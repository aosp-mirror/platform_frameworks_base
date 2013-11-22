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
import android.util.ArraySet;
import android.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
            OP_ADD, OP_ADD, OP_ADD,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,
            OP_REM, OP_REM, OP_REM,
            OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM, OP_REM,
    };

    static int[] KEYS = new int[] {
            // General adding and removing.
             -1,    1900,    600,    200,   1200,   1500,   1800,    100,   1900,
            2100,    300,    800,    600,   1100,   1300,   2000,   1000,   1400,
             600,    -1,    1900,    600,    300,   2100,    200,    800,    800,
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
               0,     -1,    100,
             106,    108,    104,    102,    103,    105,    107,    101,    109,
              -1,    100,      0,
               4,      5,      3,      5,    200,    203,    202,    201,    205,
    };

    static class ControlledHash {
        final int mValue;

        ControlledHash(int value) {
            mValue = value;
        }

        @Override
        public final boolean equals(Object o) {
            if (o == null) {
                return false;
            }
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

        if (map.entrySet().hashCode() != array.entrySet().hashCode()) {
            Log.e("test", "Entry set hash codes differ: map=0x"
                    + Integer.toHexString(map.entrySet().hashCode()) + " array=0x"
                    + Integer.toHexString(array.entrySet().hashCode()));
            return false;
        }

        if (!map.entrySet().equals(array.entrySet())) {
            Log.e("test", "Failed calling equals on map entry set against array set");
            return false;
        }

        if (!array.entrySet().equals(map.entrySet())) {
            Log.e("test", "Failed calling equals on array entry set against map set");
            return false;
        }

        if (map.keySet().hashCode() != array.keySet().hashCode()) {
            Log.e("test", "Key set hash codes differ: map=0x"
                    + Integer.toHexString(map.keySet().hashCode()) + " array=0x"
                    + Integer.toHexString(array.keySet().hashCode()));
            return false;
        }

        if (!map.keySet().equals(array.keySet())) {
            Log.e("test", "Failed calling equals on map key set against array set");
            return false;
        }

        if (!array.keySet().equals(map.keySet())) {
            Log.e("test", "Failed calling equals on array key set against map set");
            return false;
        }

        if (!map.keySet().containsAll(array.keySet())) {
            Log.e("test", "Failed map key set contains all of array key set");
            return false;
        }

        if (!array.keySet().containsAll(map.keySet())) {
            Log.e("test", "Failed array key set contains all of map key set");
            return false;
        }

        if (!array.containsAll(map.keySet())) {
            Log.e("test", "Failed array contains all of map key set");
            return false;
        }

        if (!map.entrySet().containsAll(array.entrySet())) {
            Log.e("test", "Failed map entry set contains all of array entry set");
            return false;
        }

        if (!array.entrySet().containsAll(map.entrySet())) {
            Log.e("test", "Failed array entry set contains all of map entry set");
            return false;
        }

        return true;
    }

    private static boolean compareSets(HashSet set, ArraySet array) {
        if (set.size() != array.size()) {
            Log.e("test", "Bad size: expected " + set.size() + ", got " + array.size());
            return false;
        }

        for (Object entry : set) {
            if (!array.contains(entry)) {
                Log.e("test", "Bad value: expected " + entry + " not found in ArraySet");
                return false;
            }
        }

        for (int i=0; i<array.size(); i++) {
            Object entry = array.valueAt(i);
            if (!set.contains(entry)) {
                Log.e("test", "Bad value: unexpected " + entry + " in ArraySet");
                return false;
            }
        }

        int index = 0;
        for (Object entry : array) {
            Object realEntry = array.valueAt(index);
            if (!compare(entry, realEntry)) {
                Log.e("test", "Bad iterator: expected value " + realEntry + ", got " + entry
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
                Log.e("test", "Bad array map entry set: expected key " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            value = entry.getValue();
            realValue = array.valueAt(index);
            if (!compare(realValue, value)) {
                Log.e("test", "Bad array map entry set: expected value " + realValue
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
                Log.e("test", "Bad array map key set: expected key " + realValue
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
                Log.e("test", "Bad array map value col: expected value " + realValue
                        + ", got " + value + " at index " + index);
                return false;
            }
            index++;
        }

        return true;
    }

    private static void dump(Map map, ArrayMap array) {
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

    private static void dump(Set set, ArraySet array) {
        Log.e("test", "HashSet of " + set.size() + " entries:");
        for (Object entry : set) {
            Log.e("test", "    " + entry);
        }
        Log.e("test", "ArraySet of " + array.size() + " entries:");
        for (int i=0; i<array.size(); i++) {
            Log.e("test", "    " + array.valueAt(i));
        }
    }

    private static void dump(ArrayMap map1, ArrayMap map2) {
        Log.e("test", "ArrayMap of " + map1.size() + " entries:");
        Set<Map.Entry> mapSet = map1.entrySet();
        for (int i=0; i<map2.size(); i++) {
            Log.e("test", "    " + map1.keyAt(i) + " -> " + map1.valueAt(i));
        }
        Log.e("test", "ArrayMap of " + map2.size() + " entries:");
        for (int i=0; i<map2.size(); i++) {
            Log.e("test", "    " + map2.keyAt(i) + " -> " + map2.valueAt(i));
        }
    }

    public static void run() {
        HashMap<ControlledHash, Integer> hashMap = new HashMap<ControlledHash, Integer>();
        ArrayMap<ControlledHash, Integer> arrayMap = new ArrayMap<ControlledHash, Integer>();
        HashSet<ControlledHash> hashSet = new HashSet<ControlledHash>();
        ArraySet<ControlledHash> arraySet = new ArraySet<ControlledHash>();

        for (int i=0; i<OPS.length; i++) {
            Integer oldHash;
            Integer oldArray;
            boolean hashChanged;
            boolean arrayChanged;
            ControlledHash key = KEYS[i] < 0 ? null : new ControlledHash(KEYS[i]);
            switch (OPS[i]) {
                case OP_ADD:
                    Log.i("test", "Adding key: " + KEYS[i]);
                    oldHash = hashMap.put(key, i);
                    oldArray = arrayMap.put(key, i);
                    hashChanged = hashSet.add(key);
                    arrayChanged = arraySet.add(key);
                    break;
                case OP_REM:
                    Log.i("test", "Removing key: " + KEYS[i]);
                    oldHash = hashMap.remove(key);
                    oldArray = arrayMap.remove(key);
                    hashChanged = hashSet.remove(key);
                    arrayChanged = arraySet.remove(key);
                    break;
                default:
                    Log.e("test", "Bad operation " + OPS[i] + " @ " + i);
                    return;
            }
            if (!compare(oldHash, oldArray)) {
                Log.e("test", "Bad result: expected " + oldHash + ", got " + oldArray);
                dump(hashMap, arrayMap);
                return;
            }
            if (hashChanged != arrayChanged) {
                Log.e("test", "Bad change: expected " + hashChanged + ", got " + arrayChanged);
                dump(hashSet, arraySet);
                return;
            }
            if (!validateArrayMap(arrayMap)) {
                dump(hashMap, arrayMap);
                return;
            }
            if (!compareMaps(hashMap, arrayMap)) {
                dump(hashMap, arrayMap);
                return;
            }
            if (!compareSets(hashSet, arraySet)) {
                dump(hashSet, arraySet);
                return;
            }
        }

        arrayMap.put(new ControlledHash(50000), 100);
        ControlledHash lookup = new ControlledHash(50000);
        Iterator<ControlledHash> it = arrayMap.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().equals(lookup)) {
                it.remove();
            }
        }
        if (arrayMap.containsKey(lookup)) {
            Log.e("test", "Bad map iterator: didn't remove test key");
            dump(hashMap, arrayMap);
        }

        arraySet.add(new ControlledHash(50000));
        it = arraySet.iterator();
        while (it.hasNext()) {
            if (it.next().equals(lookup)) {
                it.remove();
            }
        }
        if (arraySet.contains(lookup)) {
            Log.e("test", "Bad set iterator: didn't remove test key");
            dump(hashSet, arraySet);
        }

        if (!equalsMapTest()) {
            return;
        }

        if (!equalsSetTest()) {
            return;
        }

        // map copy constructor test
        ArrayMap newMap = new ArrayMap<Integer, String>();
        for (int i = 0; i < 10; ++i) {
            newMap.put(i, String.valueOf(i));
        }
        ArrayMap mapCopy = new ArrayMap(newMap);
        if (!compare(mapCopy, newMap)) {
            Log.e("test", "ArrayMap copy constructor failure: expected " +
                    newMap + ", got " + mapCopy);
            dump(newMap, mapCopy);
            return;
        }

        // set copy constructor test
        ArraySet newSet = new ArraySet<Integer>();
        for (int i = 0; i < 10; ++i) {
            newSet.add(i);
        }
        ArraySet setCopy = new ArraySet(newSet);
        if (!compare(setCopy, newSet)) {
            Log.e("test", "ArraySet copy constructor failure: expected " +
                    newSet + ", got " + setCopy);
            dump(newSet, setCopy);
            return;
        }

        Log.e("test", "Test successful; printing final map.");
        dump(hashMap, arrayMap);

        Log.e("test", "Test successful; printing final set.");
        dump(hashSet, arraySet);
    }

    private static boolean equalsMapTest() {
        ArrayMap<Integer, String> map1 = new ArrayMap<Integer, String>();
        ArrayMap<Integer, String> map2 = new ArrayMap<Integer, String>();
        HashMap<Integer, String> map3 = new HashMap<Integer, String>();
        if (!compare(map1, map2) || !compare(map1, map3) || !compare(map3, map2)) {
            Log.e("test", "ArrayMap equals failure for empty maps " + map1 + ", " +
                    map2 + ", " + map3);
            return false;
        }

        for (int i = 0; i < 10; ++i) {
            String value = String.valueOf(i);
            map1.put(i, value);
            map2.put(i, value);
            map3.put(i, value);
        }
        if (!compare(map1, map2) || !compare(map1, map3) || !compare(map3, map2)) {
            Log.e("test", "ArrayMap equals failure for populated maps " + map1 + ", " +
                    map2 + ", " + map3);
            return false;
        }

        map1.remove(0);
        if (compare(map1, map2) || compare(map1, map3) || compare(map3, map1)) {
            Log.e("test", "ArrayMap equals failure for map size " + map1 + ", " +
                    map2 + ", " + map3);
            return false;
        }

        map1.put(0, "-1");
        if (compare(map1, map2) || compare(map1, map3) || compare(map3, map1)) {
            Log.e("test", "ArrayMap equals failure for map contents " + map1 + ", " +
                    map2 + ", " + map3);
            return false;
        }

        return true;
    }

    private static boolean equalsSetTest() {
        ArraySet<Integer> set1 = new ArraySet<Integer>();
        ArraySet<Integer> set2 = new ArraySet<Integer>();
        HashSet<Integer> set3 = new HashSet<Integer>();
        if (!compare(set1, set2) || !compare(set1, set3) || !compare(set3, set2)) {
            Log.e("test", "ArraySet equals failure for empty sets " + set1 + ", " +
                    set2 + ", " + set3);
            return false;
        }

        for (int i = 0; i < 10; ++i) {
            set1.add(i);
            set2.add(i);
            set3.add(i);
        }
        if (!compare(set1, set2) || !compare(set1, set3) || !compare(set3, set2)) {
            Log.e("test", "ArraySet equals failure for populated sets " + set1 + ", " +
                    set2 + ", " + set3);
            return false;
        }

        set1.remove(0);
        if (compare(set1, set2) || compare(set1, set3) || compare(set3, set1)) {
            Log.e("test", "ArraSet equals failure for set size " + set1 + ", " +
                    set2 + ", " + set3);
            return false;
        }

        set1.add(-1);
        if (compare(set1, set2) || compare(set1, set3) || compare(set3, set1)) {
            Log.e("test", "ArraySet equals failure for set contents " + set1 + ", " +
                    set2 + ", " + set3);
            return false;
        }

        return true;
    }
}
