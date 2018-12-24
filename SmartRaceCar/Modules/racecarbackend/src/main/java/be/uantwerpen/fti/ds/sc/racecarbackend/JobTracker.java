package be.uantwerpen.fti.ds.sc.racecarbackend;

import be.uantwerpen.fti.ds.sc.common.*;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@Controller
public class JobTracker implements MQTTListener
{
    private static final String ROUTE_UPDATE_DONE = "done";
    private static final String ROUTE_UPDATE_ERROR = "error";
    private static final String ROUTE_UPDATE_NOT_COMPLETE = "notcomplete";

    private static final String MQTT_PROGRESS_POSTFIX = "percentage/#";
    private static final String MQTT_ROUTEUPDATE_POSTFIX = "route/#";

    private static final int ALMOST_DONE_PERCENTAGE = 90;
    // We need to contact the backbone if we're "almost there"
    // No concrete definition of "almost" has been given, so
    // I'm choosing one here. It's 90%.

    private Logger log;
    private BackendParameters backendParameters;
    private VehicleManager vehicleManager;
    private JobDispatcher jobDispatcher;
    private MQTTUtils mqttUtils;
    private Map<Long, Job> localJobs;       // Map containing local jobs mapped to their IDs
                                            // Local jobs are jobs not present in the backbone,
                                            // they are tracked locally to send vehicles to the startpoint of jobs etc.
    private Map<Long, Job> globalJobs;      // Map containing jobs mapped to their job ID's

    private boolean isProgressUpdate(String topic)
    {
        return topic.startsWith(this.backendParameters.getMqttTopic() + MQTT_PROGRESS_POSTFIX);
    }

    private boolean isRouteUpdate(String topic)
    {
        return topic.startsWith(this.backendParameters.getMqttTopic() + MQTT_ROUTEUPDATE_POSTFIX);
    }

    private JobType findJobType(long jobId, long vehicleId)
    {
        if (this.localJobs.containsKey(jobId))
        {
            if (this.localJobs.get(jobId).getVehicleId() == vehicleId)
            {
                return JobType.LOCAL;
            }
        }
        else if (this.globalJobs.containsKey(jobId))
        {
            if (this.globalJobs.get(jobId).getVehicleId() == vehicleId)
            {
                return JobType.GLOBAL;
            }
        }

        throw new NoSuchElementException("Tried to find type for job " + jobId + " (Vehicle: " + vehicleId + "), but no job matched the IDs.");
    }

    private void removeJob(long jobId, long vehicleId)
    {
        switch (this.findJobType(jobId, vehicleId))
        {
            case GLOBAL:
                this.globalJobs.remove(jobId);
                break;

            case LOCAL:
                this.localJobs.remove(jobId);
                break;
        }
    }

    /**
     * Return the job being executed by the vehicle with id vehicleID.
     * Returns -1 if no job was found for the given vehicle.
     * @param vehicleID
     * @return
     */
    @Deprecated
    private long findJobByVehicleId(long vehicleID) throws NoSuchElementException
    {
        for (long jobId: this.globalJobs.keySet())
        {
            if (this.globalJobs.get(jobId).getVehicleId() == vehicleID)
            {
                return jobId;
            }
        }

        for (long jobId: this.localJobs.keySet())
        {
            if (this.localJobs.get(jobId).getVehicleId() == vehicleID)
            {
                return jobId;
            }
        }

        throw new NoSuchElementException("Failed to find job associated with vehicle " + vehicleID);
    }

    private void completeJob(long jobId, long vehicleId)
    {
        this.log.debug("Completing job, setting vehicle " + vehicleId + " to unoccupied.");
        this.vehicleManager.setOccupied(vehicleId, false);

        // We should only inform the backend if the job was a global job.
        if ((!this.backendParameters.isBackboneDisabled()) && (this.findJobType(jobId, vehicleId) == JobType.GLOBAL))
    {
            this.log.debug("Informing Backbone about job completion.");

            RESTUtils backboneRESTUtil = new RESTUtils(this.backendParameters.getBackboneRESTURL());
            backboneRESTUtil.postEmpty("/jobs/complete/" + jobId);
        }

        this.removeJob(jobId, vehicleId);
    }

    private void updateRoute(long jobId, long vehicleId, String mqttMessage)
    {
        switch (mqttMessage)
        {
            case ROUTE_UPDATE_DONE:
                this.log.info("Vehicle " + vehicleId + " completed job " + jobId + ".");
                this.completeJob(jobId, vehicleId);
                break;

            case ROUTE_UPDATE_ERROR:
                this.log.info("Vehicle " + vehicleId + " completed its route with errors.");
                this.vehicleManager.setOccupied(vehicleId, false);
                this.removeJob(jobId, vehicleId);
                break;

            case ROUTE_UPDATE_NOT_COMPLETE:
                this.log.info("Vehicle " + vehicleId + " hasn't completed its route yet.");
                this.vehicleManager.setOccupied(vehicleId, true);
                break;
        }
    }

    private void updateProgress(long jobId, long vehicleId, int progress)
    {
        JobType type = this.findJobType(jobId, vehicleId);
        Job job = null;

        switch (type)
        {
            case GLOBAL:
                job = this.globalJobs.get(jobId);
                break;

            case LOCAL:
                job = this.localJobs.get(jobId);
                break;
        }

        job.setProgress(progress);

        // Now we just need to inform the backbone if the job is "almost" complete.
        // If the job is local, the backbone is not aware of the job and we're done now
        if (type == JobType.LOCAL)
        {
            return;
        }

        if ((!this.backendParameters.isBackboneDisabled()) && (!job.isBackboneNotified()) && (progress >= ALMOST_DONE_PERCENTAGE))
        {
            RESTUtils backboneRESTUtil = new RESTUtils(this.backendParameters.getBackboneRESTURL());
            backboneRESTUtil.postEmpty("/jobs/vehiclecloseby/" + jobId);
            job.setBackboneNotified(true);
        }
    }

    @Autowired
    public JobTracker(@Qualifier("backend") BackendParameters backendParameters, VehicleManager vehicleManager, @Lazy JobDispatcher jobDispatcher)
    {
        this.log = LoggerFactory.getLogger(JobTracker.class);
        this.backendParameters = backendParameters;
        this.vehicleManager = vehicleManager;
        this.jobDispatcher = jobDispatcher;

        this.log.info("Initializing JobTracker...");

        try
        {
            this.mqttUtils = new MQTTUtils(backendParameters.getMqttBroker(), backendParameters.getMqttUserName(), backendParameters.getMqttPassword(), this);
            this.mqttUtils.subscribe(backendParameters.getMqttTopic() + MQTT_PROGRESS_POSTFIX);
            this.mqttUtils.subscribe(backendParameters.getMqttTopic() + MQTT_ROUTEUPDATE_POSTFIX);
        }
        catch (MqttException me)
        {
            this.log.error("Failed to create MQTTUtils for JobTracker.", me);
        }

        this.globalJobs = new HashMap<>();
        this.localJobs = new HashMap<>();

        this.log.info("Initialized JobTracker.");
    }

    public void addGlobalJob(long jobId, long vehicleId, long startId, long endId)
    {
        this.log.info("Adding new Global Job for tracking (Job ID: " + jobId + ", " + startId + " -> " + endId + ", Vehicle: " + vehicleId + ").");
        Job job = new Job(jobId, startId, endId, vehicleId);
        this.globalJobs.put(jobId, job);
    }

    public void addLocalJob(long jobId, long vehicleId, long startId, long endId)
    {
        this.log.info("Adding new Local Job for tracking (Job ID: " + jobId + ", " + startId + " -> " + endId + ", Vehicle: " + vehicleId + ").");
        Job job = new Job(jobId, startId, endId, vehicleId);
        this.localJobs.put(jobId, job);
    }

    public long generateLocalJobId()
    {
        // We iterate over i and find the first (lowest) value not present in the map
        long i = 0;
        for (i = 0; this.localJobs.containsKey(i); ++i)
        {
        }

        return i;
    }

    public boolean exists(long jobId)
    {
    	if (this.globalJobs.containsKey(jobId))
	    {
	    	return true;
	    }
    	else if (this.localJobs.containsKey(jobId))
	    {
	    	return true;
	    }

    	return false;
    }

    /*
     *
     *  REST Endpoints
     *
     */
    @RequestMapping(value="/job/getprogress/{jobId}", method= RequestMethod.GET, produces=MediaType.APPLICATION_JSON)
    public @ResponseBody ResponseEntity<String> getProgress(@PathVariable long jobId)
    {
        if (!this.globalJobs.containsKey(jobId))
        {
            String errorString = "Tried to query progress of job " + jobId + ", but job doesn't exist.";
            this.log.error(errorString);
            return new ResponseEntity<>(errorString, HttpStatus.NOT_FOUND);
        }

        if (this.jobDispatcher.isInQueue(jobId, JobType.GLOBAL))
        {
            String jsonString = JSONUtils.objectToJSONStringWithKeyWord("progress", 0);
            return new ResponseEntity<>(jsonString, HttpStatus.OK);
        }

        String jsonString = JSONUtils.objectToJSONStringWithKeyWord("progress", this.globalJobs.get(jobId).getProgress());
        return new ResponseEntity<>(jsonString, HttpStatus.OK);
    }

    /*
     *
     *  MQTT Parsing
     *
     */
    /**
     *
     * @param topic   received MQTT topic
     * @param message received MQTT message string
     */
    @Override
    public void parseMQTT(String topic, String message)
    {
        long vehicleId = TopicUtils.getCarId(topic);

        try
        {
            if (this.isProgressUpdate(topic))
            {
                long jobId = this.findJobByVehicleId(vehicleId);
                int percentage = Integer.parseInt(message);
                this.log.info("Received Percentage update for vehicle " + vehicleId + ", Job: " + jobId + ", Status: " + percentage + "%.");
                this.updateProgress(jobId, vehicleId, percentage);
            }
            else if (this.isRouteUpdate(topic))
            {
                long jobId = this.findJobByVehicleId(vehicleId);
                this.log.info("Received Route Update for vehicle " + vehicleId + "");
                this.updateRoute(jobId, vehicleId, message);
            }
        }
        catch (NoSuchElementException nsee)
        {
            this.log.error("Failed to find job for vehicle " + vehicleId, nsee);
        }
    }
}
