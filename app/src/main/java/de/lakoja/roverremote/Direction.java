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

public class Direction {
    public float forward = 0; // out of -1 to 1 - negative for reverse
    public float right = 0; // out of -1 to 1 - negative for left

    public Direction(float f, float r) {
        if (f < -1 || f > 1) {
            throw new IllegalArgumentException("forward may only be -1 to 1 is "+f);
        }

        if (r < -1 || r > 1) {
            throw new IllegalArgumentException("right may only be -1 to 1 is "+r);
        }

        this.forward = f;
        this.right = r;
    }

    public String toString() {
        return "dir "+forward+","+right;
    }
}
