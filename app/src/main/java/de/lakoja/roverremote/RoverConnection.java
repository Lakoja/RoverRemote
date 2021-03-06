/*
 * Copyright (C) 2018 Lakoja on github.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.lakoja.roverremote;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.StringTokenizer;

public class RoverConnection implements Runnable {

    private static final long ENTRY_TOO_OLD = 100;
    private static final long ENTRY_STATUS_TOO_OLD = 800;
    private static final long ENTRY_IMAGE_STATUS_TOO_OLD = 1800;

    private class QueueEntry {
        String controlRequest;
        long requestQueueMillis;

        public QueueEntry(String request) {
            controlRequest = request;
            requestQueueMillis = System.currentTimeMillis();
        }

        public long age() {
            return System.currentTimeMillis() - requestQueueMillis;
        }
    }

    private static final String TAG = RoverConnection.class.getName();

    private final String host;
    private Socket serverConnection;
    private boolean active = false;
    private StatusListener statusListener;
    private Queue<QueueEntry> commandQueue = new LinkedList<>();

    public RoverConnection(String host) {
        this.host = host;
    }

    public void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public boolean isConnected() {
        return serverConnection != null && serverConnection.isConnected();
    }

    public void openControlConnection() {
        if (!active || serverConnection == null) {
            active = true;
            new Thread(this).start();
        }
    }

    // TODO why is a connection loss (Wifi down) not noticed?


    public void sendControl(String controlRequest) {
        // TODO send confirmation to caller?
        commandQueue.add(new QueueEntry(controlRequest));
    }

    public void closeControlConnection() {
        active = false;
    }

    @Override
    public void run() {
        try {
            InetAddress serverAddr = InetAddress.getByName(host);
            try {
                serverConnection = new Socket(serverAddr, 80);
            } catch (SocketException excS) {
                if (excS.getMessage().contains("ETIMEDOUT")) {
                    // Try once again
                    try { Thread.sleep(500); } catch (InterruptedException exc) {}
                    serverConnection = new Socket(serverAddr, 80);
                } else {
                    throw excS;
                }
            }

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(serverConnection.getOutputStream()), true);

            BufferedReader reader = new BufferedReader(new InputStreamReader(serverConnection.getInputStream()));

            writer.println("control");
            String result = reader.readLine();

            if (statusListener != null) {
                statusListener.informConnectionStatus(200, "control", result);
            }

            while (active) {
                if (!commandQueue.isEmpty()) {
                    QueueEntry command = commandQueue.remove();
                    if (entryAlive(command)) {
                        long m1 = System.currentTimeMillis();
                        writer.println(command.controlRequest);
                        long m2 = System.currentTimeMillis();
                        result = reader.readLine();
                        long m3 = System.currentTimeMillis();
                        // TODO also read everything there is?
                        Log.i(TAG, "Control " + command.controlRequest + " resulted in " + result + " took w" + (m2 - m1) + " r" + (m3 - m2));

                        // TODO less or more explicit "status"
                        if (command.controlRequest.equals("status")) {
                            if (statusListener != null) {
                                // TODO support more
                                StringTokenizer tokenizer = new StringTokenizer(result, " ");
                                if (tokenizer.countTokens() >= 2) {
                                    tokenizer.nextToken(); // TODO check for VOLT (or STATUS)
                                    String voltageRaw = tokenizer.nextToken();
                                    try {
                                        float voltage = Float.parseFloat(voltageRaw);
                                        RoverStatus status = new RoverStatus(false, false, false, voltage);

                                        statusListener.informRoverStatus(status);
                                    } catch (NumberFormatException exc) {
                                        Log.e(TAG, "False rover status reply; cannot parse voltage: "+voltageRaw);
                                    }
                                } else {
                                    Log.e(TAG, "False rover status reply; too few tokens: "+result);
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "Discarding command " + command.controlRequest + " age " + command.age());
                    }
                } else {
                    try { Thread.sleep(2); } catch (InterruptedException exc) { }
                }
            }

            serverConnection.close();

        } catch (Exception exc) {
            String message = "No control connection " + exc.getMessage() + "/" + exc.getClass();
            Log.e(TAG, message);
            if (statusListener != null) {
                statusListener.informConnectionStatus(500, "control", message);
            }
        }
    }

    private boolean entryAlive(QueueEntry entry) {
        if (entry.controlRequest.endsWith(" 0")) {
            // Transmit every stop regardless of age
            return true;
        } else if (entry.controlRequest.startsWith("status") && entry.age() < ENTRY_STATUS_TOO_OLD) {
            return true;
        } else if (entry.controlRequest.startsWith("image_s") && entry.age() < ENTRY_IMAGE_STATUS_TOO_OLD) {
            return true;
        } else if (entry.age() < ENTRY_TOO_OLD) {
            return true;
        }

        return false;
    }
}
