/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

import android.test.PerformanceTestBase;
import android.test.PerformanceTestCase;

public class HashMapPerformanceTest extends PerformanceTestBase {
    public static final int ITERATIONS = 1000;
    public HashMap mMap;
    public String[] mKeys;

    @Override
    @SuppressWarnings("unchecked")
    protected void setUp() throws Exception {
        super.setUp();
        mMap = new HashMap();
        mKeys = new String[ITERATIONS];

        for (int i = ITERATIONS - 1; i >= 0; i--) {
            mKeys[i] = Integer.toString(i, 16);
            mMap.put(mKeys[i], i);
        }
    }

    @Override
    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        intermediates.setInternalIterations(ITERATIONS);
        return 0;
    }

    public void testHashMapGet() {
        int num;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
            num = (Integer) mMap.get(mKeys[i]);
        }
    }

    public void testHashMapKeySet() {
        Set keyset;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
            keyset = mMap.keySet();
        }
    }

    public void testHashMapEntrySet() {
        Set keyset;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();
            keyset = mMap.entrySet();


        }
    }

    public void testHashMapValues() {
        Collection c;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();
            c = mMap.values();


        }
    }

    public void testHashMapSize() {
        int len;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();
            len = mMap.size();


        }
    }

    public void testHashMapContainsValue() {
        boolean flag;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);
            flag = mMap.containsValue(i);


        }
    }

    public void testHashMapRemove() {
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
            mMap.remove(mKeys[i]);
        }
    }


    public void testHashMapClone() {
        HashMap cMap;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
            cMap = (HashMap) mMap.clone();
        }
    }
}
