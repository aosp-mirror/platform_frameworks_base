/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.MessageQueue;

public class RequestHandlerThread extends HandlerThread {

    /**
     * Ensure that the MessageQueue's idle handler gets run by poking the message queue;
     * normally if the message queue is already idle, the idle handler won't get invoked.
     *
     * <p>Users of this handler thread should ignore this message.</p>
     */
    public final static int MSG_POKE_IDLE_HANDLER = -1;

    private final ConditionVariable mStarted = new ConditionVariable(false);
    private final ConditionVariable mIdle = new ConditionVariable(true);
    private Handler.Callback mCallback;
    private volatile Handler mHandler;

    public RequestHandlerThread(String name, Handler.Callback callback) {
        super(name, Thread.MAX_PRIORITY);
        mCallback = callback;
    }

    @Override
    protected void onLooperPrepared() {
        mHandler = new Handler(getLooper(), mCallback);
        mStarted.open();
    }

    // Blocks until thread has started
    public void waitUntilStarted() {
        mStarted.block();
    }

    // May return null if the handler is not set up yet.
    public Handler getHandler() {
        return mHandler;
    }

    // Blocks until thread has started
    public Handler waitAndGetHandler() {
        waitUntilStarted();
        return getHandler();
    }

    // Atomic multi-type message existence check
    public boolean hasAnyMessages(int[] what) {
        synchronized (mHandler.getLooper().getQueue()) {
            for (int i : what) {
                if (mHandler.hasMessages(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Atomic multi-type message remove
    public void removeMessages(int[] what) {
        synchronized (mHandler.getLooper().getQueue()) {
            for (int i : what) {
                mHandler.removeMessages(i);
            }
        }
    }

    private final MessageQueue.IdleHandler mIdleHandler = new MessageQueue.IdleHandler() {
        @Override
        public boolean queueIdle() {
            mIdle.open();
            return false;
        }
    };

    // Blocks until thread is idling
    public void waitUntilIdle() {
        Handler handler = waitAndGetHandler();
        Looper looper = handler.getLooper();
        if (looper.isIdling()) {
            return;
        }
        mIdle.close();
        looper.getQueue().addIdleHandler(mIdleHandler);
        // Ensure that the idle handler gets run even if the looper already went idle
        handler.sendEmptyMessage(MSG_POKE_IDLE_HANDLER);
        if (looper.isIdling()) {
            return;
        }
        mIdle.block();
    }

}
