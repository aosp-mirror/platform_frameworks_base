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

import android.content.pm.IPackageDeleteObserver;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous implementation of IPackageDeleteObserver.Stub.
 *
 * Allows the caller to synchronously wait for package deleted event.
 */
public class RestoreDeleteObserver extends IPackageDeleteObserver.Stub {

    @GuardedBy("mDone")
    private final AtomicBoolean mDone = new AtomicBoolean();

    public RestoreDeleteObserver() {
    }

    /**
     * Resets the observer to prepare for another removal.
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

    @Override
    public void packageDeleted(String packageName, int returnCode) throws RemoteException {
        synchronized (mDone) {
            mDone.set(true);
            mDone.notifyAll();
        }
    }
}
