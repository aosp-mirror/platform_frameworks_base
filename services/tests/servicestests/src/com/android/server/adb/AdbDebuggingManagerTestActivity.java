/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.adb;

import static com.android.server.adb.AdbDebuggingManagerTest.TestResult;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;

import java.util.concurrent.BlockingQueue;

/**
 * Helper Activity used to test the AdbDebuggingManager's prompt to allow an adb key.
 */
public class AdbDebuggingManagerTestActivity extends Activity {

    private static final String TAG = "AdbDebuggingManagerTestActivity";

    /*
     * Static values that must be set before each test to modify the behavior of the Activity.
     */
    private static AdbDebuggingManager.AdbDebuggingHandler sHandler;
    private static boolean sAllowKey;
    private static boolean sAlwaysAllow;
    private static String sExpectedKey;
    private static BlockingQueue sBlockingQueue;

    /**
     * Receives the Intent sent from the AdbDebuggingManager and sends the preconfigured response.
     */
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent intent = getIntent();
        String key = intent.getStringExtra("key");
        if (!key.equals(sExpectedKey)) {
            TestResult result = new TestResult(TestResult.RESULT_UNEXPECTED_KEY, key);
            postResult(result);
            finish();
            return;
        }
        // Post the result that the activity was successfully launched as expected and a response
        // is being sent to let the test method know that it should move on to waiting for the next
        // expected response from the AdbDebuggingManager.
        TestResult result = new TestResult(
                AdbDebuggingManagerTest.TestResult.RESULT_ACTIVITY_LAUNCHED);
        postResult(result);

        // Initialize the message based on the preconfigured values. If the key is accepted the
        // AdbDebuggingManager expects the key to be in the obj field of the message, and if the
        // user selects the 'Always allow' option the manager expects the arg1 field to be set to 1.
        int messageType;
        if (sAllowKey) {
            messageType = AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_ALLOW;
        } else {
            messageType = AdbDebuggingManager.AdbDebuggingHandler.MESSAGE_ADB_DENY;
        }
        Message message = sHandler.obtainMessage(messageType);
        message.obj = key;
        if (sAlwaysAllow) {
            message.arg1 = 1;
        }
        finish();
        sHandler.sendMessage(message);
    }

    /**
     * Posts the result of the activity to the test method.
     */
    private void postResult(TestResult result) {
        try {
            sBlockingQueue.put(result);
        } catch (InterruptedException e) {
            Log.e(TAG, "Caught an InterruptedException posting the result " + result, e);
        }
    }

    /**
     * Allows test methods to specify the behavior of the Activity before it is invoked by the
     * AdbDebuggingManager.
     */
    public static class Configurator {

        /**
         * Sets the test handler to be used by this activity to send the configured response.
         */
        public Configurator setHandler(AdbDebuggingManager.AdbDebuggingHandler handler) {
            sHandler = handler;
            return this;
        }

        /**
         * Sets whether the key should be allowed for this test.
         */
        public Configurator setAllowKey(boolean allow) {
            sAllowKey = allow;
            return this;
        }

        /**
         * Sets whether the 'Always allow' option should be selected for this test.
         */
        public Configurator setAlwaysAllow(boolean alwaysAllow) {
            sAlwaysAllow = alwaysAllow;
            return this;
        }

        /**
         * Sets the key that should be expected from the AdbDebuggingManager for this test.
         */
        public Configurator setExpectedKey(String expectedKey) {
            sExpectedKey = expectedKey;
            return this;
        }

        /**
         * Sets the BlockingQueue that should be used to post the result of the Activity back to the
         * test method.
         */
        public Configurator setBlockingQueue(BlockingQueue blockingQueue) {
            sBlockingQueue = blockingQueue;
            return this;
        }
    }
}
