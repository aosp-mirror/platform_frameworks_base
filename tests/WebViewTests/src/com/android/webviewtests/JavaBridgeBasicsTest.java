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
 * Part of the test suite for the WebView's Java Bridge. Tests a number of features including ...
 * - The type of injected objects
 * - The type of their methods
 * - Replacing objects
 * - Removing objects
 * - Access control
 * - Calling methods on returned objects
 * - Multiply injected objects
 * - Threading
 * - Inheritance
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeBasicsTest \
 *     com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeBasicsTest extends JavaBridgeTestBase {
    private class TestController extends Controller {
        private int mIntValue;
        private long mLongValue;
        private String mStringValue;
        private boolean mBooleanValue;

        public synchronized void setIntValue(int x) {
            mIntValue = x;
            notifyResultIsReady();
        }
        public synchronized void setLongValue(long x) {
            mLongValue = x;
            notifyResultIsReady();
        }
        public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }
        public synchronized void setBooleanValue(boolean x) {
            mBooleanValue = x;
            notifyResultIsReady();
        }

        public synchronized int waitForIntValue() {
            waitForResult();
            return mIntValue;
        }
        public synchronized long waitForLongValue() {
            waitForResult();
            return mLongValue;
        }
        public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }
        public synchronized boolean waitForBooleanValue() {
            waitForResult();
            return mBooleanValue;
        }
    }

    private static class ObjectWithStaticMethod {
        public static String staticMethod() {
            return "foo";
        }
    }

    TestController mTestController;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestController = new TestController();
        setUpWebView(mTestController, "testController");
    }

    // Note that this requires that we can pass a JavaScript string to Java.
    protected String executeJavaScriptAndGetStringResult(String script) throws Throwable {
        executeJavaScript("testController.setStringValue(" + script + ");");
        return mTestController.waitForStringValue();
    }

    protected void injectObjectAndReload(final Object object, final String name) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().addJavascriptInterface(object, name);
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
    }

    // Note that this requires that we can pass a JavaScript boolean to Java.
    private void assertRaisesException(String script) throws Throwable {
        executeJavaScript("try {" +
                          script + ";" +
                          "  testController.setBooleanValue(false);" +
                          "} catch (exception) {" +
                          "  testController.setBooleanValue(true);" +
                          "}");
        assertTrue(mTestController.waitForBooleanValue());
    }

    public void testTypeOfInjectedObject() throws Throwable {
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testController"));
    }

    public void testAdditionNotReflectedUntilReload() throws Throwable {
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testObject"));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().addJavascriptInterface(new Object(), "testObject");
            }
        });
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testObject"));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testObject"));
    }

    public void testRemovalNotReflectedUntilReload() throws Throwable {
        injectObjectAndReload(new Object(), "testObject");
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testObject"));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().removeJavascriptInterface("testObject");
            }
        });
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testObject"));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testObject"));
    }

    public void testRemoveObjectNotAdded() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().removeJavascriptInterface("foo");
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof foo"));
    }

    public void testTypeOfMethod() throws Throwable {
        assertEquals("function",
                executeJavaScriptAndGetStringResult("typeof testController.setStringValue"));
    }

    public void testTypeOfInvalidMethod() throws Throwable {
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testController.foo"));
    }

    public void testCallingInvalidMethodRaisesException() throws Throwable {
        assertRaisesException("testController.foo()");
    }

    // Note that this requires that we can pass a JavaScript string to Java.
    public void testTypeOfStaticMethod() throws Throwable {
        injectObjectAndReload(new ObjectWithStaticMethod(), "testObject");
        executeJavaScript("testController.setStringValue(typeof testObject.staticMethod)");
        assertEquals("function", mTestController.waitForStringValue());
    }

    // Note that this requires that we can pass a JavaScript string to Java.
    public void testCallStaticMethod() throws Throwable {
        injectObjectAndReload(new ObjectWithStaticMethod(), "testObject");
        executeJavaScript("testController.setStringValue(testObject.staticMethod())");
        assertEquals("foo", mTestController.waitForStringValue());
    }

    public void testPrivateMethodNotExposed() throws Throwable {
        injectObjectAndReload(new Object() {
            private void method() {}
        }, "testObject");
        assertEquals("undefined",
                executeJavaScriptAndGetStringResult("typeof testObject.method"));
    }

    public void testReplaceInjectedObject() throws Throwable {
        injectObjectAndReload(new Object() {
            public void method() { mTestController.setStringValue("object 1"); }
        }, "testObject");
        executeJavaScript("testObject.method()");
        assertEquals("object 1", mTestController.waitForStringValue());

        injectObjectAndReload(new Object() {
            public void method() { mTestController.setStringValue("object 2"); }
        }, "testObject");
        executeJavaScript("testObject.method()");
        assertEquals("object 2", mTestController.waitForStringValue());
    }

    public void testInjectNullObjectIsIgnored() throws Throwable {
        injectObjectAndReload(null, "testObject");
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testObject"));
    }

    public void testReplaceInjectedObjectWithNullObjectIsIgnored() throws Throwable {
        injectObjectAndReload(new Object(), "testObject");
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testObject"));
        injectObjectAndReload(null, "testObject");
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testObject"));
    }

    public void testCallOverloadedMethodWithDifferentNumberOfArguments() throws Throwable {
        injectObjectAndReload(new Object() {
            public void method() { mTestController.setStringValue("0 args"); }
            public void method(int x) { mTestController.setStringValue("1 arg"); }
            public void method(int x, int y) { mTestController.setStringValue("2 args"); }
        }, "testObject");
        executeJavaScript("testObject.method()");
        assertEquals("0 args", mTestController.waitForStringValue());
        executeJavaScript("testObject.method(42)");
        assertEquals("1 arg", mTestController.waitForStringValue());
        executeJavaScript("testObject.method(null)");
        assertEquals("1 arg", mTestController.waitForStringValue());
        executeJavaScript("testObject.method(undefined)");
        assertEquals("1 arg", mTestController.waitForStringValue());
        executeJavaScript("testObject.method(42, 42)");
        assertEquals("2 args", mTestController.waitForStringValue());
    }

    public void testCallMethodWithWrongNumberOfArgumentsRaisesException() throws Throwable {
        assertRaisesException("testController.setIntValue()");
        assertRaisesException("testController.setIntValue(42, 42)");
    }

    public void testObjectPersistsAcrossPageLoads() throws Throwable {
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testController"));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        assertEquals("object", executeJavaScriptAndGetStringResult("typeof testController"));
    }

    public void testSameObjectInjectedMultipleTimes() throws Throwable {
        class TestObject {
            private int mNumMethodInvocations;
            public void method() { mTestController.setIntValue(++mNumMethodInvocations); }
        }
        final TestObject testObject = new TestObject();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().addJavascriptInterface(testObject, "testObject1");
                getWebView().addJavascriptInterface(testObject, "testObject2");
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        executeJavaScript("testObject1.method()");
        assertEquals(1, mTestController.waitForIntValue());
        executeJavaScript("testObject2.method()");
        assertEquals(2, mTestController.waitForIntValue());
    }

    public void testCallMethodOnReturnedObject() throws Throwable {
        injectObjectAndReload(new Object() {
            public Object getInnerObject() {
                return new Object() {
                    public void method(int x) { mTestController.setIntValue(x); }
                };
            }
        }, "testObject");
        executeJavaScript("testObject.getInnerObject().method(42)");
        assertEquals(42, mTestController.waitForIntValue());
    }

    public void testReturnedObjectInjectedElsewhere() throws Throwable {
        class InnerObject {
            private int mNumMethodInvocations;
            public void method() { mTestController.setIntValue(++mNumMethodInvocations); }
        }
        final InnerObject innerObject = new InnerObject();
        final Object object = new Object() {
            public InnerObject getInnerObject() {
                return innerObject;
            }
        };
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().addJavascriptInterface(object, "testObject");
                getWebView().addJavascriptInterface(innerObject, "innerObject");
                getWebView().reload();
            }
        });
        mWebViewClient.waitForOnPageFinished();
        executeJavaScript("testObject.getInnerObject().method()");
        assertEquals(1, mTestController.waitForIntValue());
        executeJavaScript("innerObject.method()");
        assertEquals(2, mTestController.waitForIntValue());
    }

    public void testMethodInvokedOnBackgroundThread() throws Throwable {
        injectObjectAndReload(new Object() {
            public void captureThreadId() {
                mTestController.setLongValue(Thread.currentThread().getId());
            }
        }, "testObject");
        executeJavaScript("testObject.captureThreadId()");
        final long threadId = mTestController.waitForLongValue();
        assertFalse(threadId == Thread.currentThread().getId());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(threadId == Thread.currentThread().getId());
            }
        });
    }

    public void testPublicInheritedMethod() throws Throwable {
        class Base {
            public void method(int x) { mTestController.setIntValue(x); }
        }
        class Derived extends Base {
        }
        injectObjectAndReload(new Derived(), "testObject");
        assertEquals("function", executeJavaScriptAndGetStringResult("typeof testObject.method"));
        executeJavaScript("testObject.method(42)");
        assertEquals(42, mTestController.waitForIntValue());
    }

    public void testPrivateInheritedMethod() throws Throwable {
        class Base {
            private void method() {}
        }
        class Derived extends Base {
        }
        injectObjectAndReload(new Derived(), "testObject");
        assertEquals("undefined", executeJavaScriptAndGetStringResult("typeof testObject.method"));
    }

    public void testOverriddenMethod() throws Throwable {
        class Base {
            public void method() { mTestController.setStringValue("base"); }
        }
        class Derived extends Base {
            public void method() { mTestController.setStringValue("derived"); }
        }
        injectObjectAndReload(new Derived(), "testObject");
        executeJavaScript("testObject.method()");
        assertEquals("derived", mTestController.waitForStringValue());
    }

    public void testEnumerateMembers() throws Throwable {
        injectObjectAndReload(new Object() {
            public void method() {}
            private void privateMethod() {}
            public int field;
            private int privateField;
        }, "testObject");
        executeJavaScript(
                "var result = \"\"; " +
                "for (x in testObject) { result += \" \" + x } " +
                "testController.setStringValue(result);");
        // LIVECONNECT_COMPLIANCE: Should be able to enumerate members.
        assertEquals("", mTestController.waitForStringValue());
    }
}
