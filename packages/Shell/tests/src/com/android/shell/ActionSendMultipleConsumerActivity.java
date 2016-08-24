/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.shell;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/**
 * Activity responsible for handling ACTION_SEND_MULTIPLE intents and passing them back to the test
 * case class (through a {@link CustomActionSendMultipleListener}).
 */
public class ActionSendMultipleConsumerActivity extends Activity {

    private static final String CUSTOM_ACTION_SEND_MULTIPLE_INTENT =
            "com.android.shell.tests.CUSTOM_ACTION_SEND_MULTIPLE";

    private static CustomActionSendMultipleListener sListener;

    static final String UI_NAME = "ActionSendMultipleConsumer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The original intent cannot be broadcasted, it will fail due to security violations.
        // Since the test case is only interested in the extras, we need to create a new custom
        // intent with just them.
        final Intent intent = getIntent();
        final Intent customIntent = new Intent(CUSTOM_ACTION_SEND_MULTIPLE_INTENT);
        customIntent.putExtras(intent.getExtras());

        getApplicationContext().sendBroadcast(customIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * TODO: if finish() is not called, app will crash with an exception such as:
         * AndroidRuntime: java.lang.RuntimeException: Unable to resume activity
         * {com.android.shell.tests/com.android.shell.SendMultipleActivity}:
         * java.lang.IllegalStateException: Activity
         * {com.android.shell.tests/com.android.shell.SendMultipleActivity} did not call finish()
         * prior to onResume() completing. That seems to be a problem on M:
         * https://code.google.com/p/android-developer-preview/issues/detail?id=2353
         */
        finish();
    }

    /**
     * Gets the {@link CustomActionSendMultipleListener} singleton.
     */
    static CustomActionSendMultipleListener getListener(Context context) {
        synchronized (ActionSendMultipleConsumerActivity.class) {
            if (sListener == null) {
                sListener = new CustomActionSendMultipleListener(context);
            }
        }
        return sListener;
    }

    /**
     * Listener of custom ACTION_SEND_MULTIPLE_INTENTS.
     */
    static class CustomActionSendMultipleListener {

        private static final int TIMEOUT = 10;
        private final BlockingQueue<Bundle> mQueue = new SynchronousQueue<>();

        public CustomActionSendMultipleListener(Context context) {
            BroadcastReceiver receiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        mQueue.put(intent.getExtras());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            final IntentFilter filter = new IntentFilter();
            filter.addAction(CUSTOM_ACTION_SEND_MULTIPLE_INTENT);
            context.registerReceiver(receiver, filter);
        }

        /**
         * Gets the extras from the custom intent, blocking until it's received.
         */
        Bundle getExtras() {
            Bundle bundle = null;
            try {
                // UI operations can be slower the very first time the tests are run due
                // because ActionSendMultipleConsumer is not the default activity chosen.
                bundle = mQueue.poll(2 * TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (bundle == null) {
                throw new IllegalStateException("Intent not received after " + TIMEOUT + "s");
            }
            return bundle;
        }
    }
}
