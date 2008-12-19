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

import java.lang.reflect.Array;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Test java.lang.reflect.Array methods.
 */
public class ReflectArrayTest extends TestCase {

    @SmallTest
    public void testSingleInt() throws Exception {
        Object intArray = Array.newInstance(Integer.TYPE, 2);

        int[] array = (int[]) intArray;
        array[0] = 5;
        Array.setInt(intArray, 1, 6);

        assertEquals(5, Array.getInt(intArray, 0));
        assertEquals(6, array[1]);

        try {
            array[2] = 27;
            fail("store should have failed");
        } catch (ArrayIndexOutOfBoundsException abe) {
            // expected
        }

        assertEquals(2, array.length);
        assertEquals(Array.getLength(intArray), array.length);

        try {
            int[][] wrongArray = (int[][]) intArray;
            fail("cast should have failed");
        } catch (ClassCastException cce) {
            // expected
        }

        intArray = Array.newInstance(Integer.TYPE, 0);
        assertEquals(0, Array.getLength(intArray));
    }

    @SmallTest
    public void testSingle() throws Exception {
        Object strArray = Array.newInstance(String.class, 2);

        String[] array = (String[]) strArray;
        array[0] = "entry zero";
        Array.set(strArray, 1, "entry one");

        //System.out.println("array: " + array);

        assertEquals("entry zero", Array.get(strArray, 0));
        assertEquals("entry one", array[1]);

        assertEquals(2, array.length);
        assertEquals(Array.getLength(strArray), array.length);
    }

    @SmallTest
    public void testMultiInt() throws Exception {
        int[] dimensions = {3, 2, 1};
        Object intIntIntArray = Array.newInstance(Integer.TYPE, dimensions);
        int[][][] array3 = (int[][][]) intIntIntArray;

        array3[0][0][0] = 123;
        array3[2][1][0] = 456;

        try {
            array3[2][1][1] = 768;
            fail("store should have failed");
        } catch (ArrayIndexOutOfBoundsException abe) {
            // expected
        }

        //System.out.println("array3: " + array3);
    }

    @SmallTest
    public void testMulti() throws Exception {
        int[] dimensions = {1, 2, 3};
        Object strStrStrArray = Array.newInstance(String.class, dimensions);
        String[][][] array3 = (String[][][]) strStrStrArray;

        array3[0][0][0] = "zero zero zero";
        array3[0][1][2] = "zero one two";

        try {
            array3[1][0][0] = "bad store";
            fail("store should have failed");
        } catch (ArrayIndexOutOfBoundsException abe) {
            // expected
        }

        try {
            String[][] array2 = (String[][]) strStrStrArray;
            fail("expecting bad cast");
        } catch (ClassCastException cce) {
            // expected
        }
        //System.out.println("array3: " + array3);


        int[] dimensions2 = {1, 2};
        strStrStrArray = Array.newInstance(String[].class, dimensions2);
        array3 = (String[][][]) strStrStrArray;
        array3[0][1] = new String[3];
        array3[0][1][2] = "zero one two";
        try {
            array3[1][0][0] = "bad store";
            fail("store should have failed");
        } catch (ArrayIndexOutOfBoundsException abe) {
            // expected
        }
        //System.out.println("array3: " + array3);
    }
}

