package be.uantwerpen.fti.ds.sc.smartracecar.core;

import be.uantwerpen.fti.ds.sc.smartracecar.common.*;
import be.uantwerpen.fti.ds.sc.smartracecar.common.Map;
import com.github.lalyos.jfiglet.FigletFont;
import com.google.gson.reflect.TypeToken;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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
import java.io.*;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.util.*;
import java.util.logging.Level;

/**
 * Module representing the high-level of a vehicle.
 */
class Core implements TCPListener, MQTTListener
{

    //Standard settings (without config file loaded)
    private boolean debugWithoutRosKernel = false; 							// debug parameter for using this module without a connected RosKernel/SimKernel
    private String mqttBroker = "tcp://smartcity.ddns.net:1883"; 			// MQTT Broker URL
    private String mqqtUsername = "root"; 									// MQTT Broker Username
    private String mqttPassword = "smartcity"; 								// MQTT Broker Password
    private String restURL = "http://smartcity.ddns.net:8081/carmanager"; 	// REST Service URL to RacecarBackend
    private static int serverPort = 5005; 									// Standard TCP Port to listen on for messages from SimKernel/RosKernel.
    private static int clientPort = 5006;									// Standard TCP Port to send to messages to SimKernel/RosKernel.


	private boolean occupied; 												// To verify if racecar is currently occupied by a route job.


	//Help services
    private MQTTUtils mqttUtils;
    private TCPUtils tcpUtils;
    private RESTUtils restUtils;
    private HeartbeatPublisher heartbeatPublisher;

    //variables
    private long ID; 														// ID given by RacecarBackend to identify vehicle.
    private Log log; 														// logging instance
    private static long startPoint; 										// Starting position on map. Given by main argument.
    private HashMap<String, Map> loadedMaps; 								// Map of all loaded maps.
    private HashMap<Long, WayPoint> wayPoints; 								// Map of all loaded waypoints.
    private boolean connected = false; 										// To verify socket connection to vehicle.

	private Navigator navigator;
	private WeightManager weightManager;
	private CoreParameters params;


    /**
     * Module representing the high-level of a vehicle.
     *
     * @param startPoint Starting point of the vehicle. Defined by input arguments of main method.
     * @param serverPort Port to listen for messages of SimKernel/Roskernel. Defined by input arguments of main method.
     * @param clientPort Port to send messages to SimKernel/Roskernel. Defined by input arguments of main method.
     */
    private Core(long startPoint, int serverPort, int clientPort, CoreParameters params) throws InterruptedException, IOException
	{
        String asciiArt1 = FigletFont.convertOneLine("SmartCity");
        System.out.println(asciiArt1);
        System.out.println("------------------------------------------------------------------");
        System.out.println("--------------------- F1 Racecar Core - v1.0 ---------------------");
        System.out.println("------------------------------------------------------------------");

		this.params = params;

        this.loadedMaps = new HashMap<>();
        this.wayPoints = new HashMap<>();
        this.occupied = false;

        loadConfig();
        Log.logConfig("CORE", "Startup parameters: Starting Waypoint:" + startPoint + " | TCP Server Port:" + serverPort + " | TCP Client Port:" + clientPort);
        this.restUtils = new RESTUtils(this.restURL);
        requestWaypoints();
        register();
        /*Runtime.getRuntime().addShutdownHook(
                new Thread(new Runnable() {public void run() {
                    Thread thread = new Thread(new Runnable() {public void run(){
                        killCar();
                    }});
                    thread.start();
                    long endTimeMillis = System.currentTimeMillis() + 10000; //10 second timeout
                    while (thread.isAlive()) {
                        if (System.currentTimeMillis() > endTimeMillis) {
                            Log.logWarning("CORE", "Timeout was exceeded on exiting the system");
                            System.exit(1);
                        }
                        try {
                            Thread.sleep(500);
                        }
                        catch (InterruptedException t) {}
                    }
            }
        }));*/
        this.mqttUtils = new MQTTUtils(this.mqttBroker, this.mqqtUsername, this.mqttPassword, this);
        this.mqttUtils.subscribeToTopic("racecar/" + ID + "/#");

        this.tcpUtils = new TCPUtils(clientPort, serverPort, this);
        this.tcpUtils.start();

        if (!this.debugWithoutRosKernel)
        {
            connectSend();
        }
        this.loadedMaps = loadMaps(findMapsFolder());
        requestMap();
        Log.logConfig("CORE", "Giving the map 10s to load.");
        Thread.sleep(10000); //10 seconds delay so the map can load before publishing the startpoint
        sendStartPoint();
        Log.logConfig("CORE", "Startpoint was send");
        this.heartbeatPublisher = new HeartbeatPublisher(new MQTTUtils(this.mqttBroker, this.mqqtUsername, this.mqttPassword, this),this.ID);
        this.heartbeatPublisher.start();
        Log.logInfo("CORE", "Heartbeat publisher was started.");

		this.navigator = new Navigator(this);
		Log.logInfo("CORE", "Navigator was started.");

		this.weightManager = new WeightManager(this);
		Log.logInfo("CORE", "Weightmanager was started");
    }

    public HashMap<Long, WayPoint> getWayPoints()
	{
		return this.wayPoints;
	}

    public long getID()
	{
		return this.ID;
	}

	public CoreParameters getParams()
	{
		return this.params;
	}

	public TCPUtils getTcpUtils()
	{
		return this.tcpUtils;
	}

	public MQTTUtils getMqttUtils()
	{
		return this.mqttUtils;
	}

	public boolean isOccupied()
	{
		return this.occupied;
	}

	public void setOccupied(boolean occupied)
	{
		this.occupied = occupied;
	}

	/**
     * Help method to load all configuration parameters from the properties file with the same name as the class.
     * If it's not found then it will use the default ones.
     */
    @SuppressWarnings("Duplicates")
    private void loadConfig()
	{
        Properties prop = new Properties();
        InputStream input = null;
        try
		{
            String path = Core.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            String decodedPath = URLDecoder.decode(path, "UTF-8");
            decodedPath = decodedPath.replace("Core.jar", "");
            input = new FileInputStream(decodedPath + "/core.properties");
            prop.load(input);
            String debugLevel = prop.getProperty("debugLevel");
            switch (debugLevel)
			{
                case "debug":
                    log = new Log(this.getClass(), Level.CONFIG);
                    break;
                case "info":
                    log = new Log(this.getClass(), Level.INFO);
                    break;
                case "warning":
                    log = new Log(this.getClass(), Level.WARNING);
                    break;
                case "severe":
                    log = new Log(this.getClass(), Level.SEVERE);
                    break;
            }
            this.debugWithoutRosKernel = Boolean.parseBoolean(prop.getProperty("debugWithoutRosKernel"));
            this.mqttBroker = "tcp://" + prop.getProperty("mqttBroker");
            this.mqqtUsername = prop.getProperty("mqqtUsername");
            this.mqttPassword = prop.getProperty("mqttPassword");
            this.restURL = prop.getProperty("restURL");
            Log.logInfo("CORE", "Config loaded");
        }
        catch (IOException ex)
		{
            this.log = new Log(this.getClass(), Level.INFO);
            Log.logWarning("CORE", "Could not read config file. Loading default settings. " + ex);
        }
        finally
		{
            if (input != null)
            {
                try
				{
                    input.close();
                }
                catch (IOException e)
				{
                    Log.logWarning("CORE", "Could not read config file. Loading default settings. " + e);
                }
            }
        }
    }

    /**
     * Load all current available offline maps from the /mapFolder folder.
     * It reads the maps.xml file with all the necessary information.
     *
     * @param mapFolder location of the maps.xml file.
     * @return Returns a Hashmap<String,Map> where the String is the mapname. It contains all loaded maps.
     */
    private HashMap<String, Map> loadMaps(String mapFolder)
	{
        HashMap<String, Map> loadedMaps = new HashMap<>();
        try
		{
            File fXmlFile = new File(mapFolder);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(fXmlFile);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("map");

            for (int temp = 0; temp < nList.getLength(); temp++)
            {

                Node nNode = nList.item(temp);

                if (nNode.getNodeType() == Node.ELEMENT_NODE)
                {

                    Element eElement = (Element) nNode;
                    String name = eElement.getElementsByTagName("name").item(0).getTextContent();
                    loadedMaps.put(name, new Map(name));
                    Log.logConfig("CORE", "Added map: " + name + ".");
                }
            }
        }
        catch (Exception e)
		{
            Log.logSevere("CORE", "Could not correctly load XML of maps." + e);
        }
        return loadedMaps;
    }

    /**
     * Find the location of the maps.xml containing folder. Searches up to 3 levels deep.
     *
     * @return returns a String containing the location of the maps.xml's absolute path.
     */
    private String findMapsFolder()
	{
        FileUtils fileUtils = new FileUtils();
        fileUtils.searchDirectory(new File(".."), "maps.xml");
        if (fileUtils.getResult().size() == 0)
        {
            fileUtils.searchDirectory(new File("./.."), "maps.xml");
            if (fileUtils.getResult().size() == 0)
            {
                fileUtils.searchDirectory(new File("./../.."), "maps.xml");
                if (fileUtils.getResult().size() == 0)
                {
                    fileUtils.searchDirectory(new File("./../../.."), "maps.xml");
                    if (fileUtils.getResult().size() == 0)
                    {
                        Log.logSevere("CORE", "maps.xml not found. Make sure it exists in some folder (maximum 3 levels deep).");
                        System.exit(0);
                    }
                }
            }
        }
        String output = null;
        for (String matched : fileUtils.getResult())
        {
            output = matched;
        }
        return output;
    }

    /**
     * Send connection request over sockets to RosKernel/SimKernel.
     */
    private void connectSend()
	{
        this.tcpUtils.sendUpdate(JSONUtils.keywordToJSONString("connect"));
    }

    /**
     * Event to be called when connection to car has been made.
     */
    private void connectReceive()
	{
        Log.logInfo("CORE", "Connected to car.");
    }

    /**
     * Register vehicle with RacecarBackend over REST.
     */
    private void register()
	{
        String id = this.restUtils.getTextPlain("register/" + Long.toString(this.startPoint));
        this.ID = Long.parseLong(id, 10);
        Log.logInfo("CORE", "Vehicle received ID " + this.ID + ".");
    }

    /**
     * Request all possible waypoints from RaceCarManager over REST
     */
    private void requestWaypoints()
	{
        Type typeOfHashMap = new TypeToken<HashMap<Long, WayPoint>>()
		{
        }.getType();
        this.wayPoints = (HashMap<Long, WayPoint>) JSONUtils.getObjectWithKeyWord(restUtils.getJSON("getwaypoints"), typeOfHashMap);
        assert this.wayPoints != null;
        for (WayPoint wayPoint : this.wayPoints.values())
        {
            Log.logConfig("CORE", "Waypoint " + wayPoint.getID() + " added: " + wayPoint.getX() + "," + wayPoint.getY() + "," + wayPoint.getZ() + "," + wayPoint.getW());
        }
        Log.logInfo("CORE", "All possible waypoints(" + this.wayPoints.size() + ") received.");
    }

    /**
     * Sends starting point to the vehicle's SimKernel/RosKernel over socket connection.
     */
    private void sendStartPoint()
	{
        Log.logInfo("CORE", "Starting point set as waypoint with ID " + startPoint + ".");
        if (!this.debugWithoutRosKernel)
            this.tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("startPoint", this.wayPoints.get(this.startPoint)));
    }

    /**
     * REST GET request to RacecarBackend to request the name of the current map. If this map is not found in the offline available maps, it does another
     * REST GET request to download the map files and store it in the mapfolder and add it to the maps.xml file.
     * After that it sends this information to the vehicle SimKernel/SimKernel over the socket connection.
     */
    private void requestMap()
	{
        String mapName = this.restUtils.getTextPlain("getmapname");
        if (this.loadedMaps.containsKey(mapName))
        {
            Log.logInfo("CORE", "Current used map '" + mapName + "' found in folder, setting as current map.");
            if (!this.debugWithoutRosKernel)
                this.tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("currentMap", this.loadedMaps.get(mapName)));
        }
        else
		{
            Log.logConfig("CORE", "Current used map '" + mapName + "' not found. Downloading...");
            this.restUtils.getFile("getmappgm/" + mapName, findMapsFolder(), mapName, "pgm");
            this.restUtils.getFile("getmapyaml/" + mapName, findMapsFolder(), mapName, "yaml");
            Map map = new Map(mapName);
            try
			{
                DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();

                Document document = documentBuilder.parse(findMapsFolder() + "/maps.xml");
                Element root = document.getDocumentElement();

                Element newMap = document.createElement("map");

                Element newName = document.createElement("name");
                newName.appendChild(document.createTextNode(mapName));
                newMap.appendChild(newName);

                root.appendChild(newMap);

                DOMSource source = new DOMSource(document);

                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                Transformer transformer = transformerFactory.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                StreamResult result = new StreamResult(findMapsFolder() + "/maps.xml");
                transformer.transform(source, result);

            }
            catch (ParserConfigurationException | SAXException | IOException | TransformerException e)
			{
                Log.logWarning("CORE", "Could not add map to XML of maps." + e);
            }
            this.loadedMaps.put(mapName, map);
            Log.logConfig("CORE", "Added downloaded map : " + mapName + ".");
            Log.logInfo("CORE", "Current map '" + mapName + "' downloaded and set as current map.");
            if (!this.debugWithoutRosKernel)
                this.tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("currentMap", this.loadedMaps.get(mapName)));
        }
    }

    /**
     * Interfaced method to parse MQTT message and topic after MQTT callback is triggered by incoming message.
     * Used by messages coming from RacecarBackend to request a route job or a cost calculation request.
     *
     * @param topic   received MQTT topic
     * @param message received MQTT message string
     */
    public void parseMQTT(String topic, String message)
	{
        if (topic.matches("racecar/[0-9]+/job"))
        {
        	this.navigator.handleJobRequest(message);
        }
        else if (topic.matches("racecar/[0-9]+/costrequest"))
        {
            String[] wayPointStringValues = message.split(" ");
            try {
                long[] wayPointValues = new long[wayPointStringValues.length];
                for (int index = 0; index < wayPointStringValues.length; index++)
                {

                    wayPointValues[index] = Integer.parseInt(wayPointStringValues[index]);
                }
                this.weightManager.costRequest(wayPointValues);
            }
            catch (NumberFormatException e)
			{
                Log.logWarning("CORE", "Parsing MQTT gives bad result: " + e);
            }
        }
        else if (topic.matches("racecar/[0-9]+/changeMap"))
        {
                requestMap();
        }
    }

    /**
     * Interfaced method to parse TCP message socket callback is triggered by incoming message.
     * Used for messages about percentage route completion, next waypoint arrived, initial connection setup,
     * stopping/killing/starting/restarting vehicle, setting the startpoint or cost calculation response.
     *
     * @param message received TCP socket message string
     * @return a return answer to be send back over the socket to the SimKernel/RosKernel
     */
    public String parseTCP(String message)
	{
        if (JSONUtils.isJSONValid(message))
        {
            //parses keyword to do the correct function call.
            switch (JSONUtils.getFirst(message))
			{
                case "percentage":
                    this.navigator.locationUpdate((Location) JSONUtils.getObjectWithKeyWord(message, Location.class));
                    break;
                case "arrivedWaypoint":
                    this.navigator.wayPointReached();
                    break;
                case "connect":
                    this.connectReceive();
                    break;
                case "kill":
                    this.killCar();
                    break;
                case "stop":
                    this.sendAvailability(false);
                    break;
                case "start":
                    this.sendAvailability(true);
                    break;
                case "startpoint":
                    this.startPoint = (long) JSONUtils.getObjectWithKeyWord(message, Long.class);
                    this.mqttUtils.publishMessage("racecar/" + ID + "/locationupdate", Long.toString((Long) JSONUtils.getObjectWithKeyWord(message, Long.class)));
                    Log.logInfo("CORE", "Setting new starting point with ID " + JSONUtils.getObjectWithKeyWord(message, Long.class));
                    break;
                case "restart":
                    this.sendAvailability(true);
                    this.tcpUtils.sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("currentPosition", this.wayPoints.get(Core.startPoint)));
                    Log.logInfo("CORE", "Vehicle restarted.");
                    break;
                case "cost":
                    this.weightManager.costComplete((Cost) JSONUtils.getObjectWithKeyWord(message, Cost.class));
                    break;
                case "costtiming":
                    this.timeComplete((Cost) JSONUtils.getObjectWithKeyWord(message, Cost.class));
                    break;
                case "location":
                    //Log.logInfo("CORE", "Car is at coordinates: " + (String) JSONUtils.getObjectWithKeyWord(message, String.class));
                    // the current location is published but is not useful for the smartcityproject, the percentage updates are used
                    break;
                default:
                    Log.logWarning("CORE", "No matching keyword when parsing JSON from Sockets. Data: " + message);
                    break;
            }
        }
        return null;
    }

	/**
	 * Called by incoming timing calculation requests. Sends the request further to the RosKernel/SimKernel.
	 *
	 * @param wayPointIDs Array of waypoint ID's to have their timing calculated.
	 */
	public void timeRequest(long[] wayPointIDs)
	{
		List<Point> points = new ArrayList<>();
		points.add(this.wayPoints.get(wayPointIDs[0]));
		points.add(this.wayPoints.get(wayPointIDs[1]));
		if (!this.debugWithoutRosKernel)
		{
			this.tcpUtils.sendUpdate(JSONUtils.arrayToJSONStringWithKeyWord("costtiming", points));
		}
		else
		{
			this.weightManager.costComplete(new Cost(false, 5, 5, this.ID));
		}
	}

    /**
     * Set availability status of vehicle in the RacecarBackend by sending MQTT message.
     *
     * @param state state to be send. (available=true, unavailable=false)
     */
    private void sendAvailability(boolean state)
	{
        this.mqttUtils.publishMessage("racecar/" + ID + "/available", Boolean.toString(state));
        Log.logInfo("CORE", "Vehicle's availability status set to " + state + '.');
    }

    /**
     * Closes all connections (TCP & MQTT), unregisters the vehicle with the RacecarBackend and shut the module down.
     */
    private void killCar()
	{
        Log.logInfo("CORE", "Vehicle kill request. Closing connections and shutting down...");
        this.restUtils.getCall("delete/" + this.ID);
        if (!this.debugWithoutRosKernel) this.tcpUtils.closeTCP();
		{
			this.mqttUtils.closeMQTT();
		}
        System.exit(0);
    }


    /**
     * When vehicle has completed cost calculation for the locationUpdate() function it sets the variables
     * costCurrentToStartTiming and costStartToEndTiming of the Core to be used by locationUpdate().
     *
     * @param cost Cost object containing the weights of the sub-routes.
     */
    private void timeComplete(Cost cost)
	{
        this.navigator.setCostCurrentToStartTiming(cost.getWeightToStart());
        this.navigator.setCostStartToEndTiming(cost.getWeight());
    }

    /**
     * Main method, used to create a Core object and run it.
     * @param args required arguments: startpoint, tcpclientport and tcpserverport
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, InterruptedException
	{

        if (args.length == 0)
        {
            System.out.println("Need at least 1 or 3 argument to run. Possible arguments: startpoint(int)(needed!) tcpclientport(int) tcpserverport(int)");
            System.exit(0);
        } else if (args.length == 1)
        {
            if (!args[0].isEmpty()) startPoint = Long.parseLong(args[0]);
        } else if (args.length == 2)
        {
            System.out.println("Need at least 1 or 3 argument to run. Possible arguments: startpoint(int)(needed!) tcpclientport(int) tcpserverport(int)");
            System.exit(0);
        }
        else
		{
            if (!args[0].isEmpty()) startPoint = Long.parseLong(args[0]);
            if (!args[1].isEmpty()) serverPort = Integer.parseInt(args[1]);
            if (!args[2].isEmpty()) clientPort = Integer.parseInt(args[2]);
        }
        final Core core = new Core(startPoint, serverPort, clientPort, new CoreParameters(false));
    }

}