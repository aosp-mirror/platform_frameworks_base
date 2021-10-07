/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.hdmi;

import android.os.Binder;

/**
 * Executes a given Runnable with the work source UID of the thread that constructed this.
 */
public class WorkSourceUidPreservingRunnable implements Runnable {
    private Runnable mRunnable;
    private int mUid;

    /**
     * @param runnable The Runnable to execute
     */
    public WorkSourceUidPreservingRunnable(Runnable runnable) {
        this.mRunnable = runnable;
        this.mUid = Binder.getCallingWorkSourceUid();
    }

    @Override
    public void run() {
        long token = Binder.setCallingWorkSourceUid(mUid);
        try {
            mRunnable.run();
        } finally {
            Binder.restoreCallingWorkSource(token);
        }
    }
}
