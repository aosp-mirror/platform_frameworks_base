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

import java.util.TreeSet;
import java.util.SortedSet;
import java.util.Iterator;
import java.util.Comparator;

/**
 * Implements basic performance test functionality for java.util.TreeSet
 */

public class TreeSetTest extends PerformanceTestBase {
    public static final int ITERATIONS = 1000;
    public static TreeSet<Integer> sSet;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sSet = new TreeSet<Integer>();
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
     * Tests performance for the java.util.TreeSet method Add(Object arg 0)
     * 
     */

    @SuppressWarnings("unchecked")
    public void testTreeSetAdd() {
        TreeSet<Integer> set = new TreeSet();
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
     * Tests performance for the java.util.TreeSet method - first()
     * 
     */

    public void testTreeSetFirst() {
        int value;
        TreeSet<Integer> set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
            value = set.first();
        }
    }

    /**
     * 
     * Tests performance for the java.util.TreeSet method - last()
     * 
     */

    public void testTreeSetLast() {
        int value;
        TreeSet<Integer> set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
            value = set.last();
        }
    }

    /**
     * 
     * Tests performance of the java.util.TreeSet method- contains(Object arg0)
     * 
     */

    public void testTreeSetContains() {
        Integer index = new Integer(500);
        boolean flag;
        TreeSet<Integer> set = sSet;
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
     * Tests performance for the java.util.TreeSet method - size()
     * 
     */

    public void testTreeSetSize() {
        int value;
        TreeSet<Integer> set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
            value = set.size();
        }
    }

    /**
     * 
     * Tests performance for the java.util.TreeSet method - iterator()
     * 
     */

    public void testTreeSetIterator() {
        Iterator iterator;
        TreeSet<Integer> set = sSet;
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
     * Tests performance for the java.util.TreeSet method - comparator()
     * 
     */

    public void testTreeSetComparator() {
        Comparator comparator;
        TreeSet<Integer> set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
            comparator = set.comparator();
        }
    }

    /**
     * 
     * Tests performance for the java.util.TreeSet method - clone()
     * 
     */

    public void testTreeSetClone() {
        Object obj;
        TreeSet<Integer> set = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
            obj = set.clone();
        }
    }

    /**
     * 
     * Tests performance of the java.util.TreeSet method - remove(Object arg0)
     * 
     */

    @SuppressWarnings("unchecked")
    public void testTreeSetRemove() {
        TreeSet<Integer> set = new TreeSet(sSet);
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
     * Tests performance of the java.util.TreeSet method- headSet(Integer arg0)
     * 
     */

    public void testTreeSetHeadSet() {
        Integer value = new Integer(100);
        SortedSet set;
        TreeSet<Integer> tSet = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
            set = tSet.headSet(value);
        }
    }

    /**
     * 
     * Tests performance of subSet(Integer arg0, Integer arg1) - TreeSet
     * 
     */

    public void testTreeSetSubSet() {
        Integer value = new Integer(400);
        Integer nInt = new Integer(500);
        SortedSet set;
        TreeSet<Integer> tSet = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);
            set = tSet.subSet(value, nInt);

        }

    }

    /**
     * 
     * Tests performance of tailSet(Integer arg0) - TreeSet
     * 
     */

    public void testTreeSetTailSet() {
        Integer value = new Integer(900);
        SortedSet set;
        TreeSet<Integer> tSet = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
            set = tSet.tailSet(value);
        }
    }

    /**
     * 
     * Tests performance for the java.util.TreeSet method - isEmpty()
     * 
     */

    public void testTreeSetIsEmpty() {
        boolean flag;
        TreeSet<Integer> tSet = sSet;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
            flag = tSet.isEmpty();
        }
    }
}
