package be.uantwerpen.fti.ds.sc.racecarbackend;

import be.uantwerpen.fti.ds.sc.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.management.QueryEval;
import javax.ws.rs.core.MediaType;
import java.util.*;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class VehicleManager implements MQTTListener, VehicleRepository
{
	private static final String MQTT_POSTFIX = "available/#";

	private Logger log;
	private Parameters parameters;
	private MQTTUtils mqttUtils;
	private WaypointValidator waypointValidator;
	private Queue<Long> unusedIds;                    // This set contains all IDs of vehicles that were assigned once and then deleted
													// its a simple way to reuse IDs.
	private Map<Long, Vehicle> vehicles;

	private String getAvailabilityString(boolean available)
	{
		return available ? "Available" : "Not Available";
	}

	/**
	 * Check if a vehicle with the given ID exists.
	 * @param vehicleId
	 * @return
	 */
	private boolean exists(long vehicleId)
	{
		return this.vehicles.containsKey(vehicleId);
	}

	@Autowired
	public VehicleManager(@Qualifier("backend") BackendParameters parameters, WaypointValidator waypointValidator)
	{
		this.parameters = parameters;
		this.log = LoggerFactory.getLogger(this.getClass());

		this.log.info("Initializing Vehicle Manager...");

		this.mqttUtils = new MQTTUtils(parameters.getMqttBroker(), parameters.getMqttUserName(), parameters.getMqttPassword(), this);
		this.mqttUtils.subscribeToTopic(parameters.getMqttTopic() + MQTT_POSTFIX);

		this.waypointValidator = waypointValidator;

		this.vehicles = new HashMap<>();
		this.unusedIds = new LinkedList<>();

		this.log.info("Initialized Vehicle Manager.");
	}

	@Override
	public Vehicle get(long vehicleId)
	{
		if (this.exists(vehicleId))
		{
			return this.vehicles.get(vehicleId);
		}
		else
		{
			String errorString = "Tried to access vehicle that doesn't exist! (Id: " + vehicleId + ")";
			IndexOutOfBoundsException exception = new IndexOutOfBoundsException(errorString);
			this.log.error(errorString, exception);
			throw exception;
		}
	}

	@Override
	public List<Long> getVehicleIds()
	{
		return new ArrayList<>(this.vehicles.keySet());
	}

	@Override
	public int getNumVehicles()
	{
		return this.vehicles.size();
	}

	/*
	 *
	 *      REST Endpoints
	 *
	 */
	@RequestMapping(value="/carmanager/delete/{vehicleId}", method=RequestMethod.GET)
	public @ResponseBody ResponseEntity<String> delete(@PathVariable long vehicleId)
	{
		if (this.vehicles.containsKey(vehicleId))
		{
			this.vehicles.remove(vehicleId);
			this.mqttUtils.publishMessage(this.parameters.getMqttTopic() + "/delete/" + vehicleId, "");

			this.log.info("Removing vehicle " + vehicleId);
			this.unusedIds.add(vehicleId);

			return new ResponseEntity<>(HttpStatus.OK);
		}
		else
		{
			String errorString = "Got delete request for vehicle " + vehicleId + ", but vehicle doesn't exist.";
			this.log.warn(errorString);
			return new ResponseEntity<>(errorString, HttpStatus.NOT_FOUND);
		}
	}

	@RequestMapping(value="/carmanager/register/{startWaypoint}", method=RequestMethod.GET, produces=MediaType.TEXT_PLAIN)
	public @ResponseBody ResponseEntity<String> register(@PathVariable long startWaypoint)
	{
		if (!this.waypointValidator.exists(startWaypoint))
		{
			String errorString = "Tried to register vehicle with non-existent start id. (Start Waypoint: " + startWaypoint + ")";
			this.log.error(errorString);

			return new ResponseEntity<>(errorString, HttpStatus.BAD_REQUEST);
		}

		long newVehicleId = -1;

		if (!this.unusedIds.isEmpty())
		{
			newVehicleId = this.unusedIds.remove();
		}
		else
		{
			newVehicleId = this.vehicles.size();
		}

		this.mqttUtils.publishMessage(this.parameters.getMqttTopic() + "register/" + newVehicleId, Long.toString(startWaypoint));

		this.vehicles.put(newVehicleId, new Vehicle(newVehicleId));

		this.log.info("Registered new vehicle (" + newVehicleId + "), Current Waypoint: " + startWaypoint);

		return new ResponseEntity<>(Long.toString(newVehicleId), HttpStatus.OK);
	}

	@RequestMapping(value="/carmanager/getVehicles", method=RequestMethod.GET, produces=MediaType.APPLICATION_JSON)
	public @ResponseBody ResponseEntity<String> getVehicles()
	{
		return new ResponseEntity<>(JSONUtils.objectToJSONStringWithKeyWord("vehicles", this.vehicles), HttpStatus.OK);
	}

	/**
	 * Set a vehicle to be occupied or not occupied.
	 * @param vehicleId
	 * @param occupied	true if the vehicle should become occupied, false is the vehicle should become non-occupied.
	 * @return
	 */
	public void setOccupied(long vehicleId, boolean occupied)
	{
		if (!this.vehicles.containsKey(vehicleId))
		{
			String errorString = "Tried to set non-existent vehicle's occupation to " + occupied + ", vehicle ID: " + vehicleId;
			this.log.error(errorString);
			throw new NoSuchElementException(errorString);
		}

		this.vehicles.get(vehicleId).setOccupied(occupied);
	}

	@Deprecated
	public boolean isOccupied(long vehicleId) throws NoSuchElementException
	{
		if (!this.vehicles.containsKey(vehicleId))
		{
			String errorString = "Tried to check occupancy of vehicle " + vehicleId + ", but vehicle doesn't exist!";
			this.log.error(errorString);
			throw new NoSuchElementException(errorString);
		}

		return this.vehicles.get(vehicleId).isOccupied();
	}

	/*
	 *
	 *      MQTT Parsing
	 *
	 */
	@Override
	public void parseMQTT(String topic, String message)
	{
		long id = TopicUtils.getCarId(topic);

		if (!this.exists(id))
		{
			this.log.error("Received MQTT Message from non-existent car (" + id + ".");
		}

		boolean availability = Boolean.parseBoolean(message);
		this.vehicles.get(id).setAvailable(availability);
		this.log.info("Received Availability update for vehicle " + id + ", Status: " + this.getAvailabilityString(availability));
	}
}
