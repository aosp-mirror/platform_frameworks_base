/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.os;

import java.util.Arrays;
import java.util.HashMap;

public class LongArrayContainer_host {
    private static final HashMap<Long, long[]> sInstances = new HashMap<>();
    private static long sNextId = 1;

    public static long native_init(int arrayLength) {
        long[] array = new long[arrayLength];
        long instanceId = sNextId++;
        sInstances.put(instanceId, array);
        return instanceId;
    }

    static long[] getInstance(long instanceId) {
        return sInstances.get(instanceId);
    }

    public static void native_setValues(long instanceId, long[] values) {
        System.arraycopy(values, 0, getInstance(instanceId), 0, values.length);
    }

    public static void native_getValues(long instanceId, long[] values) {
        System.arraycopy(getInstance(instanceId), 0, values, 0, values.length);
    }

    public static boolean native_combineValues(long instanceId, long[] array, int[] indexMap) {
        long[] values = getInstance(instanceId);

        boolean nonZero = false;
        Arrays.fill(array, 0);

        for (int i = 0; i < values.length; i++) {
            int index = indexMap[i];
            if (index < 0 || index >= array.length) {
                throw new IndexOutOfBoundsException("Index " + index + " is out of bounds: [0, "
                        + (array.length - 1) + "]");
            }
            if (values[i] != 0) {
                array[index] += values[i];
                nonZero = true;
            }
        }
        return nonZero;
    }
}
