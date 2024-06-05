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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.Flags;
import android.content.pm.PackageManager;

import dalvik.system.CloseGuard;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class that freezes and kills the given package upon creation, and
 * unfreezes it upon closing. This is typically used when doing surgery on
 * app code/data to prevent the app from running while you're working.
 */
final class PackageFreezer implements AutoCloseable {
    @Nullable private InstallRequest mInstallRequest;

    private final String mPackageName;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    @NonNull
    private final PackageManagerService mPm;

    /**
     * Create and return a stub freezer that doesn't actually do anything,
     * typically used when someone requested
     * {@link PackageManager#INSTALL_DONT_KILL_APP} or
     * {@link PackageManager#DELETE_DONT_KILL_APP}.
     */

    PackageFreezer(PackageManagerService pm, @Nullable InstallRequest request) {
        mPm = pm;
        mPackageName = null;
        mClosed.set(true);
        mCloseGuard.open("close");
        mInstallRequest = request;
        // We only focus on the install Freeze metrics now
        if (mInstallRequest != null) {
            mInstallRequest.onFreezeStarted();
        }
    }

    PackageFreezer(String packageName, int userId, String killReason,
            PackageManagerService pm, int exitInfoReason, @Nullable InstallRequest request) {
        mPm = pm;
        mPackageName = packageName;
        mInstallRequest = request;
        final PackageSetting ps;
        // We only focus on the install Freeze metrics now
        if (mInstallRequest != null) {
            mInstallRequest.onFreezeStarted();
        }
        synchronized (mPm.mLock) {
            final int refCounts = mPm.mFrozenPackages
                    .getOrDefault(mPackageName, 0 /* defaultValue */) + 1;
            mPm.mFrozenPackages.put(mPackageName, refCounts);
            ps = mPm.mSettings.getPackageLPr(mPackageName);
        }
        if (ps != null) {
            if (Flags.waitApplicationKilled()) {
                mPm.killApplicationSync(ps.getPackageName(), ps.getAppId(), userId, killReason,
                        exitInfoReason);
            } else {
                mPm.killApplication(ps.getPackageName(), ps.getAppId(), userId, killReason,
                        exitInfoReason);
            }
        }
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            close();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void close() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            synchronized (mPm.mLock) {
                final int refCounts = mPm.mFrozenPackages
                        .getOrDefault(mPackageName, 0 /* defaultValue */) - 1;
                if (refCounts > 0) {
                    mPm.mFrozenPackages.put(mPackageName, refCounts);
                } else {
                    mPm.mFrozenPackages.remove(mPackageName);
                }
            }
        }
        // We only focus on the install Freeze metrics now
        if (mInstallRequest != null) {
            mInstallRequest.onFreezeCompleted();
            mInstallRequest = null;
        }
    }
}
