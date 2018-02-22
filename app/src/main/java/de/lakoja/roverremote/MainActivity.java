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
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.net.InetAddress;

public class MainActivity
        extends AppCompatActivity
        implements
            Runnable,
            CompoundButton.OnCheckedChangeListener,
            JoystickView.PositionChangeListener,
            RoverConnection.StatusListener,
            ImageConnection.ImageListener {

    private static final String TAG = MainActivity.class.getName();

    private static final String DESIRED_WIFI_NAME = "Roversnail";

    private ToggleButton toggleConnection;
    private ToggleButton toggleLed2;
    private ToggleButton toggleInfraLed;
    private Button btnWifi;
    private QualityView connectionStrength;
    private QualityView connectionThroughput;
    private JoystickView positionControl;
    private ImageView imageView;

    private WifiManager wifiManager;
    private RoverConnection roverConnection;
    private boolean checkWifiActive = true;
    private ImageConnection imageConnection;

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

            // TODO scaling options?
            imageView = findViewById(R.id.imageView);

            new Thread(this).start();
        }
    }

    private void connectButtons() {
        toggleConnection = findViewById(R.id.toggleConnection);
        toggleConnection.setOnCheckedChangeListener(this);
        //toggleLed1 = findViewById(R.id.toggleLed1);
        //toggleLed1.setOnCheckedChangeListener(this);
        //toggleLed1.setEnabled(false);
        toggleLed2 = findViewById(R.id.toggleLed2);
        toggleLed2.setOnCheckedChangeListener(this);
        toggleLed2.setEnabled(false);
        toggleInfraLed = findViewById(R.id.toggleInfra);
        toggleInfraLed.setOnCheckedChangeListener(this);
        toggleInfraLed.setEnabled(false);
        btnWifi = findViewById(R.id.btnWifi);
        btnWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
    }

    @Override
    protected void onDestroy() {
        closeConnections();

        checkWifiActive = false;

        super.onDestroy();
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isCheckedNow) {
        if (compoundButton == toggleConnection) {
            WifiInfo wi = wifiManager.getConnectionInfo();
            if (!wifiNameMatches(wi)) {
                Log.e(TAG, "Wifi has wrong name "+wi.getSSID());
                Toast.makeText(this, R.string.no_connection_wrong_name, Toast.LENGTH_LONG).show();
                toggleConnection.setChecked(false);
                return;
            }

            //toggleLed1.setEnabled(isCheckedNow);
            toggleLed2.setEnabled(isCheckedNow);
            toggleInfraLed.setEnabled(isCheckedNow);

            if (isCheckedNow) {
                String remoteIp = determineRatIp();
                if (remoteIp != null) {
                    try {
                        roverConnection = new RoverConnection(remoteIp);
                        roverConnection.setStatusListener(this);
                        roverConnection.openControlConnection();

                        imageConnection = new ImageConnection(remoteIp, 81);
                        imageConnection.setImageListener(this);
                        imageConnection.openConnection();
                    } catch (Exception exc) {
                        // TODO do more
                        Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
                        String toastText = getResources().getString(R.string.connection_failed) + " " + remoteIp;
                        Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
                    }
                }
                // TODO else?
            } else {
                closeConnections();
                Toast.makeText(this, R.string.disconnection_toast, Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean wifiNameMatches(WifiInfo info) {
        // Also removes
        return info.getSSID().replaceAll("^\"|\"$", "").equals(DESIRED_WIFI_NAME);
    }

    private void closeConnections() {
        if (roverConnection != null) {
            roverConnection.closeControlConnection();
            roverConnection = null;
        }
        if (imageConnection != null) {
            imageConnection.closeConnection();
            imageConnection = null;
        }
    }

    private String determineRatIp() {
        WifiInfo wi = wifiManager.getConnectionInfo();
        int i = wi.getIpAddress();

        if (i == 0) {
            return null; // TODO is this normal?
        }

        InetAddress ip = null;
        byte[] rawAddress = new byte[]{(byte) (i), (byte) (i >> 8), (byte) (i >> 16), (byte) (i >> 24)};

        // TODO IPv6?
        rawAddress[3] = 1;
        //InetAddress hostIp = InetAddress.getByAddress(rawAddress);
        String hostIpS = (rawAddress[0] & 0xff) + "." + (rawAddress[1] & 0xff) + "." + (rawAddress[2] & 0xff) + "." + (rawAddress[3] & 0xff);

        return hostIpS;
    }

    // TODO this is specified by two interfaces; that is rather odd
    public void informConnectionStatus(int returnCode, String requested, String message) {
        if (returnCode == 200) {
            if (requested.equals("status")) {
                final String toastText = getResources().getString(R.string.connection_successful) + " " + message;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, toastText, Toast.LENGTH_LONG).show();
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
    public void informRoverStatus(RoverStatus currentStatus) {

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

                    String command = "";

                    if (Math.abs(newDirection.forward) >= Math.abs(newDirection.right)) {
                        int value = Math.abs(Math.round(newDirection.forward * 1000));
                        if (newDirection.forward >= 0) {
                            command = "fore "+value;
                        } else {
                            command = "back "+value;
                        }
                    } else {
                        int value = Math.abs(Math.round(newDirection.right * 1000));
                        if (newDirection.right >= 0) {
                            command = "right "+value;
                        } else {
                            command = "left "+value;
                        }
                    }

                    if (command.length() > 0) {
                        Log.i(TAG, "Sending control command "+command);
                        roverConnection.sendControl(command);
                    }
                }
                // TODO else disable joystick ui?
            } catch (Exception exc) {
                // TODO do more
                Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
            }
        }
    }

    /* NO works
    @Override
    protected void onResume() {
        super.onResume();

        Vibrator v = (Vibrator) getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(1000);
        Log.i(TAG, "Vibrating");
    }*/

    @Override
    public void run() {
        // Check for wifi quality constantly
        while (checkWifiActive) {
            // TODO stop on pause?

            final WifiInfo info = wifiManager.getConnectionInfo();

            if (!wifiNameMatches(info)) {
                // TODO could/should also track with some ID?
                //closeConnections();
                if (toggleConnection.isChecked() || connectionStrength.getQuality() != 0) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (toggleConnection.isChecked()) {
                                toggleConnection.setChecked(false);
                            }
                            if (connectionStrength.getQuality() != 0) {
                                connectionStrength.setQuality(0);
                            }
                            Log.e(TAG, "Wifi has wrong name " + info.getSSID());
                            Toast.makeText(MainActivity.this, R.string.no_connection_wrong_name, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } else {

                // TODO use handler?
                // TODO only do something on larger change?

                // TODO signal warning if quality worsens

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int rssi = info.getRssi();
                        int signalLevel = WifiManager.calculateSignalLevel(rssi, 100);
                        connectionStrength.setQuality(signalLevel);
                        // TODO also show rssi as value
                        //Log.i(TAG, "Signal level = "+signalLevel+" from "+rssi);
                    }
                });
            }

            try { Thread.sleep(1000); } catch (InterruptedException exc) {}
        }
    }

    @Override
    public void imagePresent(final Bitmap bitmap, long timestampMillis) {
        // TODO use handler? Is there any synchronisation for multiple of these Runnables?

        //Log.i(TAG, "Got image "+bitmap.getWidth()+"x"+bitmap.getHeight());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                imageView.setImageBitmap(bitmap);
            }
        });
    }
}
