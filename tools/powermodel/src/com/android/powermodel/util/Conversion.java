/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel.util;

public class Conversion {

    /**
     * Convert the the float[] to an int[].
     * <p>
     * Values are rounded to the nearest integral value. Null input
     * results in null output.
     */
    public static int[] toIntArray(float[] value) {
        if (value == null) {
            return null;
        }
        int[] result = new int[value.length];
        for (int i=0; i<result.length; i++) {
            result[i] = (int)(value[i] + 0.5f);
        }
        return result;
    }
    
    public static double msToHr(double ms) {
        return ms / 3600.0 / 1000.0;
    }

    /**
     * No public constructor.
     */
    private Conversion() {
    }
}
