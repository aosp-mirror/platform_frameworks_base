/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * Part of the test suite for the WebView's Java Bridge. This class tests the
 * general use of arrays.
 *
 * The conversions should follow
 * http://jdk6.java.net/plugin2/liveconnect/#JS_JAVA_CONVERSIONS. Places in
 * which the implementation differs from the spec are marked with
 * LIVECONNECT_COMPLIANCE.
 * FIXME: Consider making our implementation more compliant, if it will not
 * break backwards-compatibility. See b/4408210.
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeArrayTest \
 *     com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeArrayTest extends JavaBridgeTestBase {
    private class TestObject extends Controller {
        private boolean mBooleanValue;
        private int mIntValue;
        private String mStringValue;

        private int[] mIntArray;
        private int[][] mIntIntArray;

        private boolean mWasArrayMethodCalled;

        public synchronized void setBooleanValue(boolean x) {
            mBooleanValue = x;
            notifyResultIsReady();
        }
        public synchronized void setIntValue(int x) {
            mIntValue = x;
            notifyResultIsReady();
        }
        public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }

        public synchronized boolean waitForBooleanValue() {
            waitForResult();
            return mBooleanValue;
        }
        public synchronized int waitForIntValue() {
            waitForResult();
            return mIntValue;
        }
        public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }

        public synchronized void setIntArray(int[] x) {
            mIntArray = x;
            notifyResultIsReady();
        }
        public synchronized void setIntIntArray(int[][] x) {
            mIntIntArray = x;
            notifyResultIsReady();
        }

        public synchronized int[] waitForIntArray() {
            waitForResult();
            return mIntArray;
        }
        public synchronized int[][] waitForIntIntArray() {
            waitForResult();
            return mIntIntArray;
        }

        public synchronized int[] arrayMethod() {
            mWasArrayMethodCalled = true;
            return new int[] {42, 43, 44};
        }

        public synchronized boolean wasArrayMethodCalled() {
            return mWasArrayMethodCalled;
        }
    }

    private TestObject mTestObject;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestObject = new TestObject();
        setUpWebView(mTestObject, "testObject");
    }

    public void testArrayLength() throws Throwable {
        executeJavaScript("testObject.setIntArray([42, 43, 44]);");
        int[] result = mTestObject.waitForIntArray();
        assertEquals(3, result.length);
        assertEquals(42, result[0]);
        assertEquals(43, result[1]);
        assertEquals(44, result[2]);
    }

    public void testPassNull() throws Throwable {
        executeJavaScript("testObject.setIntArray(null);");
        assertNull(mTestObject.waitForIntArray());
    }

    public void testPassUndefined() throws Throwable {
        executeJavaScript("testObject.setIntArray(undefined);");
        assertNull(mTestObject.waitForIntArray());
    }

    public void testPassEmptyArray() throws Throwable {
        executeJavaScript("testObject.setIntArray([]);");
        assertEquals(0, mTestObject.waitForIntArray().length);
    }

    // Note that this requires being able to pass a string from JavaScript to
    // Java.
    public void testPassArrayToStringMethod() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should call toString() on array.
        executeJavaScript("testObject.setStringValue([42, 42, 42]);");
        assertEquals("undefined", mTestObject.waitForStringValue());
    }

    // Note that this requires being able to pass an integer from JavaScript to
    // Java.
    public void testPassArrayToNonStringNonArrayMethod() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should raise JavaScript exception.
        executeJavaScript("testObject.setIntValue([42, 42, 42]);");
        assertEquals(0, mTestObject.waitForIntValue());
    }

    public void testPassNonArrayToArrayMethod() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should raise JavaScript exception.
        executeJavaScript("testObject.setIntArray(42);");
        assertNull(mTestObject.waitForIntArray());
    }

    public void testObjectWithLengthProperty() throws Throwable {
        executeJavaScript("testObject.setIntArray({length: 3, 1: 42});");
        int[] result = mTestObject.waitForIntArray();
        assertEquals(3, result.length);
        assertEquals(0, result[0]);
        assertEquals(42, result[1]);
        assertEquals(0, result[2]);
    }

    public void testNonNumericLengthProperty() throws Throwable {
        // LIVECONNECT_COMPLIANCE: This should not count as an array, so we
        // should raise a JavaScript exception.
        executeJavaScript("testObject.setIntArray({length: \"foo\"});");
        assertNull(mTestObject.waitForIntArray());
    }

    public void testLengthOutOfBounds() throws Throwable {
        // LIVECONNECT_COMPLIANCE: This should not count as an array, so we
        // should raise a JavaScript exception.
        executeJavaScript("testObject.setIntArray({length: -1});");
        assertNull(mTestObject.waitForIntArray());

        // LIVECONNECT_COMPLIANCE: This should not count as an array, so we
        // should raise a JavaScript exception.
        long length = (long)Integer.MAX_VALUE + 1L;
        executeJavaScript("testObject.setIntArray({length: " + length + "});");
        assertNull(mTestObject.waitForIntArray());

        // LIVECONNECT_COMPLIANCE: This should not count as an array, so we
        // should raise a JavaScript exception.
        length = (long)Integer.MAX_VALUE + 1L - (long)Integer.MIN_VALUE + 1L;
        executeJavaScript("testObject.setIntArray({length: " + length + "});");
        assertNull(mTestObject.waitForIntArray());
    }

    public void testSparseArray() throws Throwable {
        executeJavaScript("var x = [42, 43]; x[3] = 45; testObject.setIntArray(x);");
        int[] result = mTestObject.waitForIntArray();
        assertEquals(4, result.length);
        assertEquals(42, result[0]);
        assertEquals(43, result[1]);
        assertEquals(0, result[2]);
        assertEquals(45, result[3]);
    }

    // Note that this requires being able to pass a boolean from JavaScript to
    // Java.
    public void testMethodReturningArrayNotCalled() throws Throwable {
        // We don't invoke methods which return arrays, but note that no
        // exception is raised.
        // LIVECONNECT_COMPLIANCE: Should call method and convert result to
        // JavaScript array.
        executeJavaScript("testObject.setBooleanValue(undefined === testObject.arrayMethod())");
        assertTrue(mTestObject.waitForBooleanValue());
        assertFalse(mTestObject.wasArrayMethodCalled());
    }

    public void testMultiDimensionalArrayMethod() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should handle multi-dimensional arrays.
        executeJavaScript("testObject.setIntIntArray([ [42, 43], [44, 45] ]);");
        assertNull(mTestObject.waitForIntIntArray());
    }

    public void testPassMultiDimensionalArray() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should handle multi-dimensional arrays.
        executeJavaScript("testObject.setIntArray([ [42, 43], [44, 45] ]);");
        int[] result = mTestObject.waitForIntArray();
        assertEquals(2, result.length);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
    }
}
