package de.lakoja.roverremote;

public interface StatusListener {
    void informConnectionStatus(int returnCode, String requested, String message);
    void informRoverStatus(RoverStatus currentStatus);
}
