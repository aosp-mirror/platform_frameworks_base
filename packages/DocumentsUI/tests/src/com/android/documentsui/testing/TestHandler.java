/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.testing;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.TimerTask;

/**
 * A test double of {@link Handler}, backed by {@link TestTimer}.
 */
public class TestHandler extends Handler {
    private TestTimer mTimer = new TestTimer();

    public TestHandler() {
        // Use main looper to trick underlying handler, we're not using it at all.
        super(Looper.getMainLooper());
    }

    public boolean hasScheduledMessage() {
        return mTimer.hasScheduledTask();
    }

    public void dispatchNextMessage() {
        mTimer.fastForwardToNextTask();
    }

    public void dispatchAllMessages() {
        while (hasScheduledMessage()) {
            dispatchNextMessage();
        }
    }

    @Override
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        msg.setTarget(this);
        TimerTask task = new MessageTimerTask(msg);
        mTimer.scheduleAtTime(new TestTimer.Task(task), uptimeMillis);
        return true;
    }

    private static class MessageTimerTask extends TimerTask {
        private Message mMessage;

        private MessageTimerTask(Message message) {
            mMessage = message;
        }

        @Override
        public void run() {
            mMessage.getTarget().dispatchMessage(mMessage);
        }
    }
}
