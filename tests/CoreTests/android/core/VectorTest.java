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

import java.util.Vector;
import java.util.Enumeration;

/**
 * Basic Performance Tests for java.util.Vector
 */

@SuppressWarnings("unchecked")
public class VectorTest extends PerformanceTestBase {
    public static final int ITERATIONS = 1000;
    private Vector<Integer> mVector;
    private Vector<String> mStrVector;
    private String mTestString = "Hello Android";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mVector = new Vector();
        mStrVector = new Vector();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            assertTrue(mVector.add(i));
            assertTrue(mStrVector.add(Integer.toString(i)));
        }
    }

    @Override
    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        intermediates.setInternalIterations(ITERATIONS);
        return 0;
    }

    public void testVectorAdd() {
        Vector<Integer> vector = new Vector();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
            vector.add(i);
        }
    }

    public void testVectorAdd1() {
        Vector<Integer> vector = new Vector();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
            vector.add(0, i);
        }
    }

    public void testVectorToArray() {
        Object array;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
            array = vector.toArray();
        }
    }

    /**
     * 
     */
    public void testVectorSize() {
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            int mLen;
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
            mLen = vector.size();
        }
    }

    public void testVectorGet() {
        int element;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
            element = vector.get(i);
        }

    }

    public void testVectorContains() {
        boolean flag;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
            flag = vector.contains(i);
        }
    }

    public void testVectorToArray1() {
        Integer[] rArray = new Integer[100];
        Integer[] array;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
            array = vector.toArray(rArray);
        }
    }

    public void testVectorSet() {
        Vector<Integer> vector = mVector;
        int pos = 5, value = 0;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
            vector.set(pos, value);
        }
    }

    public void testVectorIndexOf() {
        int index, value = 0;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
            index = vector.indexOf(value);
        }
    }

    public void testVectorLastIndexOf() {
        int index, value = 0;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
            index = vector.lastIndexOf(value);
        }
    }

    public void testVectorRemove() {
        int index, value = 0;
        Vector<Integer> vector = new Vector(mVector);
        for (int i = 10; i > 0; i--) {
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
            index = vector.remove(value);
        }
    }

    public void testVectorRemoveElement() {
        Vector<Integer> vector = new Vector(mVector);
        for (int i = 10; i > 0; i--) {
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
            vector.removeElement(i);
        }
    }

    public void VectorRemoveElementAt() {
        Vector<Integer> vector = new Vector(mVector);
        for (int i = 10; i > 0; i--) {
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
            vector.removeElementAt(i);
        }
    }

    public void VectorAddAll() {
        Vector<Integer> vector = new Vector(), vector1 = mVector;

        boolean flag;
        for (int i = 10; i > 0; i--) {
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
            flag = vector.addAll(vector1);
        }
    }

    public void VectorRemove1() {
        Vector<String> vector = mStrVector;
        for (int j = 1000; j > 0; j--) {
            vector.add("a");
            vector.add("b");
        }
        String s = new String("a");
        boolean flag;
        for (int i = 10; i > 0; i--) {
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
            flag = vector.remove(s);
        }
    }

    public void testVectorAddAll1() {
        Vector<Integer> mEmptyVector = new Vector();
        boolean flag;
        int pos = 0;
        Vector<Integer> vector1 = mVector;
        Vector<Integer> vector = mEmptyVector;
        for (int i = 10; i > 0; i--) {
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
            flag = vector.addAll(pos, vector1);
        }
    }

    public void testVectorClone() {
        Object obj;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
            obj = vector.clone();
        }
    }

    public void testVectorCapacity() {
        int capacity;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
            capacity = vector.capacity();
        }
    }

    public void testVectorHashcode() {
        int element;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
            element = vector.hashCode();
        }
    }

    public void testVectorElements() {
        Enumeration<Integer> elements;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
            elements = vector.elements();
        }
    }

    public void testVectorToString() {
        String str;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
            str = vector.toString();
        }
    }

    public void testVectorElementAt() {
        int element;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
            element = vector.elementAt(50);
        }
    }

    public void testVectorAddElement() {
        int element;
        Vector<String> vector = mStrVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
            vector.addElement(mTestString);
        }
    }

    public void testVectorFirstElement() {
        int element;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
            element = vector.firstElement();
        }
    }

    public void testVectorLastElement() {
        int element;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
            element = vector.lastElement();
        }
    }

    public void testVectorSetElementAt() {
        Vector<Integer> vector = mVector;
        int value1 = 500, value2 = 50;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
            vector.setElementAt(value1, value2);
        }
    }

    public void testVectorIsEmpty() {
        boolean flag;
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
            flag = vector.isEmpty();
        }
    }

    public void testVectorCopyInto() {
        Integer[] rArray = new Integer[ITERATIONS];
        Vector<Integer> vector = mVector;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
            vector.copyInto(rArray);
        }
    }

    public void testVectorInsertElementAt() {
        Vector<String> vector = mStrVector;
        String string = mTestString;
        for (int i = ITERATIONS - 1; i > 0; i--) {
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
            vector.insertElementAt(string, i);
        }
    }
}
