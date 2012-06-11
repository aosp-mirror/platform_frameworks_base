/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * Part of the test suite for the WebView's Java Bridge.
 *
 * Ensures that injected objects are exposed to child frames as well as the
 * main frame.
 *
 * To run this test ...
 *  adb shell am instrument -w -e class com.android.webviewtests.JavaBridgeChildFrameTest \
 *          com.android.webviewtests/android.test.InstrumentationTestRunner
 */

package com.android.webviewtests;

public class JavaBridgeChildFrameTest extends JavaBridgeTestBase {
    private class TestController extends Controller {
        private String mStringValue;

       public synchronized void setStringValue(String x) {
            mStringValue = x;
            notifyResultIsReady();
        }
       public synchronized String waitForStringValue() {
            waitForResult();
            return mStringValue;
        }
    }

    TestController mTestController;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestController = new TestController();
        setUpWebView(mTestController, "testController");
    }

    public void testInjectedObjectPresentInChildFrame() throws Throwable {
        // In the case that the test fails (i.e. the child frame doesn't get the injected object,
        // the call to testController.setStringValue in the child frame's onload handler will
        // not be made.
        getActivity().getWebView().loadData(
                "<html><head></head><body>" +
                "<iframe id=\"childFrame\" onload=\"testController.setStringValue('PASS');\" />" +
                "</body></html>", "text/html", null);
        assertEquals("PASS", mTestController.waitForStringValue());
    }
}
