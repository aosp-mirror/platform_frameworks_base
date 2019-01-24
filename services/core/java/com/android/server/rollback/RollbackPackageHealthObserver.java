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
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.PackageHealthObserver;

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
    private Handler mHandler;

    RollbackPackageHealthObserver(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
    }

    @Override
    public boolean onHealthCheckFailed(String packageName) {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        RollbackInfo rollback = rollbackManager.getAvailableRollback(packageName);
        if (rollback != null) {
            // TODO(zezeozue): Only rollback if rollback version == failed package version
            mHandler.post(() -> executeRollback(rollbackManager, rollback));
            return true;
        }
        // Don't handle the notification, no rollbacks available
        return false;
    }

    /**
     * Start observing health of {@code packages} for {@code durationMs}.
     * This may cause {@code packages} to be rolled back if they crash too freqeuntly.
     */
    public void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs);
    }

    private void executeRollback(RollbackManager manager, RollbackInfo rollback) {
        // TODO(zezeozue): Log initiated metrics
        LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            mHandler.post(() -> {
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
        });
        manager.executeRollback(rollback, rollbackReceiver.getIntentSender());
    }

    @Override
    public String getName() {
        return NAME;
    }
}
