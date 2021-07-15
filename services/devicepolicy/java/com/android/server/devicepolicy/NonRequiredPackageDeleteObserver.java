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

package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG;

import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Slog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Awaits the deletion of all the non-required apps.
 */
final class NonRequiredPackageDeleteObserver extends IPackageDeleteObserver.Stub {
    private static final int PACKAGE_DELETE_TIMEOUT_SEC = 30;

    private final AtomicInteger mPackageCount = new AtomicInteger(/* initialValue= */ 0);
    private final CountDownLatch mLatch;
    private boolean mSuccess;

    NonRequiredPackageDeleteObserver(int packageCount) {
        this.mLatch = new CountDownLatch(packageCount);
        this.mPackageCount.set(packageCount);
    }

    @Override
    public void packageDeleted(String packageName, int returnCode) {
        if (returnCode != PackageManager.DELETE_SUCCEEDED) {
            Slog.e(LOG_TAG, "Failed to delete package: " + packageName);
            mLatch.notifyAll();
            return;
        }
        int currentPackageCount = mPackageCount.decrementAndGet();
        if (currentPackageCount == 0) {
            mSuccess = true;
            Slog.i(LOG_TAG, "All non-required system apps with launcher icon, "
                    + "and all disallowed apps have been uninstalled.");
        }
        mLatch.countDown();
    }

    public boolean awaitPackagesDeletion() {
        try {
            mLatch.await(PACKAGE_DELETE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while waiting for package deletion", e);
            Thread.currentThread().interrupt();
        }
        return mSuccess;
    }
}
