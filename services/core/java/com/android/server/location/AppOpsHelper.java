/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location;

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.location.CallerIdentity.PERMISSION_NONE;
import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides helpers and listeners for appops.
 */
public class AppOpsHelper {

    /**
     * Listener for current user changes.
     */
    public interface LocationAppOpListener {

        /**
         * Called when something has changed about a location appop for the given package.
         */
        void onAppOpsChanged(String packageName);
    }

    private final Context mContext;
    private final CopyOnWriteArrayList<LocationAppOpListener> mListeners;

    @GuardedBy("this")
    @Nullable
    private AppOpsManager mAppOps;

    public AppOpsHelper(Context context) {
        mContext = context;
        mListeners = new CopyOnWriteArrayList<>();
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        if (mAppOps != null) {
            return;
        }

        mAppOps = Objects.requireNonNull(mContext.getSystemService(AppOpsManager.class));
        mAppOps.startWatchingMode(
                AppOpsManager.OP_COARSE_LOCATION,
                null,
                AppOpsManager.WATCH_FOREGROUND_CHANGES,
                new AppOpsManager.OnOpChangedInternalListener() {
                    public void onOpChanged(int op, String packageName) {
                        // invoked on ui thread, move to fg thread so ui thread isn't blocked
                        FgThread.getHandler().sendMessage(
                                PooledLambda.obtainMessage(AppOpsHelper::onAppOpChanged,
                                        AppOpsHelper.this, packageName));
                    }
                });
    }

    private void onAppOpChanged(String packageName) {
        if (D) {
            Log.v(TAG, "location appop changed for " + packageName);
        }

        for (LocationAppOpListener listener : mListeners) {
            listener.onAppOpsChanged(packageName);
        }
    }

    /**
     * Adds a listener for app ops events. Callbacks occur on an unspecified thread.
     */
    public void addListener(LocationAppOpListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for app ops events.
     */
    public void removeListener(LocationAppOpListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Checks if the given identity may have locations delivered without noting that a location is
     * being delivered. This is a looser guarantee than {@link #noteLocationAccess(CallerIdentity)},
     * and this function does not validate package arguments and so should not be used with
     * unvalidated arguments or before actually delivering locations.
     *
     * @see AppOpsManager#checkOpNoThrow(int, int, String)
     */
    public boolean checkLocationAccess(CallerIdentity callerIdentity) {
        synchronized (this) {
            Preconditions.checkState(mAppOps != null);
        }

        if (callerIdentity.permissionLevel == PERMISSION_NONE) {
            return false;
        }

        long identity = Binder.clearCallingIdentity();
        try {
            if (mContext.checkPermission(
                    CallerIdentity.asPermission(callerIdentity.permissionLevel), callerIdentity.pid,
                    callerIdentity.uid) != PERMISSION_GRANTED) {
                return false;
            }

            return mAppOps.checkOpNoThrow(
                    CallerIdentity.asAppOp(callerIdentity.permissionLevel),
                    callerIdentity.uid,
                    callerIdentity.packageName) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Notes location access to the given identity, ie, location delivery. This method should be
     * called right before a location is delivered, and if it returns false, the location should not
     * be delivered.
     */
    public boolean noteLocationAccess(CallerIdentity callerIdentity) {
        if (callerIdentity.permissionLevel == PERMISSION_NONE) {
            return false;
        }

        long identity = Binder.clearCallingIdentity();
        try {
            if (mContext.checkPermission(
                    CallerIdentity.asPermission(callerIdentity.permissionLevel), callerIdentity.pid,
                    callerIdentity.uid) != PERMISSION_GRANTED) {
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return noteOpNoThrow(CallerIdentity.asAppOp(callerIdentity.permissionLevel),
                callerIdentity);
    }

    /**
     * Notifies app ops that the given identity is using location at normal/low power levels. If
     * this function returns false, do not later call
     * {@link #stopLocationMonitoring(CallerIdentity)}.
     */
    public boolean startLocationMonitoring(CallerIdentity identity) {
        return startLocationMonitoring(OP_MONITOR_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is no longer using location at normal/low power
     * levels.
     */
    public void stopLocationMonitoring(CallerIdentity identity) {
        stopLocationMonitoring(OP_MONITOR_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is using location at high levels. If this function
     * returns false, do not later call {@link #stopLocationMonitoring(CallerIdentity)}.
     */
    public boolean startHighPowerLocationMonitoring(CallerIdentity identity) {
        return startLocationMonitoring(OP_MONITOR_HIGH_POWER_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is no longer using location at high power levels.
     */
    public void stopHighPowerLocationMonitoring(CallerIdentity identity) {
        stopLocationMonitoring(OP_MONITOR_HIGH_POWER_LOCATION, identity);
    }

    /**
     * Notes access to any mock location APIs. If this call returns false, access to the APIs should
     * silently fail.
     */
    public boolean noteMockLocationAccess(CallerIdentity callerIdentity) {
        synchronized (this) {
            Preconditions.checkState(mAppOps != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            // note that this is not the no throw version of noteOp, this call may throw exceptions
            return mAppOps.noteOp(
                    AppOpsManager.OP_MOCK_LOCATION,
                    callerIdentity.uid,
                    callerIdentity.packageName,
                    callerIdentity.featureId,
                    callerIdentity.listenerId) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean startLocationMonitoring(int appOp, CallerIdentity callerIdentity) {
        synchronized (this) {
            Preconditions.checkState(mAppOps != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.startOpNoThrow(
                    appOp,
                    callerIdentity.uid,
                    callerIdentity.packageName,
                    false,
                    callerIdentity.featureId,
                    callerIdentity.listenerId) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void stopLocationMonitoring(int appOp, CallerIdentity callerIdentity) {
        synchronized (this) {
            Preconditions.checkState(mAppOps != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            mAppOps.finishOp(
                    appOp,
                    callerIdentity.uid,
                    callerIdentity.packageName,
                    callerIdentity.featureId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean noteOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        synchronized (this) {
            Preconditions.checkState(mAppOps != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.noteOpNoThrow(
                    appOp,
                    callerIdentity.uid,
                    callerIdentity.packageName,
                    callerIdentity.featureId,
                    callerIdentity.listenerId) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
