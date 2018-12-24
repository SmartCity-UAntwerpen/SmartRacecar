package be.uantwerpen.fti.ds.sc.racecarbackend;

import be.uantwerpen.fti.ds.sc.common.*;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Controller
public class MapManager implements MQTTListener, WaypointValidator, WaypointRepository
{
	private Logger log;
	private MapManagerParameters params;

	private VehicleRepository vehicleRepository;

	private MQTTUtils mqttUtils;

	private Map<Long, WayPoint> wayPoints;

	@Autowired
	public MapManager(MapManagerParameters params, @Lazy VehicleRepository vehicleRepository)
	{
		this.log = LoggerFactory.getLogger(this.getClass());
		this.params = params;

		this.log.info("Initializing Map Manager...");

		try
		{
			this.mqttUtils = new MQTTUtils(params.getMqttBroker(), params.getMqttUserName(), params.getMqttPassword(), this);
		}
		catch (MqttException me)
		{
			this.log.error("Failed to create MQTTUtils for MapManager.");
		}

		this.wayPoints = new HashMap<>();

		this.vehicleRepository = vehicleRepository;

		this.loadWayPoints(params.getCurrentMap());

		this.log.info("Initialized Map Manager.");
	}

	public boolean exists(long id)
	{
		this.log.info("Checking if " + id + " exists in the current map.");
		return wayPoints.containsKey(id);
	}

	/**
	 * REST GET server service to get the currently used map.
	 *
	 * @return REST response of the type Text Plain containing the mapname.
	 */
	@RequestMapping(value="/carmanager/getmapname", method=RequestMethod.GET, produces=MediaType.TEXT_PLAIN)
	public @ResponseBody ResponseEntity<String> getMapName()
	{
		return new ResponseEntity<>(this.params.getCurrentMap(), HttpStatus.OK);
	}

	/**
	 * REST GET server service to download a map's PGM file by name.
	 *
	 * @param mapName the name of the map
	 * @return REST response of the type Octet-stream containing the file.
	 */
	@RequestMapping(value="/carmanager/getmappgm/{mapName}", method=RequestMethod.GET, produces=MediaType.APPLICATION_OCTET_STREAM)
	public @ResponseBody ResponseEntity<Resource> getMapPGM(@PathVariable("mapName") String mapName)
	{
		try
		{
			String resourcePath = this.params.getMapPath() + "/" + mapName + ".pgm";

			InputStreamResource resource = new InputStreamResource(new FileInputStream(resourcePath));
			HttpHeaders headers = new HttpHeaders();
			headers.add("content-disposition", "attachment");

			this.log.info("Serving request for " + resourcePath);

			return new ResponseEntity<>(resource, headers, HttpStatus.OK);
		}
		catch(IOException ioe)
		{
			String errorString = "Error fetching " + mapName + ".pgm.";
			this.log.error(errorString, ioe);
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}

	/**
	 * REST GET server service to get all currently used wayPoints by F1 vehicles.
	 *
	 * @return REST response of the type JSON containing all wayPoints.
	 */
	@RequestMapping(value="/carmanager/getwaypoints", method=RequestMethod.GET, produces=MediaType.APPLICATION_JSON)
	public @ResponseBody ResponseEntity<String> getWayPoints()
	{
		this.log.info("Received request for all waypoint on map. Returning " + this.wayPoints.size() + " waypoints.");
		return new ResponseEntity<>(JSONUtils.objectToJSONStringWithKeyWord("wayPoints", this.wayPoints), HttpStatus.OK);
	}

	/**
	 * REST GET server service to download a map's YAML file by name.
	 *
	 * @param mapName the name of the map
	 * @return REST response of the type Octet-stream containing the file.
	 */
	@RequestMapping(value = "/carmanager/getmapyaml/{mapName}", method = RequestMethod.GET, produces = MediaType.APPLICATION_OCTET_STREAM)
	public @ResponseBody ResponseEntity<Resource> getMapYAML(@PathVariable("mapName") final String mapName)
	{
		try
		{
			String resourcePath = this.params.getMapPath() + "/" + mapName + ".yaml";

			InputStreamResource resource = new InputStreamResource(new FileInputStream(resourcePath));
			HttpHeaders headers = new HttpHeaders();
			headers.add("content-disposition", "attachment");

			this.log.info("Serving request for " + resourcePath);

			return new ResponseEntity<>(resource, headers, HttpStatus.OK);
		}
		catch (IOException ioe)
		{
			String errorString = "Error fetching " + mapName + ".yaml.";
			this.log.error(errorString, ioe);
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}

	/**
	 * Rest command that can be called to change the map used by the racecars at runtime
	 *
	 * @param mapName name of the new map
	 * @return
	 */
	@RequestMapping(value = "/carmanager/changeMap/{mapName}", method = RequestMethod.GET, produces = MediaType.TEXT_PLAIN)
	public @ResponseBody ResponseEntity<String> changeMap(@PathVariable("mapName") String mapName)
	{
		File mapFile = new File(this.params.getMapPath() + "/" + mapName + ".yaml");

		if (mapFile.exists() && mapFile.isFile())
		{
			this.params.setCurrentMap(mapName);

			this.log.info("Publishing map change.");

			try
			{
				this.mqttUtils.publish(this.params.getMqttTopic() + "changeMap/#", mapName);
			}
			catch (MqttException me)
			{
				this.log.error("Failed to publish map change.", me);
			}

			this.loadWayPoints(this.params.getCurrentMap());

			this.log.info("Changed current map to " + mapName);

			return new ResponseEntity<>(mapName, HttpStatus.OK);
		}
		else
		{
			String errorString = "Map cannot be changed as the map (\"" + mapName + "\") does not exist";
			this.log.warn(errorString);
			return new ResponseEntity<>(errorString, HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Request all possible wayPoints from the BackBone through a REST Get request.
	 */
	private void loadWayPoints(String mapName)
	{
		this.log.info("Loading wayPoints for " + mapName);
		this.wayPoints.clear();
		// Temp wayPoints for when they can't be requested from back-end services.
		switch (mapName)
		{
			case "zbuilding":
				this.wayPoints.put(46L, new WayPoint(46, 0.5f, 0.0f, -1.0f, 0.02f));
				this.wayPoints.put(47L, new WayPoint(47, -13.4f, -0.53f, 0.71f, 0.71f));
				this.wayPoints.put(48L, new WayPoint(48, -27.14f, -1.11f, -0.3f, 0.95f));
				this.wayPoints.put(49L, new WayPoint(49, -28.25f, -9.19f, -0.71f, 0.71f));
				break;
			case "V314":
				this.wayPoints.put(46L, new WayPoint(46, -3.0f, -1.5f, 0.07f, 1.00f));
				this.wayPoints.put(47L, new WayPoint(47, 1.10f, -1.20f, 0.07f, 1.00f));
				this.wayPoints.put(48L, new WayPoint(48, 4.0f, -0.90f, -0.68f, 0.73f));
				this.wayPoints.put(49L, new WayPoint(49, 4.54f, -4.49f, -0.60f, 0.80f));
				break;
			case "gangV":
				this.wayPoints.put(46L, new WayPoint(46, -6.1f, -28.78f, 0.73f, 0.69f));
				this.wayPoints.put(47L, new WayPoint(47, -6.47f, -21.69f, 0.66f, 0.75f));
				this.wayPoints.put(48L, new WayPoint(48, -5.91f, -1.03f, 0.52f, 0.85f));
				this.wayPoints.put(49L, new WayPoint(49, 6.09f, 0.21f, -0.04f, 1.00f));
				break;
			case "U014":
				this.wayPoints.put(46L, new WayPoint(46, (float) 0.07, (float) 0.15, (float) 0.99, (float) 0.05));
				this.wayPoints.put(47L, new WayPoint(47, (float) 4.61, (float) 1.82, (float) 0.72, (float) 0.69));
				this.wayPoints.put(48L, new WayPoint(48, (float) 0.3, (float) 3.85, (float) -0.99, (float) 0.18));
				this.wayPoints.put(49L, new WayPoint(49, (float) 4.35, (float) 1.86, (float) -0.70, (float) 0.711));
				break;
			case "U014Circle":
				this.wayPoints.put(0L, new WayPoint(0, 0.0f, -0.0f, 0.0f, 0.99f));
				this.wayPoints.put(1L, new WayPoint(1, 6.54f, 3.33f, 0.71f, 0.7f));
				this.wayPoints.put(2L, new WayPoint(2, 3.84f, 2.73f, -0.63f, 0.77f));
				this.wayPoints.put(3L, new WayPoint(3, 0.5f, 2.74f, -0.99f, 0.04f));
				break;
			default:
				log.warn("There are no default wayPoints for \"" + mapName + "\".");
				this.wayPoints.put(46L, new WayPoint(46, 0.5f, 0.0f, -1.0f, 0.02f));
				this.wayPoints.put(47L, new WayPoint(47, -13.4f, -0.53f, 0.71f, 0.71f));
				this.wayPoints.put(48L, new WayPoint(48, -27.14f, -1.11f, -0.3f, 0.95f));
				this.wayPoints.put(49L, new WayPoint(49, -28.25f, -9.19f, -0.71f, 0.71f));
		}

		this.log.info("All possible wayPoints(" + wayPoints.size() + ") received.");
	}

	@Override
	public Point getCoordinates(long waypointId)
	{
		this.log.info("Fetching coordinates for waypoint " + waypointId + ".");

		if (!this.wayPoints.containsKey(waypointId))
		{
			this.log.error("Tried to fetch coordinates for non-existent waypoint " + waypointId + ".");
			return new Point(0.0f, 0.0f, 0.0f, 0.0f);
		}

		return this.wayPoints.get(waypointId);
	}

	@Override
	public void parseMQTT(String topic, String message)
	{
	}
}
