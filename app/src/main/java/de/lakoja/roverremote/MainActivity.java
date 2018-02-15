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

import android.content.Context;
import android.content.DialogInterface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.net.InetAddress;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener, JoystickView.PositionChangeListener {

    private static final String TAG = "MainActivity";

    private ToggleButton toggleConnection;
    private ToggleButton toggleLed1;
    private ToggleButton toggleLed2;
    private ToggleButton toggleInfraLed;
    private QualityView connectionStrength;
    private QualityView connectionThroughput;
    private JoystickView positionControl;

    private WifiManager wifiManager;
    private RoverConnection roverConnection;

    // TODO app close does not close connection

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (null == wifiManager) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.cannot_start_wifi_title);
            builder.setMessage(R.string.cannot_start_wifi);
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    MainActivity.this.finish();
                }
            });
            builder.show();
        } else {
            connectButtons();

            connectionStrength = findViewById(R.id.connectionStrength);
            connectionStrength.setQuality(0);
            connectionThroughput = findViewById(R.id.connectionThroughput);
            connectionThroughput.setQuality(0);

            positionControl = findViewById(R.id.joystick);
            positionControl.setPositionChangeListener(this);
        }
    }

    private void connectButtons() {
        toggleConnection = findViewById(R.id.toggleConnection);
        toggleConnection.setOnCheckedChangeListener(this);
        toggleLed1 = findViewById(R.id.toggleLed1);
        toggleLed1.setOnCheckedChangeListener(this);
        toggleLed1.setEnabled(false);
        toggleLed2 = findViewById(R.id.toggleLed2);
        toggleLed2.setOnCheckedChangeListener(this);
        toggleLed2.setEnabled(false);
        toggleInfraLed = findViewById(R.id.toggleInfra);
        toggleInfraLed.setOnCheckedChangeListener(this);
        toggleInfraLed.setEnabled(false);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isCheckedNow) {
        if (compoundButton == toggleConnection) {
            WifiInfo wi = wifiManager.getConnectionInfo();
            if (!wi.getSSID().replaceAll("^\"|\"$", "").equals("Roversnail")) {
                Log.e(TAG, "Wifi has wrong name "+wi.getSSID());
                Toast.makeText(this, R.string.no_connection_wrong_name, Toast.LENGTH_LONG).show();
                toggleConnection.setChecked(false);
                return;
            }

            toggleLed1.setEnabled(isCheckedNow);
            toggleLed2.setEnabled(isCheckedNow);
            toggleInfraLed.setEnabled(isCheckedNow);

            if (isCheckedNow) {
                String remoteIp = determineRatIp();
                if (remoteIp != null) {
                    try {
                        roverConnection = new RoverConnection(remoteIp, this);
                        roverConnection.openControlConnection();
                    } catch (Exception exc) {
                        // TODO do more
                        Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
                        String toastText = getResources().getString(R.string.connection_failed) + " " + remoteIp;
                        Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
                    }
                }
                // TODO else?
            } else {
                roverConnection.closeControlConnection();
                roverConnection = null;
                Toast.makeText(this, R.string.disconnection_toast, Toast.LENGTH_LONG).show();
            }
        }
    }

    private String determineRatIp() {
        WifiInfo wi = wifiManager.getConnectionInfo();
        int i = wi.getIpAddress();
        InetAddress ip = null;
        byte[] rawAddress = new byte[]{(byte) (i), (byte) (i >> 8), (byte) (i >> 16), (byte) (i >> 24)};

        // TODO IPv6?
        rawAddress[3] = 1;
        //InetAddress hostIp = InetAddress.getByAddress(rawAddress);
        String hostIpS = (rawAddress[0] & 0xff) + "." + (rawAddress[1] & 0xff) + "." + (rawAddress[2] & 0xff) + "." + (rawAddress[3] & 0xff);

        return hostIpS;
    }

    public void informConnectionResult(int returnCode, String requested, String message) {
        if (returnCode == 200) {
            if (requested.equals("status")) {
                final String toastText = getResources().getString(R.string.connection_successful) + " " + message;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, toastText, Toast.LENGTH_LONG).show();
                        connectionStrength.setQuality(80);
                    }
                });
            }
        } else {
            //final String toastText = getResources().getString(R.string.connection_failed) + " " + returnCode + " " + message;

            final String errorText = getResources().getString(R.string.connection_failed) + " " + returnCode + " " + message;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //Toast.makeText(MainActivity.this, toastText, Toast.LENGTH_LONG).show();
                    //connectionStrength.setQuality(50);

                    Log.e(TAG, errorText);

                    if (toggleConnection.isChecked()) {
                        toggleConnection.setChecked(false);
                    }
                }
            });
        }
    }

    @Override
    public void onPositionChange(Direction newDirection) {
        Log.i(TAG, "Got position change "+newDirection);

        String remoteIp = determineRatIp();
        if (remoteIp != null) {
            try {
                // TODO only send new commands when old are acknowledged?

                // TODO both directions
                if (roverConnection != null && roverConnection.isConnected()) {
                    // NOTE for final 0,0 this requests "forward 0" which resets both engines

                    if (Math.abs(newDirection.forward) >= Math.abs(newDirection.right)) {
                        int value = Math.abs(Math.round(newDirection.forward * 1000));
                        if (newDirection.forward >= 0) {
                            roverConnection.sendControl("fore "+value);
                        } else {
                            roverConnection.sendControl("back "+value);
                        }
                    } else {
                        int value = Math.abs(Math.round(newDirection.right * 1000));
                        if (newDirection.right >= 0) {
                            roverConnection.sendControl("right "+value);
                        } else {
                            roverConnection.sendControl("left "+value);
                        }
                    }
                }
                // TODO else disable joystick ui?

                /* Old style
                // NOTE for final 0,0 this requests "forward 0" which resets both engines
                RoverConnection rc = new RoverConnection(remoteIp, this);

                if (Math.abs(newDirection.forward) >= Math.abs(newDirection.right)) {
                    int value = Math.abs(Math.round(newDirection.forward * 1000));
                    if (newDirection.forward >= 0) {
                        rc.makeRequest("fore"+value);
                    } else {
                        rc.makeRequest("back"+value);
                    }
                } else {
                    int value = Math.abs(Math.round(newDirection.right * 1000));
                    if (newDirection.right >= 0) {
                        rc.makeRequest("right"+value);
                    } else {
                        rc.makeRequest("left"+value);
                    }
                }*/
            } catch (Exception exc) {
                // TODO do more
                Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
            }
        }
    }
}
