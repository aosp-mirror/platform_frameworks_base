/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

public abstract class HandlerTester extends Thread {
    public abstract void go();
    public abstract void handleMessage(Message msg);

    public HandlerTester() {
    }

    public void doTest(long timeout) {
        start();

        synchronized (this) {
            try {
                wait(timeout);
                quit();
            }
            catch (InterruptedException e) {
            }
        }

        if (!mDone) {
            throw new RuntimeException("test timed out");
        }
        if (!mSuccess) {
            throw new RuntimeException("test failed");
        }
    }

    public void success() {
        mDone = true;
        mSuccess = true;
    }

    public void failure() {
        mDone = true;
        mSuccess = false;
    }

    public void run() {
        Looper.prepare();
        mLooper = Looper.myLooper();
        go();
        Looper.loop();
    }

    protected class H extends Handler {
        public void handleMessage(Message msg) {
            synchronized (HandlerTester.this) {
                // Call into them with our monitor locked, so they don't have
                // to deal with other races.
                HandlerTester.this.handleMessage(msg);
                if (mDone) {
                    HandlerTester.this.notify();
                    quit();
                }
            }
        }
    }

    private void quit() {
        mLooper.quit();
    }

    private boolean mDone = false;
    private boolean mSuccess = false;
    private Looper mLooper;
}

