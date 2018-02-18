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
import java.net.Socket;
import java.net.SocketException;

public class ImageConnection  implements Runnable {

    public interface ImageListener {
        void informConnectionStatus(int returnCode, String requested, String message);
        void imagePresent(Bitmap bitmap, long timestampMillis);
    }

    private static final String TAG = ImageConnection.class.getName();

    private final String host;
    private int port;
    private boolean active = false;
    private Socket serverConnection;
    private ImageListener imageListener;

    public ImageConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setImageListener(ImageListener imageListener) {
        this.imageListener = imageListener;
    }

    @Override
    public void run() {
        try {
            InetAddress serverAddr = InetAddress.getByName(host);
            serverConnection = new Socket(serverAddr, port);

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(serverConnection.getOutputStream()), true);

            writer.println("GET / HTTP/1.1");

            DataInputStream stream = new DataInputStream(new BufferedInputStream(serverConnection.getInputStream()));

            // Note also this reader buffers; so one cannot start to read image data out of the underlying input stream after reading lines.
            //InputStreamReader reader = new InputStreamReader(stream);

            String currentLine = readLine(stream);

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
                Log.e(TAG, "Did not get HTTP/1.1 response: "+currentLine);
                closeConnection(true);
                return;
            }

            if (code != 200) {
                Log.w(TAG, "Errorneous response code: "+code);
                closeConnection(true);
                return;
            }

            currentLine = readLine(stream);

            if (!currentLine.equals("Content-Type: multipart/x-mixed-replace; boundary=frame")) {
                Log.e(TAG, "Got wrong stream content type: "+currentLine);
                closeConnection(true);
                return;
            }

            String typeMarker = "Content-Type: ";
            String sizeMarker = "Content-Length: ";

            int illegalLines = 0;

            currentLine = readLine(stream);
            if (currentLine.length() != 0) {
                Log.e(TAG, "Expected empty separator line after first header; but got: "+currentLine);
                closeConnection(true);
                return;
            }

            while (active) {
                // TODO what about connection lost?
                currentLine = readLine(stream);

                if (currentLine.length() == 0) {
                    // Spacer line (after last image) is ok
                    // TODO check if this is really after image?
                    currentLine = readLine(stream);
                }

                while (!currentLine.equals("--frame")) {
                    Log.w(TAG, "Ignoring unrecognized line before --frame " + currentLine + "X"+currentLine.length());
                    if (illegalLines++ > 5) {
                        active = false;
                        Log.e(TAG, "Too much illegal content. Breaking up.");
                        closeConnection(true);
                        return;
                    }
                    currentLine = readLine(stream);
                }

                long millis = System.currentTimeMillis();

                currentLine = readLine(stream);
                int imageSize = 0;
                boolean ignoreContent = false;

                while (active && currentLine.length() > 0) {
                    if (currentLine.startsWith(typeMarker)) {
                        String type = currentLine.substring(typeMarker.length());
                        if (!type.equals("image/jpeg")) {
                            // TODO report on gui?
                            Log.w(TAG, "Ignoring invalid image data " + type);
                            ignoreContent = true;
                        }
                    } else if (currentLine.startsWith(sizeMarker)) {
                        String size = currentLine.substring(sizeMarker.length());
                        try {
                            imageSize = Integer.parseInt(size);
                        } catch (NumberFormatException exc) {
                            // TODO report on gui?
                            Log.e(TAG, "Illegal image size " + imageSize);
                            closeConnection(true);
                            return;
                        }
                    } else {
                        Log.i(TAG, "Ignoring unrecognized header " + currentLine);
                    }

                    currentLine = readLine(stream);
                }

                //Log.i(TAG, "Found headers in order; size: "+imageSize);

                // TODO even more active check (or handle errors differently)?
                if (active) {
                    if (ignoreContent) {
                        stream.skipBytes(imageSize);
                    } else {
                        if (imageSize <= 0) {
                            // TODO support without image size?
                            Log.e(TAG, "Image response has no size. Cancelling.");
                            closeConnection(true);
                            return;
                        }

                        byte[] imageData = new byte[imageSize];
                        int read = 0;
                        while (read < imageSize) {
                            read += stream.read(imageData, read, imageSize - read);
                        }
                        // TODO did try to use readFully()??

                        //Log.i(TAG, "Read image bytes "+read);

                        Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

                        if (bmp == null) {
                            Log.e(TAG, "Found illegal image");

                            if (imageSize >= 5) {
                                Log.e(TAG, "first 5 bytes " + String.format("%x", imageData[0]) + String.format("%x", imageData[1]) + String.format("%x", imageData[2]) + String.format("%x", imageData[3]) + String.format("%x", imageData[4]));
                                Log.e(TAG, "last 5 bytes " + String.format("%x", imageData[imageSize - 5]) + String.format("%x", imageData[imageSize - 4]) + String.format("%x", imageData[imageSize - 3]) + String.format("%x", imageData[imageSize - 2]) + String.format("%x", imageData[imageSize - 1]));
                            }

                            // TODO this error might be ignored?
                            closeConnection(true);
                            return;
                        } else {
                            if (imageListener != null) {
                                // TODO timestamp?
                                imageListener.imagePresent(bmp, 0);
                            }
                        }

                        long millis2 = System.currentTimeMillis();
                        //Log.i(TAG, "Processing image took "+(millis2 - millis));
                    }
                }

                try { Thread.sleep(2); } catch (InterruptedException exc) {} // give control some air
            }

            if (serverConnection != null) {
                active = false;
                Log.w(TAG, "Disconnecting");
                serverConnection.close();
            }

        } catch (SocketException exc2) {
            String message = "No image. Connection closed. "+ exc2.getMessage();
            Log.w(TAG, message);
        } catch (Exception exc) {
            String message = "No image connection " + exc.getMessage() + "/" + exc.getClass();
            Log.e(TAG, message);
        }
    }

    /**
     * Does it without the need for buffering (the underlying input stream should thus be buffered).
     */
    private String readLine(InputStream stream) throws IOException {
        StringBuilder stringBuffer = new StringBuilder(100);
        char c;
        while ((c = (char)stream.read()) != '\n') {
            if (c != '\r') {
                stringBuffer.append(c);

                if (stringBuffer.length() > 100000) {
                    Log.wtf(TAG, "Emergency stopping readLine. Over 100000 chars until \\n");
                    return "";
                }
            }
        }

        String s = stringBuffer.toString();
        //Log.i(TAG, "Passing line "+s);

        return stringBuffer.toString();
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

        // TODO do this here or in run?
        if (serverConnection != null) {
            try {
                serverConnection.close();
            } catch (IOException e) {
                // Only cleanup anyway
            }
            serverConnection = null;
        }

        if (internalError && imageListener != null) {
            imageListener.informConnectionStatus(500, "", "Image connection closed");
        }
    }


}
