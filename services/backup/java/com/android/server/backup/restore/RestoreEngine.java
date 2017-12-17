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
 * limitations under the License
 */

package com.android.server.backup.restore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Restore infrastructure.
 */
public abstract class RestoreEngine {

    private static final String TAG = "RestoreEngine";

    public static final int SUCCESS = 0;
    public static final int TARGET_FAILURE = -2;
    public static final int TRANSPORT_FAILURE = -3;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicInteger mResult = new AtomicInteger(SUCCESS);

    public boolean isRunning() {
        return mRunning.get();
    }

    public void setRunning(boolean stillRunning) {
        synchronized (mRunning) {
            mRunning.set(stillRunning);
            mRunning.notifyAll();
        }
    }

    public int waitForResult() {
        synchronized (mRunning) {
            while (isRunning()) {
                try {
                    mRunning.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        return getResult();
    }

    public int getResult() {
        return mResult.get();
    }

    public void setResult(int result) {
        mResult.set(result);
    }

    // TODO: abstract restore state and APIs
}
