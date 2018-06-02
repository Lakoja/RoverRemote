package de.lakoja.roverremote;

public class ControlCommand {
    String controlRequest;
    long requestQueueMillis;

    public ControlCommand(String request) {
        controlRequest = request;
        requestQueueMillis = System.currentTimeMillis();
    }

    public long age() {
        return System.currentTimeMillis() - requestQueueMillis;
    }
}
