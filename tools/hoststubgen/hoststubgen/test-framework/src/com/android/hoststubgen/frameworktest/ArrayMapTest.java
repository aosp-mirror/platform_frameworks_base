/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.frameworktest;

// [ravewnwood] Copied from cts/, and commented out unsupported stuff.

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Some basic tests for {@link android.util.ArrayMap}.
 */
@RunWith(AndroidJUnit4.class)
public class ArrayMapTest {
    static final boolean DEBUG = false;

    static final int OP_ADD = 1;
    static final int OP_REM = 2;

    static int[] OPS = new int[]{
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

    static int[] KEYS = new int[]{
            // General adding and removing.
            -1, 1900, 600, 200, 1200, 1500, 1800, 100, 1900,
            2100, 300, 800, 600, 1100, 1300, 2000, 1000, 1400,
            600, -1, 1900, 600, 300, 2100, 200, 800, 800,
            1800, 1500, 1300, 1100, 2000, 1400, 1000, 1200, 1900,

            // Shrink when removing item from end.
            100, 200, 300, 400, 500, 600, 700, 800, 900,
            900, 800, 700, 600, 500, 400, 300, 200, 100,

            // Shrink when removing item from middle.
            100, 200, 300, 400, 500, 600, 700, 800, 900,
            900, 800, 700, 600, 500, 400, 200, 300, 100,

            // Shrink when removing item from front.
            100, 200, 300, 400, 500, 600, 700, 800, 900,
            900, 800, 700, 600, 500, 400, 100, 200, 300,

            // Test hash collisions.
            105, 106, 108, 104, 102, 102, 107, 5, 205,
            4, 202, 203, 3, 5, 101, 109, 200, 201,
            0, -1, 100,
            106, 108, 104, 102, 103, 105, 107, 101, 109,
            -1, 100, 0,
            4, 5, 3, 5, 200, 203, 202, 201, 205,
    };

    public static class ControlledHash implements Parcelable {
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

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mValue);
        }

        public static final Parcelable.Creator<ControlledHash> CREATOR
                = new Parcelable.Creator<ControlledHash>() {
            public ControlledHash createFromParcel(Parcel in) {
                return new ControlledHash(in.readInt());
            }

            public ControlledHash[] newArray(int size) {
                return new ControlledHash[size];
            }
        };
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

    private static void compareMaps(HashMap map, ArrayMap array) {
        if (map.size() != array.size()) {
            fail("Bad size: expected " + map.size() + ", got " + array.size());
        }

        Set<Entry> mapSet = map.entrySet();
        for (Map.Entry entry : mapSet) {
            Object expValue = entry.getValue();
            Object gotValue = array.get(entry.getKey());
            if (!compare(expValue, gotValue)) {
                fail("Bad value: expected " + expValue + ", got " + gotValue
                        + " at key " + entry.getKey());
            }
        }

        for (int i = 0; i < array.size(); i++) {
            Object gotValue = array.valueAt(i);
            Object key = array.keyAt(i);
            Object expValue = map.get(key);
            if (!compare(expValue, gotValue)) {
                fail("Bad value: expected " + expValue + ", got " + gotValue
                        + " at key " + key);
            }
        }

        if (map.entrySet().hashCode() != array.entrySet().hashCode()) {
            fail("Entry set hash codes differ: map=0x"
                    + Integer.toHexString(map.entrySet().hashCode()) + " array=0x"
                    + Integer.toHexString(array.entrySet().hashCode()));
        }

        if (!map.entrySet().equals(array.entrySet())) {
            fail("Failed calling equals on map entry set against array set");
        }

        if (!array.entrySet().equals(map.entrySet())) {
            fail("Failed calling equals on array entry set against map set");
        }

        if (map.keySet().hashCode() != array.keySet().hashCode()) {
            fail("Key set hash codes differ: map=0x"
                    + Integer.toHexString(map.keySet().hashCode()) + " array=0x"
                    + Integer.toHexString(array.keySet().hashCode()));
        }

        if (!map.keySet().equals(array.keySet())) {
            fail("Failed calling equals on map key set against array set");
        }

        if (!array.keySet().equals(map.keySet())) {
            fail("Failed calling equals on array key set against map set");
        }

        if (!map.keySet().containsAll(array.keySet())) {
            fail("Failed map key set contains all of array key set");
        }

        if (!array.keySet().containsAll(map.keySet())) {
            fail("Failed array key set contains all of map key set");
        }

        if (!array.containsAll(map.keySet())) {
            fail("Failed array contains all of map key set");
        }

        if (!map.entrySet().containsAll(array.entrySet())) {
            fail("Failed map entry set contains all of array entry set");
        }

        if (!array.entrySet().containsAll(map.entrySet())) {
            fail("Failed array entry set contains all of map entry set");
        }
    }

    private static void validateArrayMap(ArrayMap array) {
        Set<Map.Entry> entrySet = array.entrySet();
        int index = 0;
        Iterator<Entry> entryIt = entrySet.iterator();
        while (entryIt.hasNext()) {
            Map.Entry entry = entryIt.next();
            Object value = entry.getKey();
            Object realValue = array.keyAt(index);
            if (!compare(realValue, value)) {
                fail("Bad array map entry set: expected key " + realValue
                        + ", got " + value + " at index " + index);
            }
            value = entry.getValue();
            realValue = array.valueAt(index);
            if (!compare(realValue, value)) {
                fail("Bad array map entry set: expected value " + realValue
                        + ", got " + value + " at index " + index);
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
                fail("Bad array map key set: expected key " + realValue
                        + ", got " + value + " at index " + index);
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
                fail("Bad array map value col: expected value " + realValue
                        + ", got " + value + " at index " + index);
            }
            index++;
        }
    }

    private static void compareBundles(Bundle bundle1, Bundle bundle2) {
        Set<String> keySet1 = bundle1.keySet();
        Iterator<String> iterator1 = keySet1.iterator();
        while (iterator1.hasNext()) {
            String key = iterator1.next();
            int value1 = bundle1.getInt(key);
            if (bundle2.get(key) == null) {
                fail("Bad Bundle: bundle2 didn't have expected key " + key);
            }
            int value2 = bundle2.getInt(key);
            if (value1 != value2) {
                fail("Bad Bundle: at key key " + key + " expected " + value1 + ", got " + value2);
            }
        }
        Set<String> keySet2 = bundle2.keySet();
        Iterator<String> iterator2 = keySet2.iterator();
        while (iterator2.hasNext()) {
            String key = iterator2.next();
            if (bundle1.get(key) == null) {
                fail("Bad Bundle: bundle1 didn't have expected key " + key);
            }
            int value1 = bundle1.getInt(key);
            int value2 = bundle2.getInt(key);
            if (value1 != value2) {
                fail("Bad Bundle: at key key " + key + " expected " + value1 + ", got " + value2);
            }
        }
    }

    private static void dump(Map map, ArrayMap array) {
        Log.e("test", "HashMap of " + map.size() + " entries:");
        Set<Map.Entry> mapSet = map.entrySet();
        for (Map.Entry entry : mapSet) {
            Log.e("test", "    " + entry.getKey() + " -> " + entry.getValue());
        }
        Log.e("test", "ArrayMap of " + array.size() + " entries:");
        for (int i = 0; i < array.size(); i++) {
            Log.e("test", "    " + array.keyAt(i) + " -> " + array.valueAt(i));
        }
    }

    private static void dump(ArrayMap map1, ArrayMap map2) {
        Log.e("test", "ArrayMap of " + map1.size() + " entries:");
        for (int i = 0; i < map1.size(); i++) {
            Log.e("test", "    " + map1.keyAt(i) + " -> " + map1.valueAt(i));
        }
        Log.e("test", "ArrayMap of " + map2.size() + " entries:");
        for (int i = 0; i < map2.size(); i++) {
            Log.e("test", "    " + map2.keyAt(i) + " -> " + map2.valueAt(i));
        }
    }

    private static void dump(Bundle bundle1, Bundle bundle2) {
        Log.e("test", "First Bundle of " + bundle1.size() + " entries:");
        Set<String> keys1 = bundle1.keySet();
        for (String key : keys1) {
            Log.e("test", "    " + key + " -> " + bundle1.get(key));
        }
        Log.e("test", "Second Bundle of " + bundle2.size() + " entries:");
        Set<String> keys2 = bundle2.keySet();
        for (String key : keys2) {
            Log.e("test", "    " + key + " -> " + bundle2.get(key));
        }
    }

    @Test
    public void testBasicArrayMap() {
        HashMap<ControlledHash, Integer> hashMap = new HashMap<>();
        ArrayMap<ControlledHash, Integer> arrayMap = new ArrayMap<>();
        Bundle bundle = new Bundle();

        for (int i = 0; i < OPS.length; i++) {
            Integer oldHash;
            Integer oldArray;
            ControlledHash key = KEYS[i] < 0 ? null : new ControlledHash(KEYS[i]);
            String strKey = KEYS[i] < 0 ? null : Integer.toString(KEYS[i]);
            switch (OPS[i]) {
                case OP_ADD:
                    if (DEBUG) Log.i("test", "Adding key: " + key);
                    oldHash = hashMap.put(key, i);
                    oldArray = arrayMap.put(key, i);
                    bundle.putInt(strKey, i);
                    break;
                case OP_REM:
                    if (DEBUG) Log.i("test", "Removing key: " + key);
                    oldHash = hashMap.remove(key);
                    oldArray = arrayMap.remove(key);
                    bundle.remove(strKey);
                    break;
                default:
                    fail("Bad operation " + OPS[i] + " @ " + i);
                    return;
            }
            if (!compare(oldHash, oldArray)) {
                String msg = "Bad result: expected " + oldHash + ", got " + oldArray;
                Log.e("test", msg);
                dump(hashMap, arrayMap);
                fail(msg);
            }
            try {
                validateArrayMap(arrayMap);
            } catch (Throwable e) {
                Log.e("test", e.getMessage());
                dump(hashMap, arrayMap);
                throw e;
            }
            try {
                compareMaps(hashMap, arrayMap);
            } catch (Throwable e) {
                Log.e("test", e.getMessage());
                dump(hashMap, arrayMap);
                throw e;
            }
            Parcel parcel = Parcel.obtain();
            bundle.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Bundle bundle2 = parcel.readBundle();
            try {
                compareBundles(bundle, bundle2);
            } catch (Throwable e) {
                Log.e("test", e.getMessage());
                dump(bundle, bundle2);
                throw e;
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
            String msg = "Bad map iterator: didn't remove test key";
            Log.e("test", msg);
            dump(hashMap, arrayMap);
            fail(msg);
        }
    }

    @Test
    public void testCopyArrayMap() {
        // map copy constructor test
        ArrayMap newMap = new ArrayMap<Integer, String>();
        for (int i = 0; i < 10; ++i) {
            newMap.put(i, String.valueOf(i));
        }
        ArrayMap mapCopy = new ArrayMap(newMap);
        if (!compare(mapCopy, newMap)) {
            String msg = "ArrayMap copy constructor failure: expected " +
                    newMap + ", got " + mapCopy;
            Log.e("test", msg);
            dump(newMap, mapCopy);
            fail(msg);
            return;
        }
    }

    @Test
    public void testEqualsArrayMap() {
        ArrayMap<Integer, String> map1 = new ArrayMap<>();
        ArrayMap<Integer, String> map2 = new ArrayMap<>();
        HashMap<Integer, String> map3 = new HashMap<>();
        if (!compare(map1, map2) || !compare(map1, map3) || !compare(map3, map2)) {
            fail("ArrayMap equals failure for empty maps " + map1 + ", " +
                    map2 + ", " + map3);
        }

        for (int i = 0; i < 10; ++i) {
            String value = String.valueOf(i);
            map1.put(i, value);
            map2.put(i, value);
            map3.put(i, value);
        }
        if (!compare(map1, map2) || !compare(map1, map3) || !compare(map3, map2)) {
            fail("ArrayMap equals failure for populated maps " + map1 + ", " +
                    map2 + ", " + map3);
        }

        map1.remove(0);
        if (compare(map1, map2) || compare(map1, map3) || compare(map3, map1)) {
            fail("ArrayMap equals failure for map size " + map1 + ", " +
                    map2 + ", " + map3);
        }

        map1.put(0, "-1");
        if (compare(map1, map2) || compare(map1, map3) || compare(map3, map1)) {
            fail("ArrayMap equals failure for map contents " + map1 + ", " +
                    map2 + ", " + map3);
        }
    }

//    /**
//     * Test creating a malformed array map with duplicated keys and that we will catch this when
//     * unparcelling.
//     */
//    @Test
//    public void testDuplicateKeys() throws NoSuchMethodException,
//            InvocationTargetException, IllegalAccessException, NoSuchFieldException {
//        ArrayMap<String, Object> map1 = new ArrayMap(2);
//
//        Method appendMethod = ArrayMap.class.getMethod("append", Object.class, Object.class);
//        appendMethod.invoke(map1, Integer.toString(100000), "foo");
//        appendMethod.invoke(map1, Integer.toString(100000), "bar");
//
//        // Now parcel/unparcel, and verify we get the expected error.
//        Parcel parcel = Parcel.obtain();
//        Method writeArrayMapMethod = Parcel.class.getMethod("writeArrayMap", ArrayMap.class);
//        writeArrayMapMethod.invoke(parcel, map1);
//        parcel.setDataPosition(0);
//        ArrayMap<String, Object> map2 = new ArrayMap(2);
//
//        try {
//            Parcel.class.getMethod("readArrayMap", ArrayMap.class, ClassLoader.class).invoke(
//                    parcel, map2, null);
//        } catch (InvocationTargetException e) {
//            Throwable cause = e.getCause();
//            if (cause instanceof IllegalArgumentException) {
//                // Good!
//                return;
//            }
//            throw e;
//        }
//
//        String msg = "Didn't throw expected IllegalArgumentException";
//        Log.e("test", msg);
//        dump(map1, map2);
//        fail(msg);
//    }

    private static void checkEntrySetToArray(ArrayMap<?, ?> testMap) {
        try {
            testMap.entrySet().toArray();
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        try {
            Map.Entry<?, ?>[] entries = new Map.Entry[20];
            testMap.entrySet().toArray(entries);
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    // http://b/32294038, Test ArrayMap.entrySet().toArray()
    @Test
    public void testEntrySetArray() {
        // Create
        ArrayMap<Integer, String> testMap = new ArrayMap<>();

        // Test empty
        checkEntrySetToArray(testMap);

        // Test non-empty
        for (int i = 0; i < 10; ++i) {
            testMap.put(i, String.valueOf(i));
        }
        checkEntrySetToArray(testMap);
    }

    @Test
    public void testCanNotIteratePastEnd_entrySetIterator() {
        Map<String, String> map = new ArrayMap<>();
        map.put("key 1", "value 1");
        map.put("key 2", "value 2");
        Set<Map.Entry<String, String>> expectedEntriesToIterate = new HashSet<>(Arrays.asList(
                entryOf("key 1", "value 1"),
                entryOf("key 2", "value 2")
        ));
        Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();

        // Assert iteration over the expected two entries in any order
        assertTrue(iterator.hasNext());
        Map.Entry<String, String> firstEntry = copyOf(iterator.next());
        assertTrue(expectedEntriesToIterate.remove(firstEntry));

        assertTrue(iterator.hasNext());
        Map.Entry<String, String> secondEntry = copyOf(iterator.next());
        assertTrue(expectedEntriesToIterate.remove(secondEntry));

        assertFalse(iterator.hasNext());

        try {
            iterator.next();
            fail();
        } catch (NoSuchElementException expected) {
        }
    }

    private static <K, V> Map.Entry<K, V> entryOf(K key, V value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    private static <K, V> Map.Entry<K, V> copyOf(Map.Entry<K, V> entry) {
        return entryOf(entry.getKey(), entry.getValue());
    }

    @Test
    public void testCanNotIteratePastEnd_keySetIterator() {
        Map<String, String> map = new ArrayMap<>();
        map.put("key 1", "value 1");
        map.put("key 2", "value 2");
        Set<String> expectedKeysToIterate = new HashSet<>(Arrays.asList("key 1", "key 2"));
        Iterator<String> iterator = map.keySet().iterator();

        // Assert iteration over the expected two keys in any order
        assertTrue(iterator.hasNext());
        String firstKey = iterator.next();
        assertTrue(expectedKeysToIterate.remove(firstKey));

        assertTrue(iterator.hasNext());
        String secondKey = iterator.next();
        assertTrue(expectedKeysToIterate.remove(secondKey));

        assertFalse(iterator.hasNext());

        try {
            iterator.next();
            fail();
        } catch (NoSuchElementException expected) {
        }
    }

    @Test
    public void testCanNotIteratePastEnd_valuesIterator() {
        Map<String, String> map = new ArrayMap<>();
        map.put("key 1", "value 1");
        map.put("key 2", "value 2");
        Set<String> expectedValuesToIterate = new HashSet<>(Arrays.asList("value 1", "value 2"));
        Iterator<String> iterator = map.values().iterator();

        // Assert iteration over the expected two values in any order
        assertTrue(iterator.hasNext());
        String firstValue = iterator.next();
        assertTrue(expectedValuesToIterate.remove(firstValue));

        assertTrue(iterator.hasNext());
        String secondValue = iterator.next();
        assertTrue(expectedValuesToIterate.remove(secondValue));

        assertFalse(iterator.hasNext());

        try {
            iterator.next();
            fail();
        } catch (NoSuchElementException expected) {
        }
    }

    @Test
    public void testForEach() {
        ArrayMap<String, Integer> map = new ArrayMap<>();

        for (int i = 0; i < 50; ++i) {
            map.put(Integer.toString(i), i * 10);
        }

        // Make sure forEach goes through all of the elements.
        HashMap<String, Integer> seen = new HashMap<>();
        map.forEach(seen::put);
        compareMaps(seen, map);
    }

    /**
     * The entrySet Iterator returns itself from each call to {@code next()}. This is unusual
     * behavior for {@link Iterator#next()}; this test ensures that any future change to this
     * behavior is deliberate.
     */
    @Test
    public void testUnusualBehavior_eachEntryIsSameAsIterator_entrySetIterator() {
        Map<String, String> map = new ArrayMap<>();
        map.put("key 1", "value 1");
        map.put("key 2", "value 2");
        Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();

        assertSame(iterator, iterator.next());
        assertSame(iterator, iterator.next());
    }

    @SuppressWarnings("SelfEquals")
    @Test
    public void testUnusualBehavior_equalsThrowsAfterRemove_entrySetIterator() {
        Map<String, String> map = new ArrayMap<>();
        map.put("key 1", "value 1");
        map.put("key 2", "value 2");
        Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();
        iterator.next();
        iterator.remove();
        try {
            iterator.equals(iterator);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    private static <T> void assertEqualsBothWays(T a, T b) {
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testRemoveAll() {
        final ArrayMap<Integer, String> map = new ArrayMap<>();
        for (Integer i : Arrays.asList(0, 1, 2, 3, 4, 5)) {
            map.put(i, i.toString());
        }

        final ArrayMap<Integer, String> expectedMap = new ArrayMap<>();
        for (Integer i : Arrays.asList(2, 4)) {
            expectedMap.put(i, String.valueOf(i));
        }
        map.removeAll(Arrays.asList(0, 1, 3, 5, 6));
        if (!compare(map, expectedMap)) {
            fail("ArrayMap removeAll failure, expect " + expectedMap + ", but " + map);
        }

        map.removeAll(Collections.emptyList());
        if (!compare(map, expectedMap)) {
            fail("ArrayMap removeAll failure for empty maps, expect " + expectedMap + ", but " +
                    map);
        }

        map.removeAll(Arrays.asList(2, 4));
        if (!map.isEmpty()) {
            fail("ArrayMap removeAll failure, expect empty, but " + map);
        }
    }

    @Test
    public void testReplaceAll() {
        final ArrayMap<Integer, Integer> map = new ArrayMap<>();
        final ArrayMap<Integer, Integer> expectedMap = new ArrayMap<>();
        final BiFunction<Integer, Integer, Integer> function = (k, v) -> 2 * v;
        for (Integer i : Arrays.asList(0, 1, 2, 3, 4, 5)) {
            map.put(i, i);
            expectedMap.put(i, 2 * i);
        }

        map.replaceAll(function);
        if (!compare(map, expectedMap)) {
            fail("ArrayMap replaceAll failure, expect " + expectedMap + ", but " + map);
        }
    }
}