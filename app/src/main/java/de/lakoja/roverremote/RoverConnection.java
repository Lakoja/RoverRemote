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

public class RoverConnection implements Runnable {

    public interface StatusListener {
        void informConnectionStatus(int returnCode, String requested, String message);
        void informRoverStatus(RoverStatus currentStatus);
    }

    private static final String TAG = RoverConnection.class.getName();

    private String host;
    private Socket serverConnection;
    private boolean active = false;
    private String controlRequest;
    private StatusListener statusListener;

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

    public void sendControl(String controlRequest) {
        // TODO queue this and/or send confirmation
        this.controlRequest = controlRequest;
    }

    public void closeControlConnection() {
        active = false;
    }

    @Override
    public void run() {
        try {
            InetAddress serverAddr = InetAddress.getByName(host);
            serverConnection = new Socket(serverAddr, 80);

            PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(serverConnection.getOutputStream())), true);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(serverConnection.getInputStream()));

            writer.println("control");
            String result = reader.readLine();

            if (statusListener != null) {
                statusListener.informConnectionStatus(200, "control", result);
            }

            while (active) {
                if (controlRequest != null) {
                    writer.println(controlRequest);
                    result = reader.readLine();
                    Log.i(TAG, "Control "+controlRequest+" resulted in "+result);
                    controlRequest = null;
                } else {
                    try { Thread.sleep(2); } catch (InterruptedException exc) {}
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
}
