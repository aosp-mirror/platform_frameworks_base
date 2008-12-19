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
import java.util.Random;
import java.util.TreeMap;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Tests for basic functinality of TreeMaps
 */
public class TreeMapTest extends TestCase {

    private Random mRandom = new Random(1);

    private static final boolean SPEW = false;

    @LargeTest
    public void testTreeMap() {
        for (int i = 0; i < 10; i++) {
            if (SPEW) System.out.println("Running doTest cycle #" + (i + 1));
            doTest();
        }
    }

    private void doTest() {
        TreeMap<Integer, String> tm = new TreeMap<Integer, String>();
        HashMap<Integer, String> hm = new HashMap<Integer, String>();

        int minVal = Integer.MAX_VALUE;
        int maxVal = Integer.MIN_VALUE;

        for (int i = 0; i < 100; i++) {
            int val = mRandom.nextInt(1000);
            if (SPEW) System.out.println("Adding val = " + val);
            if (val < minVal) {
                minVal = val;
            }
            if (val > maxVal) {
                maxVal = val;
            }
            tm.put(new Integer(val), "V:" + val);
            hm.put(new Integer(val), "V:" + val);

            if (SPEW) System.out.println("tm = " + tm);

            if (SPEW) System.out.println("tm.size() = " + tm.size());
            if (SPEW) System.out.println("hm.size() = " + hm.size());
            assertEquals(tm.size(), hm.size());

            if (SPEW) System.out.println("tm.firstKey() = " + tm.firstKey());
            if (SPEW) System.out.println("minVal = " + minVal);
            if (SPEW) System.out.println("tm.lastKey() = " + tm.lastKey());
            if (SPEW) System.out.println("maxVal = " + maxVal);
            assertEquals(minVal, tm.firstKey().intValue());
            assertEquals(maxVal, tm.lastKey().intValue());
        }

        // Check for equality
        for (int val = 0; val < 1000; val++) {
            Integer vv = new Integer(val);
            String tms = tm.get(vv);
            String hms = hm.get(vv);
            assertEquals(tms, hms);
        }

        for (int i = 0; i < 1000; i++) {
            int val = mRandom.nextInt(1000);
            if (SPEW) System.out.println("Removing val = " + val);

            String tms = tm.remove(new Integer(val));
            String hms = hm.remove(new Integer(val));

            if (SPEW) System.out.println("tm = " + tm);

            assertEquals(tm.size(), hm.size());
            assertEquals(tms, hms);
        }

        // Check for equality
        for (int val = 0; val < 1000; val++) {
            Integer vv = new Integer(val);
            String tms = tm.get(vv);
            String hms = hm.get(vv);
            assertEquals(tms, hms);
        }
    }
}
