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

import android.os.SystemClock;
import android.test.suitebuilder.annotation.LargeTest;
import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Random;

/**
 * Tests basic functionality of HashMaps and prints the time needed to System.out
 */
public class HashMapPerfTest extends TestCase {

    private static final Random sRandom = new Random(1);

    class StringThing {

        String mId;

        public StringThing() {
            int len = sRandom.nextInt(20) + 1;
            char[] chars = new char[len];
            chars[0] = 't';
            for (int i = 1; i < len; i++) {
                chars[i] = (char) ('q' + sRandom.nextInt(4));
            }
            mId = new String(chars, 0, len);
        }

        public String getId() {
            return mId;
        }
    }

    private static final int NUM_ELTS = 1000;
    private static final int ITERS = 100;

    String[] keyCopies = new String[NUM_ELTS];

    private static final boolean lookupByOriginals = false;

    @LargeTest
    public void testHashMapPerformance() throws Exception {
        StringThing[] st = new StringThing[NUM_ELTS];
        for (int i = 0; i < NUM_ELTS; i++) {
            st[i] = new StringThing();
            keyCopies[i] = st[i].getId();
        }

        // android.os.Debug.startMethodTracing();
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < ITERS; i++) {
            HashMap<String, StringThing> map = new HashMap<String, StringThing>();
            for (int j = 0; j < NUM_ELTS; j++) {
                StringThing s = st[i];
                map.put(s.getId(), s);
            }
            for (int j = 0; j < NUM_ELTS; j++) {
                if (lookupByOriginals) {
                    StringThing s = st[i];
                    map.get(s.getId());
                } else {
                    map.get(keyCopies[j]);
                }
            }
        }
        long finish = SystemClock.uptimeMillis();
        // android.os.Debug.stopMethodTracing();

        // This should be an assertion instead
        
//        System.out.println("time (" + NUM_ELTS +
//                ", elts, " + ITERS +
//                " iters) = " + (finish - start));
    }
}
