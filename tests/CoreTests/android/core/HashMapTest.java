/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Iterator;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Test cases for Hashmap.
 */
public class HashMapTest extends TestCase {
    private static final Integer ONE = new Integer(1);
    private static final Integer TWO = new Integer(2);
    private static final Integer THREE = new Integer(3);
    private static final Integer FOUR = new Integer(4);

    private void addItems(HashMap map) {
        map.put("one", ONE);
        map.put("two", TWO);
        map.put("three", THREE);
        map.put("four", FOUR);

        assertEquals(4, map.size());

        assertEquals(ONE, map.get("one"));
        assertEquals(TWO, map.get("two"));
        assertEquals(THREE, map.get("three"));
        assertEquals(FOUR, map.get("four"));
    }

    /**
     * checks if simple adding elements works.
     */
    @SmallTest
    public void testAdd() throws Exception {
        HashMap map = new HashMap();
        addItems(map);
    }

    /**
     * checks if clearing the map works.
     */
    @SmallTest
    public void testClear() throws Exception {
        HashMap map = new HashMap();

        addItems(map);
        map.clear();
        assertEquals(0, map.size());
    }

    /**
     * checks if removing an elemt works.
     */
    @SmallTest
    public void testRemove() throws Exception {
        HashMap map = new HashMap();

        addItems(map);
        map.remove("three");
        assertNull(map.get("three"));
    }

    /**
     * does some manipulation with a filled HashMap and checks
     * if they work as intended
     */
    @SmallTest
    public void testManipulate() throws Exception {
        HashMap map = new HashMap();

        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(null));
        assertNull(map.get("one"));
        assertFalse(map.containsKey("one"));
        assertFalse(map.containsValue(new Integer(1)));
        assertNull(map.remove(null));
        assertNull(map.remove("one"));

        assertNull(map.put(null, new Integer(-1)));
        assertNull(map.put("one", new Integer(1)));
        assertNull(map.put("two", new Integer(2)));
        assertNull(map.put("three", new Integer(3)));
        assertEquals(-1, ((Integer) map.put(null, new Integer(0))).intValue());

        assertEquals(0, ((Integer) map.get(null)).intValue());
        assertEquals(1, ((Integer) map.get("one")).intValue());
        assertEquals(2, ((Integer) map.get("two")).intValue());
        assertEquals(3, ((Integer) map.get("three")).intValue());

        assertTrue(map.containsKey(null));
        assertTrue(map.containsKey("one"));
        assertTrue(map.containsKey("two"));
        assertTrue(map.containsKey("three"));

        assertTrue(map.containsValue(new Integer(0)));
        assertTrue(map.containsValue(new Integer(1)));
        assertTrue(map.containsValue(new Integer(2)));
        assertTrue(map.containsValue(new Integer(3)));

        assertEquals(0, ((Integer) map.remove(null)).intValue());
        assertEquals(1, ((Integer) map.remove("one")).intValue());
        assertEquals(2, ((Integer) map.remove("two")).intValue());
        assertEquals(3, ((Integer) map.remove("three")).intValue());

        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(null));
        assertNull(map.get("one"));
        assertFalse(map.containsKey("one"));
        assertFalse(map.containsValue(new Integer(1)));
        assertNull(map.remove(null));
        assertNull(map.remove("one"));
    }

    /**
     * checks if the key iterator of HashMaps work.
     */
    @SmallTest
    public void testKeyIterator() throws Exception {
        HashMap map = new HashMap();

        boolean[] slots = new boolean[4];

        addItems(map);

        Iterator iter = map.keySet().iterator();

        while (iter.hasNext()) {
            int slot = 0;
            Object key = iter.next();

            if (key.equals("one"))
                slot = 0;
            else if (key.equals("two"))
                slot = 1;
            else if (key.equals("three"))
                slot = 2;
            else if (key.equals("four"))
                slot = 3;
            else
                fail("Unkown key in hashmap");

            if (slots[slot])
                fail("key returned more than once");
            else
                slots[slot] = true;
        }

        assertTrue(slots[0]);
        assertTrue(slots[1]);
        assertTrue(slots[2]);
        assertTrue(slots[3]);
    }

    /**
     * checks if the value iterator works.
     */
    @SmallTest
    public void testValueIterator() throws Exception {
        HashMap map = new HashMap();

        boolean[] slots = new boolean[4];

        addItems(map);

        Iterator iter = map.values().iterator();

        while (iter.hasNext()) {
            int slot = 0;
            Object value = iter.next();

            if (value.equals(ONE))
                slot = 0;
            else if (value.equals(TWO))
                slot = 1;
            else if (value.equals(THREE))
                slot = 2;
            else if (value.equals(FOUR))
                slot = 3;
            else
                fail("Unkown value in hashmap");

            if (slots[slot])
                fail("value returned more than once");
            else
                slots[slot] = true;
        }

        assertTrue(slots[0]);
        assertTrue(slots[1]);
        assertTrue(slots[2]);
        assertTrue(slots[3]);
    }

    /**
     * checks if the entry iterator works for HashMaps.
     */
    @SmallTest
    public void testEntryIterator() throws Exception {
        HashMap map = new HashMap();

        boolean[] slots = new boolean[4];

        addItems(map);

        Iterator iter = map.entrySet().iterator();

        while (iter.hasNext()) {
            int slot = 0;
            Object entry = iter.next();

            if (entry.toString().equals("one=1"))
                slot = 0;
            else if (entry.toString().equals("two=2"))
                slot = 1;
            else if (entry.toString().equals("three=3"))
                slot = 2;
            else if (entry.toString().equals("four=4"))
                slot = 3;
            else
                fail("Unkown entry in hashmap");

            if (slots[slot])
                fail("entry returned more than once");
            else
                slots[slot] = true;
        }

        assertTrue(slots[0]);
        assertTrue(slots[1]);
        assertTrue(slots[2]);
        assertTrue(slots[3]);
    }

    /**
     * checks if the HashMap equals method works.
     */
    @SmallTest
    public void testEquals() throws Exception {
        HashMap map1 = new HashMap();
        HashMap map2 = new HashMap();
        HashMap map3 = new HashMap();

        map1.put("one", "1");
        map1.put("two", "2");
        map1.put("three", "3");

        map2.put("one", new String("1"));
        map2.put(new String("two"), "2");
        map2.put(new String("three"), new String("3"));

        assertTrue(map1.equals(map2));

        map3.put("one", "1");
        map3.put("two", "1");
        map3.put("three", "1");

        assertFalse(map1.equals(map3));
        assertFalse(map2.equals(map3));
    }
}

