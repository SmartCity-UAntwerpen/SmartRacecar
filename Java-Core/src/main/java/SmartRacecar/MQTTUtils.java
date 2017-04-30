package SmartRacecar;

import org.eclipse.paho.client.mqttv3.*;

class MQTTUtils implements MqttCallback {

    private MqttClient client;
    private MqttConnectOptions options;
    private eventListener listener;

    MQTTUtils(int ID,String brokerURL, String username, String password,eventListener listener){
        String clientID = String.valueOf(ID);
        options = new MqttConnectOptions();
        this.listener = listener;

        options.setCleanSession(true);
        options.setKeepAliveInterval(30);
        //options.setUserName(username);
        //options.setPassword(password.toCharArray());

        try {
            client = new MqttClient(brokerURL,clientID);
            client.setCallback(this);
            client.connect(options);
            System.out.println("[MQTT] [DEBUG] Connected to " + brokerURL);
        } catch (MqttException e) {
            System.err.println("[MQTT] [ERROR] Could not connect to " + brokerURL + "." + e);
        }

        subscribeToTopic(clientID);
    }

    @Override
    public void connectionLost(Throwable t) {
        t.printStackTrace();
        System.err.println("[MQTT] [ERROR] Connection lost.");
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        System.out.println("[MQTT] [DEBUG] message arrived. Topic:" + topic + " | Message:" + new String(mqttMessage.getPayload()));
        //TODO Proper MQTT message handling
        jobRequest();
    }

    private void jobRequest(){
        int[] wayPoints = {1,2,3,4};
        listener.jobRequest(wayPoints);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
            System.out.println("[MQTT] [DEBUG] Publish complete.");
    }

    private void subscribeToTopic(String topic){
        try {
            int subQoS = 0;
            client.subscribe(topic, subQoS);
            System.out.println("[MQTT] [DEBUG] Subscribed to topic:" + topic);
        } catch (Exception e) {
            System.err.println("[MQTT] [ERROR] Could not subscribe to topic:" + topic + "." + e);
        }
    }

    void publishMessage(String topic,String message){
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        int pubQoS = 0;
        mqttMessage.setQos(pubQoS);
        mqttMessage.setRetained(false);

        System.out.println("[MQTT] [DEBUG] Publishing. Topic:" + topic + " | QoS " + pubQoS + " | Message:" + message);
        MqttTopic mqttTopic = client.getTopic(topic);
        MqttDeliveryToken token;
        try {
            token = mqttTopic.publish(mqttMessage);
            token.waitForCompletion();
        } catch (Exception e) {
            System.err.println("[MQTT] [ERROR] Could not Publish." + e);
        }
    }

    void closeMQTT(){
        try {
            client.disconnect();
        } catch (MqttException e) {
            System.err.println("[MQTT] [ERROR] MqttException:  " + e);
        }
    }
}
