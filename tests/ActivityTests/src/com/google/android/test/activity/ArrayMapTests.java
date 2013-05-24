/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.google.android.test.activity;

import android.util.ArrayMap;
import android.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class ArrayMapTests {
    static final int OP_ADD = 1;
    static final int OP_REM = 2;

    static int[] OPS = new int[] {
            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,

            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,

            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,

            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,

            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,
    };

    static int[] KEYS = new int[] {
            // General adding and removing.
             100,   1900,    600,    200,   1200,   1500,   1800,    100,   1900,
            2100,    300,    800,    600,   1100,   1300,   2000,   1000,   1400,
             600,    100,   1900,    600,    300,   2100,    200,    800,    800,
            1800,   1500,   1300,   1100,   2000,   1400,   1000,   1200,   1900,

            // Shrink when removing item from end.
             100,    200,    300,    400,    500,    600,    700,    800,    900,
             900,    800,    700,    600,    500,    400,    300,    200,    100,

            // Shrink when removing item from middle.
             100,    200,    300,    400,    500,    600,    700,    800,    900,
             900,    800,    700,    600,    500,    400,    200,    300,    100,

            // Shrink when removing item from front.
             100,    200,    300,    400,    500,    600,    700,    800,    900,
             900,    800,    700,    600,    500,    400,    100,    200,    300,

            // Test hash collisions.
             105,    106,    108,    104,    102,    102,    107,      5,    205,
               4,    202,    203,      3,      5,    101,    109,    200,    201,
             106,    108,    104,    102,    103,    105,    107,    101,    109,
               4,      5,      3,      5,    200,    203,    202,    201,    205,
    };

    static class ControlledHash {
        final int mValue;

        ControlledHash(int value) {
            mValue = value;
        }

        @Override
        public final boolean equals(Object o) {
            return mValue == ((ControlledHash)o).mValue;
        }

        @Override
        public final int hashCode() {
            return mValue/100;
        }

        @Override
        public final String toString() {
            return Integer.toString(mValue);
        }
    }

    private static boolean compare(Object v1, Object v2) {
        if (v1 == null) {
            return v2 == null;
        }
        if (v2 == null) {
            return false;
        }
        return v1.equals(v2);
    }

    private static boolean compareMaps(HashMap map, ArrayMap array) {
        if (map.size() != array.size()) {
            Log.e("test", "Bad size: expected " + map.size() + ", got " + array.size());
            return false;
        }

        Set<Map.Entry> mapSet = map.entrySet();
        for (Map.Entry entry : mapSet) {
            Object expValue = entry.getValue();
            Object gotValue = array.get(entry.getKey());
            if (!compare(expValue, gotValue)) {
                Log.e("test", "Bad value: expected " + expValue + ", got " + gotValue
                        + " at key " + entry.getKey());
                return false;
            }
        }

        for (int i=0; i<array.size(); i++) {
            Object gotValue = array.valueAt(i);
            Object key = array.keyAt(i);
            Object expValue = map.get(key);
            if (!compare(expValue, gotValue)) {
                Log.e("test", "Bad value: expected " + expValue + ", got " + gotValue
                        + " at key " + key);
                return false;
            }
        }

        int index = 0;
        for (Object entry : array.entrySet()) {
            Object key = ((Map.Entry)entry).getKey();
            Object value = ((Map.Entry)entry).getValue();
            Object realKey = array.keyAt(index);
            Object realValue = array.valueAt(index);
            if (!compare(key, realKey)) {
                Log.e("test", "Bad entry iterator: expected key " + realKey + ", got " + key
                        + " at index " + index);
                return false;
            }
            if (!compare(value, realValue)) {
                Log.e("test", "Bad entry iterator: expected value " + realValue + ", got " + value
                        + " at index " + index);
                return false;
            }
            index++;
        }

        index = 0;
        for (Object key : array.keySet()) {
            Object realKey = array.keyAt(index);
            if (!compare(key, realKey)) {
                Log.e("test", "Bad key iterator: expected key " + realKey + ", got " + key
                        + " at index " + index);
                return false;
            }
            index++;
        }

        index = 0;
        for (Object value : array.values()) {
            Object realValue = array.valueAt(index);
            if (!compare(value, realValue)) {
                Log.e("test", "Bad value iterator: expected value " + realValue + ", got " + value
                        + " at index " + index);
                return false;
            }
            index++;
        }

        return true;
    }

    private static boolean validateArrayMap(ArrayMap array) {
        Set<Map.Entry> entrySet = array.entrySet();
        int index=0;
        Iterator<Map.Entry> entryIt = entrySet.iterator();
        while (entryIt.hasNext()) {
            Map.Entry entry = entryIt.next();
            Object value = entry.getKey();
            Object realValue = array.keyAt(index);
            if (!compare(realValue, value)) {
                Log.e("test", "Bad hash array entry set: expected key " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            value = entry.getValue();
            realValue = array.valueAt(index);
            if (!compare(realValue, value)) {
                Log.e("test", "Bad hash array entry set: expected value " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            index++;
        }

        index = 0;
        Set keySet = array.keySet();
        Iterator keyIt = keySet.iterator();
        while (keyIt.hasNext()) {
            Object value = keyIt.next();
            Object realValue = array.keyAt(index);
            if (!compare(realValue, value)) {
                Log.e("test", "Bad hash array key set: expected key " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            index++;
        }

        index = 0;
        Collection valueCol = array.values();
        Iterator valueIt = valueCol.iterator();
        while (valueIt.hasNext()) {
            Object value = valueIt.next();
            Object realValue = array.valueAt(index);
            if (!compare(realValue, value)) {
                Log.e("test", "Bad hash array value col: expected value " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            index++;
        }

        return true;
    }

    private static void dump(HashMap map, ArrayMap array) {
        Log.e("test", "HashMap of " + map.size() + " entries:");
        Set<Map.Entry> mapSet = map.entrySet();
        for (Map.Entry entry : mapSet) {
            Log.e("test", "    " + entry.getKey() + " -> " + entry.getValue());
        }
        Log.e("test", "ArrayMap of " + array.size() + " entries:");
        for (int i=0; i<array.size(); i++) {
            Log.e("test", "    " + array.keyAt(i) + " -> " + array.valueAt(i));
        }
    }

    public static void run() {
        HashMap<ControlledHash, Integer> mHashMap = new HashMap<ControlledHash, Integer>();
        ArrayMap<ControlledHash, Integer> mArrayMap = new ArrayMap<ControlledHash, Integer>();

        for (int i=0; i<OPS.length; i++) {
            Integer oldMap;
            Integer oldArray;
            switch (OPS[i]) {
                case OP_ADD:
                    Log.i("test", "Adding key: " + KEYS[i]);
                    oldMap = mHashMap.put(new ControlledHash(KEYS[i]), i);
                    oldArray = mArrayMap.put(new ControlledHash(KEYS[i]), i);
                    break;
                case OP_REM:
                    Log.i("test", "Removing key: " + KEYS[i]);
                    oldMap = mHashMap.remove(new ControlledHash(KEYS[i]));
                    oldArray = mArrayMap.remove(new ControlledHash(KEYS[i]));
                    break;
                default:
                    Log.e("test", "Bad operation " + OPS[i] + " @ " + i);
                    return;
            }
            if (!compare(oldMap, oldArray)) {
                Log.e("test", "Bad result: expected " + oldMap + ", got " + oldArray);
                dump(mHashMap, mArrayMap);
                return;
            }
            if (!validateArrayMap(mArrayMap)) {
                dump(mHashMap, mArrayMap);
                return;
            }
            if (!compareMaps(mHashMap, mArrayMap)) {
                dump(mHashMap, mArrayMap);
                return;
            }
        }

        mArrayMap.put(new ControlledHash(50000), 100);
        ControlledHash lookup = new ControlledHash(50000);
        Iterator<ControlledHash> it = mArrayMap.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().equals(lookup)) {
                it.remove();
            }
        }
        if (mArrayMap.containsKey(lookup)) {
            Log.e("test", "Bad iterator: didn't remove test key");
            dump(mHashMap, mArrayMap);
        }

        Log.e("test", "Test successful; printing final map.");
        dump(mHashMap, mArrayMap);
    }
}
