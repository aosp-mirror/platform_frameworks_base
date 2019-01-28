/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.rollback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import java.util.Collections;
import java.util.List;

/**
 * {@code PackageHealthObserver} for {@code RollbackManagerService}.
 *
 * @hide
 */
public final class RollbackPackageHealthObserver implements PackageHealthObserver {
    private static final String TAG = "RollbackPackageHealthObserver";
    private static final String NAME = "rollback-observer";
    private Context mContext;
    private RollbackManager mRollbackManager;
    private Handler mHandler;

    RollbackPackageHealthObserver(Context context) {
        mContext = context;
        mRollbackManager = mContext.getSystemService(RollbackManager.class);
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
    }

    @Override
    public int onHealthCheckFailed(String packageName) {
        RollbackInfo rollback = getAvailableRollback(packageName);
        if (rollback == null) {
            // Don't handle the notification, no rollbacks available for the package
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }
        // Rollback is available, we may get a callback into #execute
        return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
    }

    @Override
    public boolean execute(String packageName) {
        RollbackInfo rollback = getAvailableRollback(packageName);
        if (rollback == null) {
            // Expected a rollback to be available, what happened?
            return false;
        }

        // TODO(zezeozue): Only rollback if rollback version == failed package version
        LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                // TODO(zezeozue); Log success metrics
                // Rolledback successfully, no action required by other observers
            } else {
                // TODO(zezeozue); Log failure metrics
                // Rollback failed other observers should have a shot
            }
        });

        // TODO(zezeozue): Log initiated metrics
        // TODO: Pass the package as a cause package instead of using
        // Collections.emptyList once the version of the failing package is
        // easily available.
        mHandler.post(() ->
                mRollbackManager.commitRollback(rollback.getRollbackId(),
                    Collections.emptyList(),
                    rollbackReceiver.getIntentSender()));
        // Assume rollback executed successfully
        return true;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Start observing health of {@code packages} for {@code durationMs}.
     * This may cause {@code packages} to be rolled back if they crash too freqeuntly.
     */
    public void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs);
    }

    private RollbackInfo getAvailableRollback(String packageName) {
        for (RollbackInfo rollback : mRollbackManager.getAvailableRollbacks()) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageName.equals(packageRollback.getPackageName())) {
                    // TODO(zezeozue): Only rollback if rollback version == failed package version
                    return rollback;
                }
            }
        }
        return null;
    }
}
