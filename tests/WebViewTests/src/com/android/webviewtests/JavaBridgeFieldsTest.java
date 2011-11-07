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
 * Part of the test suite for the WebView's Java Bridge. This test tests the
 * use of fields.
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeFieldsTest \
 *     com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeFieldsTest extends JavaBridgeTestBase {
    private class TestObject extends Controller {
        private String mStringValue;

        // These methods are used to control the test.
        public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }
        public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }

        public boolean booleanField = true;
        public byte byteField = 42;
        public char charField = '\u002A';
        public short shortField = 42;
        public int intField = 42;
        public long longField = 42L;
        public float floatField = 42.0f;
        public double doubleField = 42.0;
        public String stringField = "foo";
        public Object objectField = new Object();
        public CustomType customTypeField = new CustomType();
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

    // The Java bridge does not provide access to fields.
    // FIXME: Consider providing support for this. See See b/4408210.
    public void testFieldTypes() throws Throwable {
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.booleanField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.byteField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.charField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.shortField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.intField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.longField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.floatField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.doubleField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.objectField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.stringField"));
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.customTypeField"));
    }
}
