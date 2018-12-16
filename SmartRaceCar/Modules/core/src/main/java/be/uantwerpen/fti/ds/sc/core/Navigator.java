package be.uantwerpen.fti.ds.sc.core;


import be.uantwerpen.fti.ds.sc.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;

public class Navigator
{
	private Core core;

	private int costCurrentToStartTiming;                                    // Time in seconds from current position to start position of route.
	private int costStartToEndTiming;                                        // Time in seconds from start position to end position of route.

	private Queue<Long> currentRoute;                                        // All waypoint IDs to be handled in the current route.
	private int routeSize;                                                   // Current route's size.
	private Logger log;
	private MQTTUtils mqttUtils;

	public Navigator(Core core)
	{
		this.log = LoggerFactory.getLogger(Navigator.class);

		this.core = core;

		this.mqttUtils = new MQTTUtils(this.core.getParams().getMqttBroker(), this.core.getParams().getMqttUserName(), this.core.getParams().getMqttPassword(), this.core);

		this.costCurrentToStartTiming = -1;
		this.costStartToEndTiming = -1;
		this.routeSize = -1;

		this.currentRoute = new LinkedList<>();
	}

	public void setCostCurrentToStartTiming(int costCurrentToStartTiming)
	{
		this.costCurrentToStartTiming = costCurrentToStartTiming;
	}

	public void setCostStartToEndTiming(int costStartToEndTiming)
	{
		this.costStartToEndTiming = costStartToEndTiming;
	}

	/**
	 * Parses and handles MQTT messages concerning job requests.
	 *
	 * @param message
	 */
	public void handleJobRequest(String message)
	{
		if (message.equals("stop"))
		{
			sendWheelStates(0, 0);
		} else
		{
			if (!this.core.isOccupied())
			{
				String[] wayPointStringValues = message.split(" ");
				try
				{
					long[] wayPointValues = new long[wayPointStringValues.length];
					for (int index = 0; index < wayPointStringValues.length; index++)
					{
						wayPointValues[index] = Integer.parseInt(wayPointStringValues[index]);
					}
					this.jobRequest(wayPointValues);
				}
				catch (NumberFormatException e)
				{
					this.log.warn("Parsing MQTT gives bad result: " + e);
				}
			}
			else
			{
				this.log.warn("Current Route not completed. Not adding waypoints.");
				this.routeNotComplete();
			}
		}
	}

	/**
	 * Event call over interface for when MQTT connection receives new route job requests from the RacecarBackend.
	 * Adds all requested waypoints to route queue one by one.
	 * Sets the vehicle to occupied. Ignores the request if vehicle is already occupied.
	 * Then triggers the first waypoint to be send to the RosKernel/SimKernel.
	 *
	 * @param wayPointIDs Array of waypoint ID's that are on the route to be completed.
	 */
	public void jobRequest(long[] wayPointIDs)
	{
		this.log.info("Route request received.");

		boolean error = false;

		if (!this.core.isOccupied())
		{
			this.core.setOccupied(true);
			this.core.timeRequest(wayPointIDs);
			while (this.costCurrentToStartTiming == -1 && this.costStartToEndTiming == -1)
			{
				this.log.info("Waiting for cost calculation");
				try
				{
					Thread.sleep(1000);
				} catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
			for (long wayPointID : wayPointIDs)
			{
				if (this.core.getWayPoints().containsKey(wayPointID))
				{
					this.currentRoute.add(wayPointID);
					this.log.info("Added waypoint with ID " + wayPointID + " to route.");
				}
				else
				{
					this.log.warn("Waypoint with ID '" + wayPointID + "' not found.");
					this.currentRoute.clear();
					error = true;
				}
			}
			if (!error)
			{
				this.routeSize = this.currentRoute.size();
				this.log.info("All waypoints(" + this.routeSize + ") of route added. Starting route.");
				this.updateRoute();
			}
			else
			{
				this.log.warn("Certain waypoints not found. Route cancelled.");
				this.routeError();
			}
		}
		else
		{
			this.log.warn("Current Route not completed. Not adding waypoints.");
			this.routeNotComplete();
		}
	}

	/**
	 * Event call over interface to be used when socket connection received message that waypoint has been reached.
	 */
	public void wayPointReached()
	{
		this.log.info("Waypoint reached.");
		this.updateRoute();
	}

	/**
	 * When vehicle's RosKernel/SimKernel sends update on route completion it needs to be transformed to the completion amount for the total route.
	 * To do this it receives a cost calculation from the RosServer that it requested and uses this to determine the size of each waypoint's sub-route.
	 *
	 * @param location Location object containing the current percentage value.
	 */
	public void locationUpdate(Location location)
	{
		if (this.currentRoute.size() == 0)
		{
			float weight = (float) this.costStartToEndTiming / (float) (this.costCurrentToStartTiming + this.costStartToEndTiming);
			location.setPercentage(Math.round((1 - weight) * 100 + location.getPercentage() * weight));
		} else if (this.currentRoute.size() == 1)
		{
			float weight = (float) this.costCurrentToStartTiming / (float) (this.costCurrentToStartTiming + this.costStartToEndTiming);
			location.setPercentage(Math.round(location.getPercentage() * weight));
		}
		this.log.info("Location Updated. Vehicle has " + location.getPercentage() + "% of route completed");
		//this.mqttUtils.publishMessage("racecar/" + this.core.getID() + "/percentage", JSONUtils.objectToJSONString(location));
		this.mqttUtils.publishMessage("racecar/" + this.core.getID() + "/percentage", Integer.toString(location.getPercentage()));
	}

	/**
	 * When all a requested route job can't be done by the vehicle as it's still completing a route.
	 * Sends MQTT message to RacecarBackend to update the route status.
	 */
	private void routeNotComplete()
	{
		this.core.setOccupied(false);
		this.mqttUtils.publishMessage("racecar/" + this.core.getID() + "/route", "notcomplete");
	}

	/**
	 * When all a requested route job can't be done by the vehicle.
	 * Sends MQTT message to RacecarBackend to update the route status.
	 */
	private void routeError()
	{
		this.log.warn("Route error. Route Cancelled");
		this.core.setOccupied(false);
		this.mqttUtils.publishMessage("racecar/" + this.core.getID() + "/route", "error");
	}

	/**
	 * To be used when when a new waypoint has to be send to the vehicle or to check if route is completed.
	 * Sends information of the next waypoint over the socket connection to the vehicle's SimKernel/RosKernel.
	 */
	private void updateRoute()
	{
		if (!this.currentRoute.isEmpty())
		{
			WayPoint nextWayPoint = this.core.getWayPoints().get(this.currentRoute.poll());

			this.log.info("Sending next waypoint with ID " + nextWayPoint.getID() + " (" + (this.routeSize - this.currentRoute.size()) + "/" + this.routeSize + ")");

			if (!this.core.getParams().isDebug())
			{
				this.core.getTcpUtils().sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("nextWayPoint", nextWayPoint));
			}
			else
			{
				//Debug code to go over all waypoints with a 3s sleep in between.
				this.log.info("debug mode -> not sending waypoints to corelinker");
				try
				{
					Thread.sleep(3000);
				} catch (InterruptedException ie)
				{
					ie.printStackTrace();
				}
				this.wayPointReached();
			}
		}
		else
		{
			this.log.info("No waypoints left in route");
			this.routeCompleted();
		}
	}

	/**
	 * When all waypoints have been completed the vehicle becomes unoccupied again.
	 * Sends MQTT message to RacecarBackend to update the vehicle status.
	 */
	private void routeCompleted()
	{
		this.log.info("Route Completed.");
		this.core.setOccupied(false);
		this.mqttUtils.publishMessage("racecar/" + this.core.getID() + "/route", "done");
	}

	/**
	 * Send wheel states to the vehicle over the socket connection. useful for emergency stops and other specific requests.
	 *
	 * @param throttle throttle value for the vehicle wheels.
	 * @param steer    rotation value for the vehicle wheels.
	 */
	private void sendWheelStates(float throttle, float steer)
	{
		this.log.info("Sending wheel state Throttle:" + throttle + ", Steer:" + steer + ".");
		if (!this.core.getParams().isDebug())
		{
			this.core.getTcpUtils().sendUpdate(JSONUtils.objectToJSONStringWithKeyWord("drive", new Drive(steer, throttle)));
		}
		else
		{
			this.log.info("Debug mode -> not sending wheel states");
		}

	}

	/**
	 * When vehicle has completed cost calculation for the locationUpdate() function it sets the variables
	 * costCurrentToStartTiming and costStartToEndTiming of the Core to be used by locationUpdate().
	 *
	 * @param cost Cost object containing the weights of the sub-routes.
	 */
	public void timingCalculationComplete(Cost cost)
	{
		this.setCostCurrentToStartTiming(cost.getWeightToStart());
		this.setCostStartToEndTiming(cost.getWeight());
		this.log.info("Timing calculation complete");
	}


}
