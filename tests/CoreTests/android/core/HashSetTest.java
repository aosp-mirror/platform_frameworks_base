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

import android.test.PerformanceTestBase;
import android.test.PerformanceTestCase;

import java.util.HashSet;
import java.util.Iterator;

/**
 * Implements basic performance test functionality for HashSets
 */

public class HashSetTest extends PerformanceTestBase {
    public static final int ITERATIONS = 1000;
    public static HashSet<Integer> sSet;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sSet = new HashSet<Integer>();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            sSet.add(i);
        }
    }

    @Override
    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        intermediates.setInternalIterations(ITERATIONS);
        return 0;
    }

    /**
     * 
     * Tests performance for the HashSet method Add(Object arg 0)
     * 
     */

    @SuppressWarnings("unchecked")
    public void testHashSetAdd() {
        HashSet set = new HashSet();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
            set.add(i);
        }

    }

    /**
     * 
     * Tests performance of HashSet method contains(Object arg 0)
     * 
     */

    public void testHashSetContains() {
        Integer index = new Integer(500);
        boolean flag;
        HashSet set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
            flag = set.contains(index);
        }
    }

    /**
     * 
     * Tests performance of HashSet method size()
     * 
     */

    public void testHashSetSize() {
        int num;
        HashSet set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
            num = set.size();
        }
    }

    /**
     * 
     * Tests performance of the HashSet method -iterator()
     * 
     */

    public void testHashSetIterator() {
        Iterator iterator;
        HashSet set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
            iterator = set.iterator();
        }
    }

    /**
     * 
     * Tests performance for the HashSet method Remove(Object arg 0)
     * 
     */

    @SuppressWarnings("unchecked")
    public void testHashSetRemove() {
        HashSet set = new HashSet(sSet);
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
            set.remove(i);
        }
    }

    /**
     * 
     * Tests performance for the HashSet method isEmpty(Object arg 0)
     * 
     */

    public void testHashSetIsEmpty() {
        HashSet set = sSet;
        boolean flag;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
            flag = set.isEmpty();
        }
    }

    /**
     * 
     * Tests performance for the HashSet method clone()
     * 
     */

    public void testHashSetClone() {
        HashSet hSet = sSet;
        Object set;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
            set = hSet.clone();
        }
    }
}
