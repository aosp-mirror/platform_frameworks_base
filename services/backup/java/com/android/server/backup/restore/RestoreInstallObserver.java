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

import android.app.PackageInstallObserver;
import android.os.Bundle;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous implementation of PackageInstallObserver.
 *
 * Allows the caller to synchronously wait for package install event.
 */
public class RestoreInstallObserver extends PackageInstallObserver {

    @GuardedBy("mDone")
    private final AtomicBoolean mDone = new AtomicBoolean();

    private String mPackageName;
    private int mResult;

    public RestoreInstallObserver() {
    }

    /**
     * Resets the observer to prepare for another installation.
     */
    public void reset() {
        synchronized (mDone) {
            mDone.set(false);
        }
    }

    /**
     * Synchronously waits for completion.
     */
    public void waitForCompletion() {
        synchronized (mDone) {
            while (mDone.get() == false) {
                try {
                    mDone.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Returns result code.
     */
    public int getResult() {
        return mResult;
    }

    /**
     * Returns installed package name.
     */
    public String getPackageName() {
        return mPackageName;
    }

    @Override
    public void onPackageInstalled(String packageName, int returnCode,
            String msg, Bundle extras) {
        synchronized (mDone) {
            mResult = returnCode;
            mPackageName = packageName;
            mDone.set(true);
            mDone.notifyAll();
        }
    }
}
