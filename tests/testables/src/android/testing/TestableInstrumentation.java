/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.TestLooperManager;
import android.util.Log;

import androidx.test.runner.AndroidJUnitRunner;

import java.util.ArrayList;

/**
 * Wrapper around instrumentation that spins up a TestLooperManager around
 * the main looper whenever a test is not using it to attempt to stop crashes
 * from stopping other tests from running.
 */
public class TestableInstrumentation extends AndroidJUnitRunner {

    private static final String TAG = "TestableInstrumentation";

    private static final int MAX_CRASHES = 5;
    private static MainLooperManager sManager;

    @Override
    public void onCreate(Bundle arguments) {
        if (TestableLooper.HOLD_MAIN_THREAD) {
            sManager = new MainLooperManager();
            Log.setWtfHandler((tag, what, system) -> {
                if (system) {
                    Log.e(TAG, "WTF!!", what);
                } else {
                    // These normally kill the app, but we don't want that in a test, instead we want
                    // it to throw.
                    throw new RuntimeException(what);
                }
            });
        }
        super.onCreate(arguments);
    }

    @Override
    public void finish(int resultCode, Bundle results) {
        if (TestableLooper.HOLD_MAIN_THREAD) {
            sManager.destroy();
        }
        super.finish(resultCode, results);
    }

    public static void acquireMain() {
        if (sManager != null) {
            sManager.acquireMain();
        }
    }

    public static void releaseMain() {
        if (sManager != null) {
            sManager.releaseMain();
        }
    }

    public class MainLooperManager implements Runnable {

        private final ArrayList<Throwable> mExceptions = new ArrayList<>();
        private Message mStopMessage;
        private final Handler mMainHandler;
        private TestLooperManager mManager;

        public MainLooperManager() {
            mMainHandler = Handler.createAsync(Looper.getMainLooper());
            startManaging();
        }

        @Override
        public void run() {
            try {
                synchronized (this) {
                    // Let the thing starting us know we are up and ready to run.
                    notify();
                }
                while (true) {
                    Message m = mManager.next();
                    if (m == mStopMessage) {
                        mManager.recycle(m);
                        return;
                    }
                    try {
                        mManager.execute(m);
                    } catch (Throwable t) {
                        if (!checkStack(t) || (mExceptions.size() == MAX_CRASHES)) {
                            throw t;
                        }
                        mExceptions.add(t);
                        Log.d(TAG, "Ignoring exception to run more tests", t);
                    }
                    mManager.recycle(m);
                }
            } finally {
                mManager.release();
                synchronized (this) {
                    // Let the caller know we are done managing the main thread.
                    notify();
                }
            }
        }

        private boolean checkStack(Throwable t) {
            StackTraceElement topStack = t.getStackTrace()[0];
            String className = topStack.getClassName();
            if (className.equals(TestLooperManager.class.getName())) {
                topStack = t.getCause().getStackTrace()[0];
                className = topStack.getClassName();
            }
            // Only interested in blocking exceptions from the app itself, not from android
            // framework.
            return !className.startsWith("android.")
                    && !className.startsWith("com.android.internal");
        }

        public void destroy() {
            mStopMessage.sendToTarget();
            if (mExceptions.size() != 0) {
                throw new RuntimeException("Exception caught during tests", mExceptions.get(0));
            }
        }

        public void acquireMain() {
            synchronized (this) {
                mStopMessage.sendToTarget();
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }

        public void releaseMain() {
            startManaging();
        }

        private void startManaging() {
            mStopMessage = mMainHandler.obtainMessage();
            synchronized (this) {
                mManager = acquireLooperManager(Looper.getMainLooper());
                // This bit needs to happen on a background thread or it will hang if called
                // from the same thread we are looking to block.
                new Thread(() -> {
                    // Post a message to the main handler that will manage executing all future
                    // messages.
                    mMainHandler.post(this);
                    while (!mManager.hasMessages(mMainHandler, null, this));
                    // Lastly run the message that executes this so it can manage the main thread.
                    Message next = mManager.next();
                    // Run through messages until we reach ours.
                    while (next.getCallback() != this) {
                        mManager.execute(next);
                        mManager.recycle(next);
                        next = mManager.next();
                    }
                    mManager.execute(next);
                }).start();
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }
}
