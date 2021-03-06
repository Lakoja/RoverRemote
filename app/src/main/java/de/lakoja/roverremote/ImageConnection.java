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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.StringTokenizer;

public class ImageConnection  implements Runnable {

    private static final long ENTRY_TOO_OLD = 300;
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

    private static final String TAG = ImageConnection.class.getName();

    private final String host;
    private boolean active = false;
    private Socket serverConnection;
    private ImageListener imageListener;
    private StatusListener statusListener;
    private long lastImageTime;
    private Queue<Float> lastTransfersKbps = new LinkedList<>();
    private float lastTransferKbpsMean = 0;
    private long lastTransferOutTime = 0;
    private long lastImageRequestTime = 0;

    private Queue<ImageConnection.QueueEntry> commandQueue = new LinkedList<>();

    public ImageConnection(String host) {
        this.host = host;
        lastImageTime = System.currentTimeMillis();
    }

    public void setImageListener(ImageListener imageListener) {
        this.imageListener = imageListener;
    }

    public void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public boolean isConnected() {
        return serverConnection != null && serverConnection.isConnected();
    }

    public void sendControl(String controlRequest) {
        // TODO send confirmation to caller?
        commandQueue.add(new ImageConnection.QueueEntry(controlRequest));
    }

    @Override
    public void run() {
        try {
            InetAddress serverAddr = InetAddress.getByName(host);
            serverConnection = new Socket();
            serverConnection.setReceiveBufferSize(4000); // TODO has this any influence? (on responsiveness?)
            try {
                serverConnection.connect(new InetSocketAddress(serverAddr, 80));
            } catch (SocketException excS) {
                if (excS.getMessage().contains("ETIMEDOUT") || excS.getMessage().contains("ECONNRESET")) {
                    Log.w(TAG, "Try image connection once again");
                    // Try once again
                    try { Thread.sleep(500); } catch (InterruptedException exc) {}
                    serverConnection.connect(new InetSocketAddress(serverAddr, 80));
                } else {
                    Log.w(TAG, "First image connection attempt failed");
                    throw excS;
                }
            }

            Log.i(TAG, "Established image connection");

            // TODO could use setSoTimeout (InterruptedException and continue normally after)

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(serverConnection.getOutputStream()), true);
            InputStream input = serverConnection.getInputStream();

            Log.i(TAG, "Established image streams");

            // TODO remove / probably not necessary
            long staleDataSkipped = 0;
            while (input.available() > 0) {
                int av = input.available();
                staleDataSkipped += input.skip(av);
            }

            if (staleDataSkipped > 0) {
                Log.e(TAG, "Skipped stale data: "+staleDataSkipped);
            }

            DataInputStream stream = new DataInputStream(new BufferedInputStream(input));

            while (active) {

                // TODO simplify / beautify control handling vs image handling

                // TODO slot-in image request at least from time to time

                boolean sentCommand = false;
                if (!commandQueue.isEmpty()) {
                    ImageConnection.QueueEntry command = commandQueue.remove();
                    if (entryAlive(command)) {
                        sentCommand = true;

                        long m1 = System.currentTimeMillis();
                        writer.println("GET /"+command.controlRequest);
                        writer.flush();
                        long m2 = System.currentTimeMillis();
                        String result = readLine(stream);
                        if (result.length() == 0) {
                            // TODO understand and remove
                            result = readLine(stream);
                        }
                        long m3 = System.currentTimeMillis();
                        // TODO also read everything there is?
                        if (m3 - m2 > 100) {
                            Log.i(TAG, "Control " + command.controlRequest + " resulted in " + result + " took w" + (m2 - m1) + " r" + (m3 - m2));
                        }

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
                }

                long nowBeforeImageRequest = System.currentTimeMillis();
                if (!sentCommand && nowBeforeImageRequest - lastImageRequestTime >= 100) {
                    writer.println("GET / HTTP/1.1");
                    writer.flush();
                    lastImageRequestTime = nowBeforeImageRequest;

                    //Log.i(TAG, "Sent image request");

                    // Note also this reader buffers; so one cannot start to read image data out of the underlying input stream after reading lines.
                    //InputStreamReader reader = new InputStreamReader(stream);

                    String currentLine = readLine(stream);

                    // TODO remove / probably not necessary
                    int garbageDataSkipped = 0;
                    while (!currentLine.startsWith("HTTP/1.1 ")) {
                        garbageDataSkipped += currentLine.length();

                        if (garbageDataSkipped > 100000) {
                            Log.wtf(TAG, "Cannot skip any more garbage data");
                            break;
                        }

                        currentLine = readLine(stream);
                    }

                    if (garbageDataSkipped > 0) {
                        Log.e(TAG, "Skipped garbage data: " + garbageDataSkipped);
                    }

                    int code = 0;
                    if (currentLine.startsWith("HTTP/1.1 ")) {
                        String responseCode = currentLine.substring(9);
                        String[] responseCodeParts = responseCode.split(" ");
                        try {
                            code = Integer.parseInt(responseCodeParts[0]);
                        } catch (NumberFormatException exc) {
                            // TODO report on gui? All errors?
                            Log.e(TAG, "Illegal response code: " + responseCode);
                            closeConnection(true);
                            return;
                        }
                    } else {
                        Log.e(TAG, "Did not get HTTP/1.1 response: " + (int) currentLine.charAt(0) + " " + (int) currentLine.charAt(1) + " " + currentLine);
                        closeConnection(true);
                        return;
                    }

                    if (code != 200) {
                        Log.w(TAG, "Errorneous response code: " + code);
                        closeConnection(true);
                        return;
                    }

                    //Log.i(TAG, "Got HTTP response");

                    currentLine = readLine(stream);

                    if (currentLine.equals("NOIY")) {
                        // No new image yet; the time guard above (lastImageRequestTime) makes sure the
                        // server is not flooded with requests

                        continue;
                    }

                    if (!currentLine.equals("Content-Type: image/jpeg")) {
                        Log.e(TAG, "Got wrong stream content type: " + currentLine);
                        closeConnection(true);
                        return;
                    }

                    currentLine = readLine(stream);

                    String sizeMarker = "Content-Length: ";
                    if (!currentLine.startsWith(sizeMarker)) {
                        Log.e(TAG, "Got wrong stream content length: " + currentLine);
                        closeConnection(true);
                        return;
                    }

                    String size = currentLine.substring(sizeMarker.length());
                    int imageSize = 0;
                    try {
                        imageSize = Integer.parseInt(size);
                    } catch (NumberFormatException exc) {
                        // TODO report on gui?
                        Log.e(TAG, "Illegal image size " + imageSize);
                        closeConnection(true);
                        return;
                    }

                    //Log.i(TAG, "Reading image with size "+imageSize);

                    currentLine = readLine(stream);

                    if (currentLine.length() != 0) {
                        Log.e(TAG, "Expected empty separator line after header; but got: " + currentLine);
                        closeConnection(true);
                        return;
                    }

                    long imageStartTime = System.currentTimeMillis();

                    // TODO even more active check (or handle errors differently)?

                    if (imageSize <= 0) {
                        // TODO support without image size?
                        Log.e(TAG, "Image response has no size. Cancelling.");
                        closeConnection(true);
                        return;
                    }

                    byte[] imageData = new byte[imageSize];
                    long m1 = System.currentTimeMillis();

                    stream.readFully(imageData);

                    long m2 = System.currentTimeMillis();
                    //logLongWait(m2-m1, "image");

                    float kbps = (imageSize / 1024.0f) / ((m2 - m1) / 1000.0f);

                    // TODO this dequeue and enqueue with mean is rather awkward
                    if (lastTransfersKbps.size() == 0) {
                        lastTransferKbpsMean = kbps;
                    } else if (lastTransfersKbps.size() > 2) {
                        // dequeue oldest one
                        float oldestKbps = lastTransfersKbps.remove();
                        lastTransferKbpsMean = ((lastTransferKbpsMean * (lastTransfersKbps.size() + 1)) - oldestKbps) / lastTransfersKbps.size();
                    }

                    lastTransferKbpsMean = (lastTransferKbpsMean * lastTransfersKbps.size() + kbps) / (lastTransfersKbps.size() + 1);
                    lastTransfersKbps.add(kbps);

                    //Log.i(TAG, "Having kbps "+lastTransferKbpsMean);

                /*
                int read = 0;
                while (read < imageSize) {
                    long m1 = System.currentTimeMillis();
                    read += stream.read(imageData, read, imageSize - read);
                    long m2 = System.currentTimeMillis();
                    logLongWait(m2-m1, "image");
                }*/

                    // TODO remove?
                    // writer.println("ok");

                    Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

                    if (bmp == null) {
                        Log.e(TAG, "Found illegal image");

                        // TODO must be shown more prominently
                        if (statusListener != null) {
                            statusListener.informConnectionStatus(500, "image", "illegal image found");
                        }

                        if (imageSize >= 5) {
                            Log.e(TAG, "first 5 bytes " + String.format("%x", imageData[0]) + String.format("%x", imageData[1]) + String.format("%x", imageData[2]) + String.format("%x", imageData[3]) + String.format("%x", imageData[4]));
                            Log.e(TAG, "last 5 bytes " + String.format("%x", imageData[imageSize - 5]) + String.format("%x", imageData[imageSize - 4]) + String.format("%x", imageData[imageSize - 3]) + String.format("%x", imageData[imageSize - 2]) + String.format("%x", imageData[imageSize - 1]));
                        }

                        // TODO this error might be ignored?
                        closeConnection(true);
                        return;
                    } else {
                        //Log.i(TAG, "Found image "+bmp.getWidth());

                        if (imageListener != null) {
                            imageListener.imagePresent(bmp, imageStartTime, imageData, lastTransferKbpsMean);
                        }
                    }


                    long nowAfterImageReceive = System.currentTimeMillis();
                    long passed = nowAfterImageReceive - imageStartTime;
                    if (passed > 500 || nowAfterImageReceive - lastTransferOutTime > 2000) {
                        Log.i(TAG, "Processing image took " + passed + " (last image " + (nowAfterImageReceive - lastImageTime) + " ago)");
                        lastTransferOutTime = nowAfterImageReceive;
                    }
                    lastImageTime = nowAfterImageReceive;
                }

                // This sleeps (longer) in waiting for input above - if the servers wishes so or the connection is bad
                try { Thread.sleep(1); } catch (InterruptedException exc) {}
            }

            if (serverConnection != null) {
                active = false;
                Log.w(TAG, "Disconnecting");
                serverConnection.close();
                serverConnection = null;
            }

        } catch (Exception exc) {
            if (!active && exc instanceof SocketException) {
                // ignore connection close
            } else {
                String message = "No image connection " + exc.getMessage() + "/" + exc.getClass();
                Log.e(TAG, message);

                if (statusListener != null) {
                    statusListener.informConnectionStatus(500, "image", message);
                }
            }
        }
    }

    private boolean entryAlive(ImageConnection.QueueEntry entry) {
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

    /**
     * Does it without the need for buffering (the underlying input stream should thus be buffered).
     */
    private String readLine(InputStream stream) throws IOException {
        StringBuilder stringBuffer = new StringBuilder(100);

        // TODO only read when available() (not sending is normal for the server)

        long time1 = System.currentTimeMillis();
        boolean timeError = false;
        while (stream.available() <= 0) {
            if (System.currentTimeMillis() - time1 > 5000 && !timeError) {
                Log.e(TAG, "Waited too long for line data");

                timeError = true;
            }

            try { Thread.sleep(2); } catch (InterruptedException exc) {}
        }

        char c = (char)stream.read();
        while (c != '\n') {
            if (c != '\r') {
                stringBuffer.append(c);

                if (stringBuffer.length() % 100 == 0) {
                    if (!serverConnection.isConnected()) {
                        Log.e(TAG, "Emergency stopping readLine. Not connected anymore.");
                        return "";
                    }
                }

                if (stringBuffer.length() > 100000) {
                    Log.wtf(TAG, "Emergency stopping readLine. Over 100000 chars until \\n");
                    return "";
                }
            }

            c = (char)stream.read();
        }

        return stringBuffer.toString();
    }

    private void logLongWait(long waitMillis, String pos) {
        if (waitMillis > 100) {
            Log.w(TAG, "Long wait for "+pos+" "+waitMillis);
        }
    }

    public void openConnection() {
        if (!active || serverConnection == null) {
            active = true;
            new Thread(this).start();
        }
    }

    public void closeConnection() {
        closeConnection(false);
    }

    public void closeConnection(boolean internalError) {
        active = false;

        if (internalError && statusListener != null) {
            statusListener.informConnectionStatus(500, "", "Image connection closed");
        }
    }
}
