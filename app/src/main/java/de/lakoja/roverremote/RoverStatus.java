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

public class RoverStatus {
    private boolean led1On = false;
    private boolean led2On = false;
    private boolean irLedOn = false;
    private float voltage = 4.3f;

    public RoverStatus(boolean led1On, boolean led2On, boolean irLedOn, float voltage) {
        this.led1On = led1On;
        this.led2On = led2On;
        this.irLedOn = irLedOn;
        this.voltage = voltage;
    }

    public boolean isLed1On() {
        return led1On;
    }

    public boolean isLed2On() {
        return led2On;
    }

    public boolean isIrLedOn() {
        return irLedOn;
    }

    public float getVoltage() {
        return voltage;
    }
}
