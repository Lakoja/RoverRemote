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
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

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
    private static final int COLOR_ORANGE = 0xffff7f00;

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
    private boolean checkSystemLoop = true;
    private ImageConnection imageConnection;
    private byte[] lastImageData = null;
    private long lastImageMillis = 0;
    private int lastImageBackColor;
    private long lastStatusCheck = 0;
    private boolean wifiNameMatches = false;

    private MyVibrator vibrator;

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

            if (savedInstanceState != null) {
                // TODO does not work in onSaveInstanceState
            }

            restoreLastImage();

            vibrator = new MyVibrator(this);

            /* TODO make this check work
            try {
                int i = Settings.System.getInt(getApplicationContext().getContentResolver(), Settings.System.VIBRATE_ON);
                Log.i(TAG, "Vibrate is "+i);
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }*/
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

    private void restoreLastImage() {
        try {
            File lastImageFile = new File(getApplicationContext().getFilesDir(), "lastImage.jpg");
            if (lastImageFile.exists()) {
                FileInputStream fis = getApplicationContext().openFileInput("lastImage.jpg");
                DataInputStream input = new DataInputStream(fis);

                byte[] lastImageDataFromFile = new byte[(int) lastImageFile.length()];
                input.readFully(lastImageDataFromFile);

                input.close();

                Bitmap bmp = BitmapFactory.decodeByteArray(lastImageDataFromFile, 0, lastImageDataFromFile.length);
                if (bmp != null) {
                    setImageBackColor(Color.RED); // TODO set different otherwise
                    imageView.setImageBitmap(bmp);
                } else {
                    Log.e(TAG, "Found corrupt image in saved file; byte size " + lastImageDataFromFile.length);
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // TODO better error output?
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkSystemLoop = true;
        new Thread(this).start();
    }

    @Override
    protected void onPause() {
        super.onPause();

        checkSystemLoop = false;

        saveLastImage();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // NOTE only called for system events

        super.onSaveInstanceState(outState);

        // TODO does not work
    }

    private void saveLastImage() {
        if (lastImageData != null) {
            try {
                FileOutputStream fos = getApplicationContext().openFileOutput("lastImage.jpg", 0);
                fos.write(lastImageData);
                fos.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        closeConnections();

        // TODO also use a timer for connection close

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
                        Log.i(TAG, "Opening connections to "+remoteIp);

                        roverConnection = new RoverConnection(remoteIp);
                        roverConnection.setStatusListener(this);
                        roverConnection.openControlConnection();

                        roverConnection.sendControl("status");

                        imageConnection = new ImageConnection(remoteIp, 81);
                        imageConnection.setImageListener(this);
                        imageConnection.openConnection();
                    } catch (Exception exc) {
                        // TODO do more
                        Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
                        String toastText = getResources().getString(R.string.connection_failed) + " " + remoteIp;
                        Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Log.e(TAG, "Cannot determine remote ip");
                    toggleConnection.setChecked(false);
                }

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
            // TODO improve
            ConnectivityManager cm = (ConnectivityManager)getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                Log.w(TAG, "No ip. Network status is "+ni.isConnected());
            }

            return null; // TODO is this normal?
        }

        byte[] rawAddress = new byte[]{(byte) (i), (byte) (i >> 8), (byte) (i >> 16), (byte) (i >> 24)};

        // TODO IPv6?
        rawAddress[3] = 1;
        return (rawAddress[0] & 0xff) + "." + (rawAddress[1] & 0xff) + "." + (rawAddress[2] & 0xff) + "." + (rawAddress[3] & 0xff);
    }

    // TODO this is specified by two interfaces; that is rather odd
    public void informConnectionStatus(int returnCode, final String requested, String message) {
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
            Log.e(TAG, errorText);

            // TODO allow missing image connection better
            if (requested == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, errorText, Toast.LENGTH_LONG).show();
                        if (!requested.equals("image") && toggleConnection.isChecked()) {
                            toggleConnection.setChecked(false);
                        }
                    }
                });
            }
        }
    }

    @Override
    public void informRoverStatus(final RoverStatus currentStatus) {
        if (currentStatus.getVoltage() > 0) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.positionControl.showVolt(currentStatus.getVoltage());
                }
            });
        }
    }

    @Override
    public void onPositionChange(Direction newDirection) {
        try {
            // TODO only send new commands when old are acknowledged?

            if (roverConnection != null && roverConnection.isConnected()) {
                String command = "move ";
                command += (500 + Math.round(bend(newDirection.forward) * 500));
                command += " ";
                command += (500 + Math.round(bend(newDirection.right) * 500));

                roverConnection.sendControl(command);
            }
        } catch (Exception exc) {
            // TODO do more
            Log.e(TAG, "" + exc.getMessage() + "/" + exc.getClass());
        }
    }

    /**
     * Slightly curve the value: lower values raise slower; maps -1..1 to -1..1
     * Found/tested on https://rechneronline.de/funktionsgraphen/
     */
    private float bend(float x) {
        return Math.signum(x) * (float)Math.pow(Math.abs(x), 1.5f);
    }

    @Override
    public void run() {
        while (checkSystemLoop) {
            // Check for wifi quality constantly
            // TODO stop on pause?

            final WifiInfo info = wifiManager.getConnectionInfo();

            if (!wifiNameMatches(info)) {
                wifiNameMatches = false;

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
                            if (connectionThroughput.getQuality() != 0) {
                                connectionThroughput.setQuality(0);
                            }
                            Log.e(TAG, "Wifi has wrong name " + info.getSSID());
                            Toast.makeText(MainActivity.this, R.string.no_connection_wrong_name, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } else {

                // TODO use handler?
                /* long[] pattern = new long[]{
                    0,
                    dot, gap, dash, gap, dot, gap, dot
                };
                final Handler handler = new Handler();
                handler.postDelayer(new Runnable(){
                    @Override
                    public void run(){
                        vibrator.vibrate();
                        if(!endVibration){
                            handler.postDelayed(this, timeToRun);
                        }
                    }
                }, timeToRun);
                */
                // TODO only do something on larger change?

                // TODO signal warning if quality worsens

                if (!wifiNameMatches) {
                    vibrator.vibratePattern(200, 100, 200);
                }

                wifiNameMatches = true;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int rssi = info.getRssi();
                        int signalLevel = WifiManager.calculateSignalLevel(rssi, 100);
                        connectionStrength.setQuality(signalLevel);
                        // TODO also show rssi as value
                    }
                });
            }

            if (lastImageMillis > 0) {
                int desiredColor = lastImageBackColor;

                if (System.currentTimeMillis() - lastImageMillis > 5000) {
                    desiredColor = COLOR_ORANGE;
                } else if (System.currentTimeMillis() - lastImageMillis > 1500) {
                    desiredColor = Color.YELLOW;
                }
                if (lastImageBackColor != desiredColor) {
                    final int colorToSet = desiredColor;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setImageBackColor(colorToSet);
                        }
                    });
                }
            }

            if (roverConnection != null && roverConnection.isConnected()) {
                long now = System.currentTimeMillis();
                if (now - lastStatusCheck > 1900) {
                    roverConnection.sendControl("status");
                    // TODO the actual result may be delayed / request discarded?
                    lastStatusCheck = now;
                }
            }

            try { Thread.sleep(1000); } catch (InterruptedException exc) {}
        }
    }

    @Override
    public void imagePresent(final Bitmap bitmap, final long timestampMillis, final byte[] rawData, final float lastKbps) {
        // TODO use handler? Is there any synchronisation for multiple of these Runnables?
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setImageBackColor(Color.GREEN);
                imageView.setImageBitmap(bitmap);
                MainActivity.this.lastImageData = rawData;
                MainActivity.this.lastImageMillis = timestampMillis;

                // 1MB/s is maximum shown throughput
                // Formula found experimentally: uses a moderatly logarithmic curve mapping 0..1000 to 0..100
                //   See https://rechneronline.de/funktionsgraphen/
                float qualityValue = 49 * (float)Math.log((lastKbps + 150) / 150);
                connectionThroughput.setQuality(qualityValue);
            }
        });
    }

    // TODO stop image display/request once paused?

    // TODO this should only set a border (if image does not fit view correctly this background border will be big)
    private void setImageBackColor(int color) {
        if (lastImageBackColor != color) {
            lastImageBackColor = color;
            imageView.setBackgroundColor(lastImageBackColor); // TODO  what about repaint performance?
        }
    }
}
