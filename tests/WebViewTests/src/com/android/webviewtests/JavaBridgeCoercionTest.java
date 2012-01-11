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
 * Part of the test suite for the WebView's Java Bridge. This class tests that
 * we correctly convert JavaScript values to Java values when passing them to
 * the methods of injected Java objects.
 *
 * The conversions should follow
 * http://jdk6.java.net/plugin2/liveconnect/#JS_JAVA_CONVERSIONS. Places in
 * which the implementation differs from the spec are marked with
 * LIVECONNECT_COMPLIANCE.
 * FIXME: Consider making our implementation more compliant, if it will not
 * break backwards-compatibility. See b/4408210.
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeCoercionTest \
 *     com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeCoercionTest extends JavaBridgeTestBase {
    private class TestObject extends Controller {
        private Object objectInstance;
        private CustomType customTypeInstance;
        private CustomType2 customType2Instance;

        private boolean mBooleanValue;
        private byte mByteValue;
        private char mCharValue;
        private short mShortValue;
        private int mIntValue;
        private long mLongValue;
        private float mFloatValue;
        private double mDoubleValue;
        private String mStringValue;
        private Object mObjectValue;
        private CustomType mCustomTypeValue;

        public TestObject() {
            objectInstance = new Object();
            customTypeInstance = new CustomType();
            customType2Instance = new CustomType2();
        }

        public Object getObjectInstance() {
            return objectInstance;
        }
        public CustomType getCustomTypeInstance() {
            return customTypeInstance;
        }
        public CustomType2 getCustomType2Instance() {
            return customType2Instance;
        }

        public synchronized void setBooleanValue(boolean x) {
            mBooleanValue = x;
            notifyResultIsReady();
        }
        public synchronized void setByteValue(byte x) {
            mByteValue = x;
            notifyResultIsReady();
        }
        public synchronized void setCharValue(char x) {
            mCharValue = x;
            notifyResultIsReady();
        }
        public synchronized void setShortValue(short x) {
            mShortValue = x;
            notifyResultIsReady();
        }
        public synchronized void setIntValue(int x) {
            mIntValue = x;
            notifyResultIsReady();
        }
        public synchronized void setLongValue(long x) {
            mLongValue = x;
            notifyResultIsReady();
        }
        public synchronized void setFloatValue(float x) {
            mFloatValue = x;
            notifyResultIsReady();
        }
        public synchronized void setDoubleValue(double x) {
            mDoubleValue = x;
            notifyResultIsReady();
        }
        public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }
        public synchronized void setObjectValue(Object x) {
            mObjectValue = x;
            notifyResultIsReady();
        }
        public synchronized void setCustomTypeValue(CustomType x) {
            mCustomTypeValue = x;
            notifyResultIsReady();
        }

        public synchronized boolean waitForBooleanValue() {
            waitForResult();
            return mBooleanValue;
        }
        public synchronized byte waitForByteValue() {
            waitForResult();
            return mByteValue;
        }
        public synchronized char waitForCharValue() {
            waitForResult();
            return mCharValue;
        }
        public synchronized short waitForShortValue() {
            waitForResult();
            return mShortValue;
        }
        public synchronized int waitForIntValue() {
            waitForResult();
            return mIntValue;
        }
        public synchronized long waitForLongValue() {
            waitForResult();
            return mLongValue;
        }
        public synchronized float waitForFloatValue() {
            waitForResult();
            return mFloatValue;
        }
        public synchronized double waitForDoubleValue() {
            waitForResult();
            return mDoubleValue;
        }
        public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }
        public synchronized Object waitForObjectValue() {
            waitForResult();
            return mObjectValue;
        }
        public synchronized CustomType waitForCustomTypeValue() {
            waitForResult();
            return mCustomTypeValue;
        }
    }

    // Two custom types used when testing passing objects.
    private static class CustomType {
    }
    private static class CustomType2 {
    }

    private TestObject mTestObject;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestObject = new TestObject();
        setUpWebView(mTestObject, "testObject");
    }

    // Test passing a JavaScript number in the int32 range to a method of an
    // injected object.
    public void testPassNumberInt32() throws Throwable {
        executeJavaScript("testObject.setByteValue(42);");
        assertEquals(42, mTestObject.waitForByteValue());
        executeJavaScript("testObject.setByteValue(" + Byte.MAX_VALUE + " + 42);");
        assertEquals(Byte.MIN_VALUE + 42 - 1, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should convert to numeric char value.
        executeJavaScript("testObject.setCharValue(42);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(42);");
        assertEquals(42, mTestObject.waitForShortValue());
        executeJavaScript("testObject.setShortValue(" + Short.MAX_VALUE + " + 42);");
        assertEquals(Short.MIN_VALUE + 42 - 1, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(42);");
        assertEquals(42, mTestObject.waitForIntValue());

        executeJavaScript("testObject.setLongValue(42);");
        assertEquals(42L, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(42);");
        assertEquals(42.0f, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(42);");
        assertEquals(42.0, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.Number.
        executeJavaScript("testObject.setObjectValue(42);");
        assertNull(mTestObject.waitForObjectValue());

        // The spec allows the JS engine flexibility in how to format the number.
        executeJavaScript("testObject.setStringValue(42);");
        String str = mTestObject.waitForStringValue();
        assertTrue("42".equals(str) || "42.0".equals(str));

        executeJavaScript("testObject.setBooleanValue(0);");
        assertFalse(mTestObject.waitForBooleanValue());
        // LIVECONNECT_COMPLIANCE: Should be true;
        executeJavaScript("testObject.setBooleanValue(42);");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(42);");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing a JavaScript number in the double range to a method of an
    // injected object.
    public void testPassNumberDouble() throws Throwable {
        executeJavaScript("testObject.setByteValue(42.1);");
        assertEquals(42, mTestObject.waitForByteValue());
        executeJavaScript("testObject.setByteValue(" + Byte.MAX_VALUE + " + 42.1);");
        assertEquals(Byte.MIN_VALUE + 42 - 1, mTestObject.waitForByteValue());
        executeJavaScript("testObject.setByteValue(" + Integer.MAX_VALUE + " + 42.1);");
        assertEquals(-1, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should convert to numeric char value.
        executeJavaScript("testObject.setCharValue(42.1);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(42.1);");
        assertEquals(42, mTestObject.waitForShortValue());
        executeJavaScript("testObject.setShortValue(" + Short.MAX_VALUE + " + 42.1);");
        assertEquals(Short.MIN_VALUE + 42 - 1, mTestObject.waitForShortValue());
        executeJavaScript("testObject.setShortValue(" + Integer.MAX_VALUE + " + 42.1);");
        assertEquals(-1, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(42.1);");
        assertEquals(42, mTestObject.waitForIntValue());
        executeJavaScript("testObject.setIntValue(" + Integer.MAX_VALUE + " + 42.1);");
        assertEquals(Integer.MAX_VALUE, mTestObject.waitForIntValue());

        executeJavaScript("testObject.setLongValue(42.1);");
        assertEquals(42L, mTestObject.waitForLongValue());
        // LIVECONNECT_COMPLIANCE: Should be Long.MAX_VALUE.
        executeJavaScript("testObject.setLongValue(" + Long.MAX_VALUE + " + 42.1);");
        assertEquals(Long.MIN_VALUE, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(42.1);");
        assertEquals(42.1f, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(42.1);");
        assertEquals(42.1, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.Number.
        executeJavaScript("testObject.setObjectValue(42.1);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setStringValue(42.1);");
        assertEquals("42.1", mTestObject.waitForStringValue());

        executeJavaScript("testObject.setBooleanValue(0.0);");
        assertFalse(mTestObject.waitForBooleanValue());
        // LIVECONNECT_COMPLIANCE: Should be true.
        executeJavaScript("testObject.setBooleanValue(42.1);");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(42.1);");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing JavaScript NaN to a method of an injected object.
    public void testPassNumberNaN() throws Throwable {
        executeJavaScript("testObject.setByteValue(Number.NaN);");
        assertEquals(0, mTestObject.waitForByteValue());

        executeJavaScript("testObject.setCharValue(Number.NaN);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(Number.NaN);");
        assertEquals(0, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(Number.NaN);");
        assertEquals(0, mTestObject.waitForIntValue());

        executeJavaScript("testObject.setLongValue(Number.NaN);");
        assertEquals(0L, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(Number.NaN);");
        assertEquals(Float.NaN, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(Number.NaN);");
        assertEquals(Double.NaN, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.Number.
        executeJavaScript("testObject.setObjectValue(Number.NaN);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setStringValue(Number.NaN);");
        assertEquals("NaN", mTestObject.waitForStringValue());

        executeJavaScript("testObject.setBooleanValue(Number.NaN);");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(Number.NaN);");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing JavaScript infinity to a method of an injected object.
    public void testPassNumberInfinity() throws Throwable {
        executeJavaScript("testObject.setByteValue(Infinity);");
        assertEquals(-1, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should convert to maximum numeric char value.
        executeJavaScript("testObject.setCharValue(Infinity);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(Infinity);");
        assertEquals(-1, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(Infinity);");
        assertEquals(Integer.MAX_VALUE, mTestObject.waitForIntValue());

        // LIVECONNECT_COMPLIANCE: Should be Long.MAX_VALUE.
        executeJavaScript("testObject.setLongValue(Infinity);");
        assertEquals(-1L, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(Infinity);");
        assertEquals(Float.POSITIVE_INFINITY, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(Infinity);");
        assertEquals(Double.POSITIVE_INFINITY, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.Number.
        executeJavaScript("testObject.setObjectValue(Infinity);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setStringValue(Infinity);");
        assertEquals("Inf", mTestObject.waitForStringValue());

        executeJavaScript("testObject.setBooleanValue(Infinity);");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(Infinity);");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing a JavaScript boolean to a method of an injected object.
    public void testPassBoolean() throws Throwable {
        executeJavaScript("testObject.setBooleanValue(true);");
        assertTrue(mTestObject.waitForBooleanValue());
        executeJavaScript("testObject.setBooleanValue(false);");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.Boolean.
        executeJavaScript("testObject.setObjectValue(true);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setStringValue(false);");
        assertEquals("false", mTestObject.waitForStringValue());
        executeJavaScript("testObject.setStringValue(true);");
        assertEquals("true", mTestObject.waitForStringValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.
        executeJavaScript("testObject.setByteValue(true);");
        assertEquals(0, mTestObject.waitForByteValue());
        executeJavaScript("testObject.setByteValue(false);");
        assertEquals(0, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should convert to numeric char value 1.
        executeJavaScript("testObject.setCharValue(true);");
        assertEquals('\u0000', mTestObject.waitForCharValue());
        executeJavaScript("testObject.setCharValue(false);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.
        executeJavaScript("testObject.setShortValue(true);");
        assertEquals(0, mTestObject.waitForShortValue());
        executeJavaScript("testObject.setShortValue(false);");
        assertEquals(0, mTestObject.waitForShortValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.
        executeJavaScript("testObject.setIntValue(true);");
        assertEquals(0, mTestObject.waitForIntValue());
        executeJavaScript("testObject.setIntValue(false);");
        assertEquals(0, mTestObject.waitForIntValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.
        executeJavaScript("testObject.setLongValue(true);");
        assertEquals(0L, mTestObject.waitForLongValue());
        executeJavaScript("testObject.setLongValue(false);");
        assertEquals(0L, mTestObject.waitForLongValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.0.
        executeJavaScript("testObject.setFloatValue(true);");
        assertEquals(0.0f, mTestObject.waitForFloatValue());
        executeJavaScript("testObject.setFloatValue(false);");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        // LIVECONNECT_COMPLIANCE: Should be 1.0.
        executeJavaScript("testObject.setDoubleValue(true);");
        assertEquals(0.0, mTestObject.waitForDoubleValue());
        executeJavaScript("testObject.setDoubleValue(false);");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(true);");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing a JavaScript string to a method of an injected object.
    public void testPassString() throws Throwable {
        executeJavaScript("testObject.setStringValue(\"+042.10\");");
        assertEquals("+042.10", mTestObject.waitForStringValue());

        // Make sure that we distinguish between the empty string and NULL.
        executeJavaScript("testObject.setStringValue(\"\");");
        assertEquals("", mTestObject.waitForStringValue());

        // LIVECONNECT_COMPLIANCE: Should create an instance of java.lang.String.
        executeJavaScript("testObject.setObjectValue(\"+042.10\");");
        assertNull(mTestObject.waitForObjectValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setByteValue(\"+042.10\");");
        assertEquals(0, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setShortValue(\"+042.10\");");
        assertEquals(0, mTestObject.waitForShortValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setIntValue(\"+042.10\");");
        assertEquals(0, mTestObject.waitForIntValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setLongValue(\"+042.10\");");
        assertEquals(0L, mTestObject.waitForLongValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setFloatValue(\"+042.10\");");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        // LIVECONNECT_COMPLIANCE: Should use valueOf() of appropriate type.
        executeJavaScript("testObject.setDoubleValue(\"+042.10\");");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should decode and convert to numeric char value.
        executeJavaScript("testObject.setCharValue(\"+042.10\");");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        // LIVECONNECT_COMPLIANCE: Non-empty string should convert to true.
        executeJavaScript("testObject.setBooleanValue(\"+042.10\");");
        assertFalse(mTestObject.waitForBooleanValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue(\"+042.10\");");
        assertNull(mTestObject.waitForCustomTypeValue());
    }

    // Test passing a JavaScript object to a method of an injected object.
    public void testPassJavaScriptObject() throws Throwable {
        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setObjectValue({foo: 42});");
        assertNull(mTestObject.waitForObjectValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCustomTypeValue({foo: 42});");
        assertNull(mTestObject.waitForCustomTypeValue());

        // LIVECONNECT_COMPLIANCE: Should call toString() on object.
        executeJavaScript("testObject.setStringValue({foo: 42});");
        assertEquals("undefined", mTestObject.waitForStringValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setByteValue({foo: 42});");
        assertEquals(0, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCharValue({foo: 42});");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setShortValue({foo: 42});");
        assertEquals(0, mTestObject.waitForShortValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setIntValue({foo: 42});");
        assertEquals(0, mTestObject.waitForIntValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setLongValue({foo: 42});");
        assertEquals(0L, mTestObject.waitForLongValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setFloatValue({foo: 42});");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setDoubleValue({foo: 42});");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setBooleanValue({foo: 42});");
        assertFalse(mTestObject.waitForBooleanValue());
    }

    // Test passing a Java object to a method of an injected object. Note that
    // this test requires being able to return objects from the methods of
    // injected objects. This is tested elsewhere.
    public void testPassJavaObject() throws Throwable {
        executeJavaScript("testObject.setObjectValue(testObject.getObjectInstance());");
        assertTrue(mTestObject.getObjectInstance() == mTestObject.waitForObjectValue());
        executeJavaScript("testObject.setObjectValue(testObject.getCustomTypeInstance());");
        assertTrue(mTestObject.getCustomTypeInstance() == mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setCustomTypeValue(testObject.getObjectInstance());");
        assertTrue(mTestObject.getObjectInstance() == mTestObject.waitForCustomTypeValue());
        executeJavaScript("testObject.setCustomTypeValue(testObject.getCustomTypeInstance());");
        assertTrue(mTestObject.getCustomTypeInstance() == mTestObject.waitForCustomTypeValue());
        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception, as the types are unrelated.
        executeJavaScript("testObject.setCustomTypeValue(testObject.getCustomType2Instance());");
        assertTrue(mTestObject.getCustomType2Instance() ==
                (Object)mTestObject.waitForCustomTypeValue());

        // LIVECONNECT_COMPLIANCE: Should call toString() on object.
        executeJavaScript("testObject.setStringValue(testObject.getObjectInstance());");
        assertEquals("undefined", mTestObject.waitForStringValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setByteValue(testObject.getObjectInstance());");
        assertEquals(0, mTestObject.waitForByteValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setCharValue(testObject.getObjectInstance());");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setShortValue(testObject.getObjectInstance());");
        assertEquals(0, mTestObject.waitForShortValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setIntValue(testObject.getObjectInstance());");
        assertEquals(0, mTestObject.waitForIntValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setLongValue(testObject.getObjectInstance());");
        assertEquals(0L, mTestObject.waitForLongValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setFloatValue(testObject.getObjectInstance());");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setDoubleValue(testObject.getObjectInstance());");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        // LIVECONNECT_COMPLIANCE: Should raise a JavaScript exception.
        executeJavaScript("testObject.setBooleanValue(testObject.getObjectInstance());");
        assertFalse(mTestObject.waitForBooleanValue());
    }

    // Test passing JavaScript null to a method of an injected object.
    public void testPassNull() throws Throwable {
        executeJavaScript("testObject.setObjectValue(null);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setCustomTypeValue(null);");
        assertNull(mTestObject.waitForCustomTypeValue());

        executeJavaScript("testObject.setStringValue(null);");
        assertNull(mTestObject.waitForStringValue());

        executeJavaScript("testObject.setByteValue(null);");
        assertEquals(0, mTestObject.waitForByteValue());

        executeJavaScript("testObject.setCharValue(null);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(null);");
        assertEquals(0, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(null);");
        assertEquals(0, mTestObject.waitForIntValue());

        executeJavaScript("testObject.setLongValue(null);");
        assertEquals(0L, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(null);");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(null);");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        executeJavaScript("testObject.setBooleanValue(null);");
        assertFalse(mTestObject.waitForBooleanValue());
    }

    // Test passing JavaScript undefined to a method of an injected object.
    public void testPassUndefined() throws Throwable {
        executeJavaScript("testObject.setObjectValue(undefined);");
        assertNull(mTestObject.waitForObjectValue());

        executeJavaScript("testObject.setCustomTypeValue(undefined);");
        assertNull(mTestObject.waitForCustomTypeValue());

        // LIVECONNECT_COMPLIANCE: Should be NULL.
        executeJavaScript("testObject.setStringValue(undefined);");
        assertEquals("undefined", mTestObject.waitForStringValue());

        executeJavaScript("testObject.setByteValue(undefined);");
        assertEquals(0, mTestObject.waitForByteValue());

        executeJavaScript("testObject.setCharValue(undefined);");
        assertEquals('\u0000', mTestObject.waitForCharValue());

        executeJavaScript("testObject.setShortValue(undefined);");
        assertEquals(0, mTestObject.waitForShortValue());

        executeJavaScript("testObject.setIntValue(undefined);");
        assertEquals(0, mTestObject.waitForIntValue());

        executeJavaScript("testObject.setLongValue(undefined);");
        assertEquals(0L, mTestObject.waitForLongValue());

        executeJavaScript("testObject.setFloatValue(undefined);");
        assertEquals(0.0f, mTestObject.waitForFloatValue());

        executeJavaScript("testObject.setDoubleValue(undefined);");
        assertEquals(0.0, mTestObject.waitForDoubleValue());

        executeJavaScript("testObject.setBooleanValue(undefined);");
        assertFalse(mTestObject.waitForBooleanValue());
    }
}
