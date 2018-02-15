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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class RoverConnection implements Runnable {

    private static final String TAG = "RoverConnection";

    private String host;
    private String requestWord;
    private MainActivity parent;

    public RoverConnection(String host, MainActivity parent) {
        this.host = host;
        this.parent = parent;
    }

    @Override
    public void run() {
        try {
            HttpURLConnection conn = (HttpURLConnection) (new URL("http://" + host + "/" + requestWord).openConnection());
            conn.connect();
            int code = conn.getResponseCode();

            Log.i(TAG,"Network got result code "+code + " for " + requestWord);

            if (code == 200) {
                InputStreamReader streamReader = new InputStreamReader(conn.getInputStream());

                BufferedReader reader = new BufferedReader(streamReader);

                String result = reader.readLine();
                /*
                StringBuilder stringBuilder = new StringBuilder();
                while((inputLine = reader.readLine()) != null){
                    stringBuilder.append(inputLine);
                }*/

                reader.close();
                streamReader.close();

                //result = stringBuilder.toString();

                parent.informConnectionResult(code, requestWord, result);
            } else {
                parent.informConnectionResult(code, requestWord, "Failure");
            }

        } catch (Exception exc) {
            String message = ""+exc.getMessage()+"/"+exc.getClass();
            Log.e(TAG, message);
            parent.informConnectionResult(500, requestWord, message);
        }
    }

    public void makeRequest(String requestWord) {
        this.requestWord = requestWord;
        new Thread(this).start();
    }
}
