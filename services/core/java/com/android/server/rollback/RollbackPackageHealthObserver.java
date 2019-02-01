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
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.R;
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
    private Handler mHandler;

    RollbackPackageHealthObserver(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
    }

    @Override
    public int onHealthCheckFailed(VersionedPackage failedPackage) {
        VersionedPackage moduleMetadataPackage = getModuleMetadataPackage();
        if (moduleMetadataPackage == null) {
            // Ignore failure, no mainline update available
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }

        RollbackInfo rollback =
                getAvailableMainlineRollback(mContext.getSystemService(RollbackManager.class),
                        failedPackage, moduleMetadataPackage);
        if (rollback == null) {
            // Don't handle the notification, no rollbacks available for the package
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }
        // Rollback is available, we may get a callback into #execute
        return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
    }

    @Override
    public boolean execute(VersionedPackage failedPackage) {
        VersionedPackage moduleMetadataPackage = getModuleMetadataPackage();
        if (moduleMetadataPackage == null) {
            // Ignore failure, no mainline update available
            return false;
        }

        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        RollbackInfo rollback = getAvailableMainlineRollback(rollbackManager,
                failedPackage, moduleMetadataPackage);
        if (rollback == null) {
            Slog.w(TAG, "Expected rollback but no mainline rollback found for package: [ "
                    + failedPackage.getPackageName() + "] with versionCode: ["
                    + failedPackage.getVersionCode() + "]");
            return false;
        }

        StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE,
                moduleMetadataPackage.getPackageName(),
                moduleMetadataPackage.getVersionCode());
        LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                    RollbackManager.STATUS_FAILURE);
            if (status == RollbackManager.STATUS_SUCCESS) {
                StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                        moduleMetadataPackage.getPackageName(),
                        moduleMetadataPackage.getVersionCode());
            } else {
                StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        moduleMetadataPackage.getPackageName(),
                        moduleMetadataPackage.getVersionCode());
            }
        });

        mHandler.post(() ->
                rollbackManager.commitRollback(rollback.getRollbackId(),
                    Collections.singletonList(moduleMetadataPackage),
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

    private RollbackInfo getAvailableMainlineRollback(RollbackManager rollbackManager,
            VersionedPackage failedPackage, VersionedPackage moduleMetadataPackage) {
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            // We only rollback mainline packages, so check if rollback contains the
            // module metadata provider, if it does, the rollback is a mainline rollback
            boolean hasModuleMetadataPackage = false;
            boolean hasFailedPackage = false;
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                hasModuleMetadataPackage |= packageRollback.getPackageName().equals(
                        moduleMetadataPackage.getPackageName());
                hasFailedPackage |= packageRollback.getPackageName().equals(
                        failedPackage.getPackageName())
                        && packageRollback.getVersionRolledBackFrom().getVersionCode()
                        == failedPackage.getVersionCode();
            }
            if (hasModuleMetadataPackage && hasFailedPackage) {
                return rollback;
            }
        }
        return null;
    }

    private VersionedPackage getModuleMetadataPackage() {
        String packageName = mContext.getResources().getString(
                R.string.config_defaultModuleMetadataProvider);
        if (!TextUtils.isEmpty(packageName)) {
            return null;
        }

        try {
            return new VersionedPackage(packageName, mContext.getPackageManager().getPackageInfo(
                            packageName, 0 /* flags */).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Module metadata provider not found");
            return null;
        }
    }
}
