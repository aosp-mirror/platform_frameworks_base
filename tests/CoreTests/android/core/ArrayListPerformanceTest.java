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

import java.util.ArrayList;
import android.test.PerformanceTestBase;

public class ArrayListPerformanceTest extends PerformanceTestBase {

    private ArrayList<Integer> mList;

    @Override
    @SuppressWarnings("unchecked")
    protected void setUp() throws Exception {
        super.setUp();

        mList = new ArrayList();
        mList.add(0);
        mList.add(1);
        mList.add(2);
        mList.add(3);
        mList.add(4);
        mList.add(5);
        mList.add(6);
        mList.add(7);
        mList.add(8);
        mList.add(9);
    }

    public void testArrayListAdd() {
        int i = 0;
        for (; i < 10; i++) {
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
            mList.add(i);
        }
    }

    public void testArrayListAdd1() {
        int i = 0;
        for (; i < 10; i++) {
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
            mList.add(7, i);
        }
    }

    public void testArrayListToArray() {
        Object rArray;
        int i = 0;
        for (; i < 100; i++) {
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
            rArray = mList.toArray();
        }
    }

    public void testArrayListSize() {
        int i = 0, len;
        for (; i < 100; i++) {
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
            len = mList.size();
        }
    }

    public void testArrayListGet() {
        int i = 0, value;
        int len = mList.size();
        for (; i < len; i++) {
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
            value = mList.get(i);
        }
    }

    public void testArrayListContains() {
        boolean flag;
        int i = 0;

        for (; i < 100; i++) {
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);
            flag = mList.contains(i);

        }
    }

    public void testArrayListToArray1() {
        Integer[] rArray = new Integer[10];

        Integer[] mArray;
        int i = 0;
        for (; i < 100; i++) {
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
            mArray = mList.toArray(rArray);
        }
    }

    public void testArrayListSet() {
        int i = 0;
        for (; i < 10; i++) {
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
            mList.set(5, 0);
        }
    }

    public void testArrayListIndexOf() {
        int i = 0, index;

        for (; i < 100; i++) {
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
            index = mList.indexOf(0);
        }
    }

    public void testArrayListLastIndexOf() {
        int i = 0, index;

        for (; i < 100; i++) {
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
            index = mList.lastIndexOf(0);
        }
    }

    @SuppressWarnings("unchecked")
    public void testArrayListRemove() {
        ArrayList<Integer> aList;
        aList = new ArrayList();
        for (int j = 0; j < 10000; j++) {
            aList.add(0);
        }

        int i = 0, index;

        for (; i < 10; i++) {
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);
            index = aList.remove(0);


        }
    }

    @SuppressWarnings("unchecked")
    public void testArrayListAddAll() {
        ArrayList<Integer> aList = new ArrayList();

        int i = 0;
        boolean b;
        for (; i < 10; i++) {
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);
            b = aList.addAll(mList);

        }
    }

    @SuppressWarnings("unchecked")
    public void testArrayListRemove1() {
        ArrayList<String> aList;
        String s;

        aList = new ArrayList();
        for (int j = 0; j < 100; j++) {
            aList.add("a");
            aList.add("b");
        }
        s = new String("a");

        int i = 0;
        boolean b;
        for (; i < 10; i++) {
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
            b = aList.remove(s);
        }
    }

    @SuppressWarnings("unchecked")
    public void testArrayListAddAll1() {
        ArrayList<Integer> aList = new ArrayList();

        int i = 0;
        boolean b;

        for (; i < 10; i++) {
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
            b = aList.addAll(0, mList);
        }
    }

    public void testArrayListClone() {
        Object rObj;
        int i = 0;

        for (; i < 100; i++) {
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
            rObj = mList.clone();
        }
    }
}
