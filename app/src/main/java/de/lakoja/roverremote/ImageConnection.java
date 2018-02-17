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
import android.media.Image;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class ImageConnection  implements Runnable {

    public interface ImageListener {
        void imagePresent(Bitmap bitmap, long timestampMillis);
    }

    private static final String TAG = ImageConnection.class.getName();

    private final String host;
    private int port;
    private boolean active = false;
    private HttpURLConnection urlConnection;
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
            //InetAddress serverAddr = InetAddress.getByName(host);
            URL url = new URL("http", host, port, "");
            urlConnection = (HttpURLConnection) url.openConnection();

            //PrintWriter writer = new PrintWriter(
            //        new BufferedWriter(new OutputStreamWriter(urlConnection.getOutputStream())), true);

            if (200 != urlConnection.getResponseCode()) {
                Log.e(TAG, "Illegal image response code "+ urlConnection.getResponseCode());
                active = false;
                return;
            }

            int contentLength = urlConnection.getContentLength();

            Log.i(TAG, "Got content length "+contentLength);
            Log.i(TAG, "Content type "+urlConnection.getContentType());

            Map<String, List<String>> headerFields = urlConnection.getHeaderFields();
            for(Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                String key = entry.getKey();
                List<String> value = entry.getValue();
                Log.i(TAG, "Header field " + key + ": " + value);
            }

            DataInputStream stream = new DataInputStream(new BufferedInputStream(urlConnection.getInputStream()));
            InputStreamReader reader = new InputStreamReader(stream);

            //writer.println("control");

            //String typeMarker = "Content-Type: ";
            //String sizeMarker = "Content-Length: ";

            int illegalLines = 0;

            while (active) {
                // TODO what about connection lost?

                /*
                String currentLine = readLine(reader);
                while (!currentLine.equals("--frame")) {
                    Log.w(TAG, "Ignoring unrecognized line before --frame " + currentLine + "X"+currentLine.length());
                    if (illegalLines++ > 5) {
                        active = false;
                        Log.e(TAG, "Too much illegal content. Breaking up.");
                        break;
                    }
                    currentLine = readLine(reader);
                }

                currentLine = readLine(reader);
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
                            Log.w(TAG, "Illegal image size " + imageSize);
                            ignoreContent = true;
                        }
                    } else {
                        Log.i(TAG, "Ignoring unrecognized header " + currentLine);
                    }

                    currentLine = readLine(reader);
                }
                */

                int imageSize = contentLength;
                boolean ignoreContent = false;

                // TODO even more active check (or handle errors differently)?
                if (active && !ignoreContent) {
                    if (imageSize <= 0) {
                        // TODO support without image size?
                        Log.e(TAG, "Image response has no size. Cancelling.");
                        active = false;
                    }

                    byte[] imageData = new byte[imageSize];
                    // TODO read in loop?
                    stream.readFully(imageData);

                    Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                    if (imageListener != null) {
                        // TODO timestamp?
                        imageListener.imagePresent(bmp, 0);
                    }

                    active = false;
                    String line = readLine(reader);
                    Log.w(TAG, "Next content "+line);
                    break;
                }

                Thread.yield();
            }

            if (urlConnection != null) {
                Log.w(TAG, "Disconnecting");
                urlConnection.disconnect();
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
    private String readLine(Reader reader) throws IOException {
        StringBuilder stringBuffer = new StringBuilder(100);
        char c;
        while ((c = (char)reader.read()) != '\n') {
            if (c != '\r') {
                stringBuffer.append(c);
            }
        }

        return stringBuffer.toString();
    }

    public void openConnection() {
        if (!active || urlConnection == null) {
            active = true;
            new Thread(this).start();
        }
    }

    public void closeConnection() {
        active = false;
        if (urlConnection != null) {
            urlConnection.disconnect();
            urlConnection = null;
        }
    }


}
