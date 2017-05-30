package be.uantwerpen.fti.ds.sc.smartracecar.core;

import be.uantwerpen.fti.ds.sc.smartracecar.common.*;
import be.uantwerpen.fti.ds.sc.smartracecar.common.Location;
import be.uantwerpen.fti.ds.sc.smartracecar.common.Map;
import com.google.gson.reflect.TypeToken;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Level;

//interface to trigger certain events from other objects such as TCP Sockets or MQTT.


class Core implements TCPListener, MQTTListener {

    //Hardcoded elements.
    private boolean debugWithoutRos = false; // debug parameter to stop attempts to send over sockets when ROS-Node is active.
    private Log log;
    private Level level = Level.CONFIG; //Debug level
    private final String mqttBroker = "tcp://broker.hivemq.com:1883";
    //private final String mqttBroker = "tcp://143.129.39.151:1883";
    private final String mqqtUsername = "root";
    private final String mqttPassword = "smartcity";
   // private final String restURL = "http://146.175.140.17:8080/carmanager";
    private final String restURL = "http://localhost:8080/carmanager";
    private static int serverPort = 5005;
    private static int clientPort = 5006;

    private MQTTUtils mqttUtils;
    private TCPUtils tcpUtils;
    private RESTUtils restUtils;

    private long ID; // ID given by RaceCarManager.
    private HashMap<String, Map> loadedMaps = new HashMap<>(); // Map of all loaded maps.
    private HashMap<Long, WayPoint> wayPoints = new HashMap<>(); // Map of all loaded waypoints.
    private Queue<Long> currentRoute = new LinkedList<>();// All waypoint IDs to be handled in the current route.
    private int routeSize = 0; // Current route's size.
    private boolean connected = false; // To verify socket connection to vehicle.
    private static long startPoint; // Starting position on map. Given by main argument.
    private boolean occupied = false; // To verify if racecar is currently occupied by a route job.

    private Core(long startPoint,int serverPort,int clientPort) throws InterruptedException, IOException {
        log = new Log(this.getClass(), level);
        log.logConfig("CORE","Startup parameters: Starting Waypoint:" + startPoint + " | TCP Server Port:" + serverPort + " | TCP Client Port:" + clientPort);
        restUtils = new RESTUtils(restURL);
        requestWaypoints();
        register();
        mqttUtils = new MQTTUtils(ID, mqttBroker, mqqtUsername, mqttPassword, this);
        mqttUtils.subscribeToTopic("racecar/" + ID + "/#");
        tcpUtils = new TCPUtils(serverPort, clientPort, this);
        tcpUtils.start();

        if (!debugWithoutRos) {
            connectSend();
            while (!connected) {
                Thread.sleep(1);
            }
        } else {
            connected = true;
        }
        sendStartPoint();
        loadedMaps = XMLUtils.loadMaps(findMapsFolder());
        requestMap();
    }

    private String findMapsFolder(){
        FileUtils fileUtils = new FileUtils();
        fileUtils.searchDirectory(new File(".."), "maps.xml");
        if(fileUtils.getResult().size() == 0){
            fileUtils.searchDirectory(new File("./.."), "maps.xml");
            if(fileUtils.getResult().size() == 0){
                fileUtils.searchDirectory(new File("./../.."), "maps.xml");
                if(fileUtils.getResult().size() == 0){
                    fileUtils.searchDirectory(new File("./../../.."), "maps.xml");
                    if(fileUtils.getResult().size() == 0){
                        Log.logSevere("CORE","maps.xml not found. Make sure it exists in some folder (maximum 3 levels deep).");
                        System.exit(0);
                    }
                }
            }
        }
        String output = null;
        for (String matched : fileUtils.getResult()){
                output=matched;
        }
        return output;
    }

    //Send connection check over sockets
    private void connectSend() {
        tcpUtils.sendUpdate(JSONUtils.keywordToJSONString("connect"));
    }

    //Event to be called when connection to car has been made
    private void connectReceive() {
        connected = true;
        Log.logInfo("CORE", "Connected to car.");

    }

    //Register vehicle with RaceCarManager
    private void register() {
        HashMap<String, String> queryParams = new HashMap<>();
        queryParams.put("startwaypoint", Long.toString(startPoint));
        String id = restUtils.getTextPlain("register", queryParams);
        ID = Long.parseLong(id, 10);
        Log.logInfo("CORE", "Vehicle received ID " + ID + ".");
    }

    //Request all possible waypoints from RaceCarManager
    private void requestWaypoints() {
        Type typeOfHashMap = new TypeToken<HashMap<Long, WayPoint>>() {}.getType();
        wayPoints = (HashMap<Long, WayPoint>) JSONUtils.getObjectWithKeyWord(restUtils.getJSON("getwaypoints"), typeOfHashMap);
        assert wayPoints != null;
        for (WayPoint wayPoint : wayPoints.values()) {
            Log.logConfig("CORE", "Waypoint " + wayPoint.getID() + " added: " + wayPoint.getX() + "," + wayPoint.getY() + "," + wayPoint.getZ() + "," + wayPoint.getW());
        }
        Log.logInfo("CORE", "All possible waypoints(" + wayPoints.size() + ") received.");
    }

    //Set the starting point for the vehicle. Sending it over the socket connection.
    private void sendStartPoint() {
        Log.logInfo("CORE", "Starting point set as waypoint with ID " + startPoint + ".");
        if (!debugWithoutRos)
            tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("startPoint", wayPoints.get(startPoint)));
    }


    //REST call to RaceCarManager to request the name of the current map. If this map is not found in the offline available maps, it does another
    //REST download call to download the map and store it in it's /mapFolder and add it to the maps.xml file.
    //After that it sends this information to the vehicle to be used over the socket connection.
    private void requestMap() throws IOException {
        String mapName = restUtils.getTextPlain("getmapname");
        //String mapName = "zbuilding";
        if (loadedMaps.containsKey(mapName)) {
            Log.logInfo("CORE", "Current map '" + mapName + "' found.");
            sendCurrentMap(mapName);
        } else {
            Log.logConfig("CORE", "Current map '" + mapName + "' not found. Downloading...");
            restUtils.getFile("getmappgm/" + mapName, "maps", mapName, "pgm");
            restUtils.getFile("getmapyaml/" + mapName, "maps", mapName, "yaml");
            addMap(mapName);
            Log.logInfo("CORE", "Current map '" + mapName + "' downloaded.");
            sendCurrentMap(mapName);
        }
    }

    //Add a new map to the /mapFolder folder and update the maps.xml file.
    private void addMap(String name) {
        Map map = new Map(name);
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();

            Document document = documentBuilder.parse(findMapsFolder() + "/maps.xml");
            Element root = document.getDocumentElement();

            Element newMap = document.createElement("map");

            Element newName = document.createElement("name");
            newName.appendChild(document.createTextNode(name));
            newMap.appendChild(newName);

            root.appendChild(newMap);

            DOMSource source = new DOMSource(document);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StreamResult result = new StreamResult(findMapsFolder() + "/maps.xml");
            transformer.transform(source, result);

        } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
            Log.logSevere("CORE", "Could not add map to XML of maps." + e);
        }
        loadedMaps.put(name, map);
        Log.logConfig("CORE", "Added downloaded map : " + name + ".");

    }

    //Send the name and other information of the current map to the vehicle over the socket connection.
    private void sendCurrentMap(String mapName) {
        if (!debugWithoutRos) tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("currentMap", loadedMaps.get(mapName)));

    }

    //Tto be used when when a new waypoint has to be send to the vehicle or to check if route is completed.
    //Sends information of the next waypoint over the socket connection to the vehicle.
    private void updateRoute() {
        if (!currentRoute.isEmpty()) {
            WayPoint nextWayPoint = wayPoints.get(currentRoute.poll());
            if (!debugWithoutRos) tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("nextWayPoint", nextWayPoint));
            Log.logInfo("CORE", "Sending next waypoint with ID " + nextWayPoint.getID() + " (" + (routeSize - currentRoute.size()) + "/" + routeSize + ")");
            if(debugWithoutRos){
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                wayPointReached();
            }

        } else {
            routeCompleted();
        }
    }

    //Event call over interface to be used when socket connection received message that waypoint has been reached.
    private void wayPointReached() {
        Log.logInfo("CORE", "Waypoint reached.");
        updateRoute();
    }

    //When all waypoints have been completed the vehicle becomes available again.
    private void routeCompleted() {
        Log.logInfo("CORE", "Route Completed.");
        occupied = false;
        mqttUtils.publishMessage("racecar/" + ID + "/route", "done");
    }

    private void routeError() {
        Log.logWarning("CORE", "Route error. Route Cancelled");
        occupied = false;
        mqttUtils.publishMessage("racecar/" + ID + "/route", "error");
    }

    private void routeNotComplete() {
        occupied = false;
        mqttUtils.publishMessage("racecar/" + ID + "/route", "notcomplete");
    }

    //Send wheel states to the vehicle over the socket connection. useful for emergency stops and other requests.
    private void sendWheelStates(float throttle, float steer) {
        if (!debugWithoutRos) tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("drive", new Drive(steer, throttle)));
        Log.logInfo("CORE", "Sending wheel state Throttle:" + throttle + ", Steer:" + steer + ".");
    }

    //Event call over interface for when the socket connection receives location update. Publishes this to the RaceCarManager over MQTT.
    private void locationUpdate(Location location) {
        Log.logInfo("CORE", "Location Updated.");
        mqttUtils.publishMessage("racecar/" + ID + "/percentage", JSONUtils.objectToJSONString(location));
    }

    public void parseMQTT(String topic, String message) {
        if (topic.matches("racecar/[0-9]+/job")) {
            if (message.equals("stop")) {
                sendWheelStates(0, 0);
            } else {
                if (!occupied) {
                    String[] wayPointStringValues = message.split(" ");
                    try {
                        long[] wayPointValues = new long[wayPointStringValues.length];
                        for (int index = 0; index < wayPointStringValues.length; index++) {

                            wayPointValues[index] = Integer.parseInt(wayPointStringValues[index]);
                        }
                        jobRequest(wayPointValues);
                    } catch (NumberFormatException e) {
                        Log.logWarning("CORE", "Parsing MQTT gives bad result: " + e);
                    }
                } else {
                    Log.logWarning("CORE", "Current Route not completed. Not adding waypoints.");
                    routeNotComplete();
                }
            }
        } else if (topic.matches("racecar/[0-9]+/costrequest")) {
            String[] wayPointStringValues = message.split(" ");
            try {
                long[] wayPointValues = new long[wayPointStringValues.length];
                for (int index = 0; index < wayPointStringValues.length; index++) {

                    wayPointValues[index] = Integer.parseInt(wayPointStringValues[index]);
                }
                costRequest(wayPointValues);
            } catch (NumberFormatException e) {
                Log.logWarning("CORE", "Parsing MQTT gives bad result: " + e);
            }
        }
    }

    public void parseTCP(String message) {
        if (JSONUtils.isJSONValid(message)) {
            //parses keyword to do the correct function call.
            switch (JSONUtils.getFirst(message)) {
                case "percentage":
                    locationUpdate((Location) JSONUtils.getObjectWithKeyWord(message, Location.class));
                    break;
                case "arrivedWaypoint":
                    wayPointReached();
                    break;
                case "connect":
                    connectReceive();
                    break;
                case "kill":
                    killCar();
                    break;
                case "cost":
                    costComplete((Cost) JSONUtils.getObjectWithKeyWord(message, Cost.class));
                default:
                    Log.logWarning("CORE", "No matching keyword when parsing JSON from Sockets. Data: " + message);
                    break;
            }
        }
    }

    private void killCar(){
        Log.logInfo("CORE", "Vehicle kill request. Closing connections and shutting down...");
        restUtils.getCall("delete/" + ID);
        if(!debugWithoutRos)tcpUtils.closeTCP();
        mqttUtils.closeMQTT();
        System.exit(0);
    }

    private void costRequest(long[] wayPointIDs){
        List<Point> costs = new ArrayList<>();
        costs.add(wayPoints.get(wayPointIDs[0]));
        costs.add(wayPoints.get(wayPointIDs[1]));
        if (!debugWithoutRos){
            tcpUtils.sendUpdate(JSONUtils.arrayToJSONStringWithKeyWord("cost",costs));
        }else{
            costComplete(new Cost(false,(long)5,(long)5,ID));
        }
        Log.logInfo("CORE", "Cost request send between waypoints " + wayPointIDs[0] + " and " + wayPointIDs[1] + ".");
    }

    private void costComplete(Cost cost){
        Log.logInfo("CORE", "Cost request calculated.");
        cost.setStatus(occupied);
        cost.setIdVehicle(ID);
        mqttUtils.publishMessage("racecar/" + ID + "/costanswer", JSONUtils.objectToJSONString(cost));
    }

    //Event call over interface for when MQTT connection receives new route job requests. Adds all requested waypoints to route queue one by one.
    //Sets the vehicle to occupied. Ignores the request if vehicle is already occupied.
    private void jobRequest(long[] wayPointIDs) {
        Log.logInfo("CORE", "Route request received.");
        Boolean error = false;
        if (!occupied) {
            occupied = true;
            for (long wayPointID : wayPointIDs) {
                if (wayPoints.containsKey(wayPointID)) {
                    currentRoute.add(wayPointID);
                    Log.logInfo("CORE", "Added waypoint with ID " + wayPointID + " to route.");
                } else {
                    Log.logWarning("CORE", "Waypoint with ID '" + wayPointID + "' not found.");
                    currentRoute.clear();
                    routeError();
                    error = true;
                }

            }
            if(!error){
                routeSize = currentRoute.size();
                Log.logInfo("CORE", "All waypoints(" + routeSize + ") of route added. Starting route.");
                updateRoute();
            }
        } else {
            Log.logWarning("CORE", "Current Route not completed. Not adding waypoints.");
            routeNotComplete();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        if (args.length == 0) {
            System.out.println("Need 1 or 3 argument to run. Possible arguments: startpoint(int)(needed!) tcpclientport(int) tcpserverport(int)");
            System.exit(0);
        } else if (args.length == 1) {
            if (!args[0].isEmpty()) startPoint = Long.parseLong(args[0]);
        }
        else if (args.length == 2) {
            System.out.println("Need at least 1 or 3 argument to run. Possible arguments: startpoint(int)(needed!) tcpclientport(int) tcpserverport(int)");
            System.exit(0);
        }else {
            if (!args[0].isEmpty()) startPoint = Long.parseLong(args[0]);
            if (!args[1].isEmpty()) serverPort  = Integer.parseInt(args[1]);
            if (!args[2].isEmpty()) clientPort = Integer.parseInt(args[2]);
        }
        final Core core = new Core(startPoint,serverPort,clientPort);
    }

}