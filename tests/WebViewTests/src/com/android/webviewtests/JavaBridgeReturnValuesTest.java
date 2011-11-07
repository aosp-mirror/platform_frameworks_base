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
 * Part of the test suite for the WebView's Java Bridge. This test checks that
 * we correctly convert Java values to JavaScript values when returning them
 * from the methods of injected Java objects.
 *
 * The conversions should follow
 * http://jdk6.java.net/plugin2/liveconnect/#JS_JAVA_CONVERSIONS. Places in
 * which the implementation differs from the spec are marked with
 * LIVECONNECT_COMPLIANCE.
 * FIXME: Consider making our implementation more compliant, if it will not
 * break backwards-compatibility. See b/4408210.
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeReturnValuesTest \
 *     com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeReturnValuesTest extends JavaBridgeTestBase {
    // An instance of this class is injected into the page to test returning
    // Java values to JavaScript.
    private class TestObject extends Controller {
        private String mStringValue;
        private boolean mBooleanValue;

        // These four methods are used to control the test.
        public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }
        public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }
        public synchronized void setBooleanValue(boolean x) {
            mBooleanValue = x;
            notifyResultIsReady();
        }
        public synchronized boolean waitForBooleanValue() {
            waitForResult();
            return mBooleanValue;
        }

        public boolean getBooleanValue() {
            return true;
        }
        public byte getByteValue() {
            return 42;
        }
        public char getCharValue() {
            return '\u002A';
        }
        public short getShortValue() {
            return 42;
        }
        public int getIntValue() {
            return 42;
        }
        public long getLongValue() {
            return 42L;
        }
        public float getFloatValue() {
            return 42.1f;
        }
        public float getFloatValueNoDecimal() {
            return 42.0f;
        }
        public double getDoubleValue() {
            return 42.1;
        }
        public double getDoubleValueNoDecimal() {
            return 42.0;
        }
        public String getStringValue() {
            return "foo";
        }
        public String getEmptyStringValue() {
            return "";
        }
        public String getNullStringValue() {
            return null;
        }
        public Object getObjectValue() {
            return new Object();
        }
        public Object getNullObjectValue() {
            return null;
        }
        public CustomType getCustomTypeValue() {
            return new CustomType();
        }
        public void getVoidValue() {
        }
    }

    // A custom type used when testing passing objects.
    private class CustomType {
    }

    TestObject mTestObject;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestObject = new TestObject();
        setUpWebView(mTestObject, "testObject");
    }

    // Note that this requires that we can pass a JavaScript string to Java.
    protected String executeJavaScriptAndGetStringResult(String script) throws Throwable {
        executeJavaScript("testObject.setStringValue(" + script + ");");
        return mTestObject.waitForStringValue();
    }

    // Note that this requires that we can pass a JavaScript boolean to Java.
    private boolean executeJavaScriptAndGetBooleanResult(String script) throws Throwable {
        executeJavaScript("testObject.setBooleanValue(" + script + ");");
        return mTestObject.waitForBooleanValue();
    }

    public void testMethodReturnTypes() throws Throwable {
        assertEquals("boolean",
                executeJavaScriptAndGetStringResult("typeof testObject.getBooleanValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getByteValue()"));
        // char values are returned to JavaScript as numbers.
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getCharValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getShortValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getIntValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getLongValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getFloatValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getFloatValueNoDecimal()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getDoubleValue()"));
        assertEquals("number",
                executeJavaScriptAndGetStringResult("typeof testObject.getDoubleValueNoDecimal()"));
        assertEquals("string",
                executeJavaScriptAndGetStringResult("typeof testObject.getStringValue()"));
        assertEquals("string",
                executeJavaScriptAndGetStringResult("typeof testObject.getEmptyStringValue()"));
        // LIVECONNECT_COMPLIANCE: This should have type object.
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.getNullStringValue()"));
        assertEquals("object",
                executeJavaScriptAndGetStringResult("typeof testObject.getObjectValue()"));
        assertEquals("object",
                executeJavaScriptAndGetStringResult("typeof testObject.getNullObjectValue()"));
        assertEquals("object",
                executeJavaScriptAndGetStringResult("typeof testObject.getCustomTypeValue()"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.getVoidValue()"));
    }

    public void testMethodReturnValues() throws Throwable {
        // We do the string comparison in JavaScript, to avoid relying on the
        // coercion algorithm from JavaScript to Java.
        assertTrue(executeJavaScriptAndGetBooleanResult("testObject.getBooleanValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult("42 === testObject.getByteValue()"));
        // char values are returned to JavaScript as numbers.
        assertTrue(executeJavaScriptAndGetBooleanResult("42 === testObject.getCharValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult("42 === testObject.getShortValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult("42 === testObject.getIntValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult("42 === testObject.getLongValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult(
                "Math.abs(42.1 - testObject.getFloatValue()) < 0.001"));
        assertTrue(executeJavaScriptAndGetBooleanResult(
                "42.0 === testObject.getFloatValueNoDecimal()"));
        assertTrue(executeJavaScriptAndGetBooleanResult(
                "Math.abs(42.1 - testObject.getDoubleValue()) < 0.001"));
        assertTrue(executeJavaScriptAndGetBooleanResult(
                "42.0 === testObject.getDoubleValueNoDecimal()"));
        assertEquals("foo", executeJavaScriptAndGetStringResult("testObject.getStringValue()"));
        assertEquals("", executeJavaScriptAndGetStringResult("testObject.getEmptyStringValue()"));
        assertTrue(executeJavaScriptAndGetBooleanResult("undefined === testObject.getVoidValue()"));
    }
}
