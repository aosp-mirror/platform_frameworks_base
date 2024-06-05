/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * A Handler that can track posts/sends and wait for them to be completed.
 */
public class TestHandler extends Handler {

    private final Object mMonitor = new Object();
    private int mMessagesProcessed = 0;
    private int mMessagesSent = 0;

    public TestHandler(Looper looper) {
        super(looper);
    }

    @Override
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        synchronized (mMonitor) {
            mMessagesSent++;
        }

        Runnable callback = msg.getCallback();
        // Have the callback increment the mMessagesProcessed when it is done. It will notify
        // any threads waiting for all messages to be processed if appropriate.
        Runnable newCallback = () -> {
            callback.run();
            synchronized (mMonitor) {
                mMessagesProcessed++;
                if (mMessagesSent == mMessagesProcessed) {
                    mMonitor.notifyAll();
                }
            }
        };
        msg.setCallback(newCallback);
        return super.sendMessageAtTime(msg, uptimeMillis);
    }

    /** Asserts the number of messages posted or sent is as expected. */
    public void assertTotalMessagesEnqueued(int expected) {
        synchronized (mMonitor) {
            assertEquals(expected, mMessagesSent);
        }
    }

    /**
     * Waits for all enqueued work to be completed before returning.
     */
    public void waitForMessagesToBeProcessed() {
        synchronized (mMonitor) {
            if (mMessagesSent != mMessagesProcessed) {
                try {
                    mMonitor.wait();
                } catch (InterruptedException e) {
                    throw new AssertionError("Unexpected exception", e);
                }
            }
        }
    }
}
