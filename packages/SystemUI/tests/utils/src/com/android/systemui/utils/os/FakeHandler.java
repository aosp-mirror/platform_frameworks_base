/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.utils.os;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.ArrayList;

/**
 * A handler that allows control over when to dispatch messages and callbacks.
 *
 * WARNING: Because most Handler methods are final, the only thing this handler can intercept
 *          are sending messages and posting runnables, but *NOT* removing messages nor runnables.
 *          It also *CANNOT* intercept messages posted to the front of queue.
 */
public class FakeHandler extends Handler {

    private Mode mMode = Mode.IMMEDIATE;
    private ArrayList<Message> mQueuedMessages = new ArrayList<>();

    public FakeHandler(Looper looper) {
        super(looper);
    }

    @Override
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        mQueuedMessages.add(msg);
        if (mMode == Mode.IMMEDIATE) {
            dispatchQueuedMessages();
        }
        return true;
    }

    public void setMode(Mode mode) {
        mMode = mode;
    }

    /**
     * Dispatch any messages that have been queued on the calling thread.
     */
    public void dispatchQueuedMessages() {
        ArrayList<Message> messages = new ArrayList<>(mQueuedMessages);
        mQueuedMessages.clear();
        for (Message msg : messages) {
            dispatchMessage(msg);
        }
    }

    public enum Mode {
        /** Messages are dispatched immediately on the calling thread. */
        IMMEDIATE,
        /** Messages are queued until {@link #dispatchQueuedMessages()} is called. */
        QUEUEING,
    }
}
