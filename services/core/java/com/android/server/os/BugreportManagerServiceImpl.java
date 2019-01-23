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

package com.android.server.os;

import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.BugreportParams;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.IDumpstateToken;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;

import java.io.FileDescriptor;

// TODO(b/111441001):
// 1. Handle the case where another bugreport is in progress
// 2. Make everything threadsafe
// 3. Pass validation & other errors on listener

/**
 * Implementation of the service that provides a privileged API to capture and consume bugreports.
 *
 * <p>Delegates the actualy generation to a native implementation of {@code Dumpstate}.
 */
class BugreportManagerServiceImpl extends IDumpstate.Stub {
    private static final String TAG = "BugreportManagerService";
    private static final String BUGREPORT_SERVICE = "bugreportd";
    private static final long DEFAULT_BUGREPORT_SERVICE_TIMEOUT_MILLIS = 30 * 1000;

    private IDumpstate mDs = null;
    private final Context mContext;
    private final AppOpsManager mAppOps;

    BugreportManagerServiceImpl(Context context) {
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public IDumpstateToken setListener(String name, IDumpstateListener listener,
            boolean getSectionDetails) throws RemoteException {
        // TODO(b/111441001): Figure out if lazy setting of listener should be allowed
        // and if so how to handle it.
        throw new UnsupportedOperationException("setListener is not allowed on this service");
    }

    // TODO(b/111441001): Intercept onFinished here in system server and shutdown
    // the bugreportd service.
    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(int callingUidUnused, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, IDumpstateListener listener) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        // TODO(b/111441001): validate all arguments & ensure primary user
        validate(bugreportMode);

        mAppOps.checkPackage(callingUid, callingPackage);
        mDs = getDumpstateService();
        if (mDs == null) {
            Slog.w(TAG, "Unable to get bugreport service");
            // TODO(b/111441001): pass error on listener
            return;
        }
        mDs.startBugreport(callingUid, callingPackage,
                bugreportFd, screenshotFd, bugreportMode, listener);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void cancelBugreport() throws RemoteException {
        // This tells init to cancel bugreportd service.
        SystemProperties.set("ctl.stop", BUGREPORT_SERVICE);
        mDs = null;
    }

    private boolean validate(@BugreportParams.BugreportMode int mode) {
        if (mode != BugreportParams.BUGREPORT_MODE_FULL
                && mode != BugreportParams.BUGREPORT_MODE_INTERACTIVE
                && mode != BugreportParams.BUGREPORT_MODE_REMOTE
                && mode != BugreportParams.BUGREPORT_MODE_WEAR
                && mode != BugreportParams.BUGREPORT_MODE_TELEPHONY
                && mode != BugreportParams.BUGREPORT_MODE_WIFI) {
            Slog.w(TAG, "Unknown bugreport mode: " + mode);
            return false;
        }
        return true;
    }

    /*
     * Start and get a handle to the native implementation of {@code IDumpstate} which does the
     * actual bugreport generation.
     *
     * <p>Generating bugreports requires root privileges. To limit the footprint
     * of the root access, the actual generation in Dumpstate binary is accessed as a
     * oneshot service 'bugreport'.
     */
    private IDumpstate getDumpstateService() {
        // Start bugreport service.
        SystemProperties.set("ctl.start", BUGREPORT_SERVICE);

        IDumpstate ds = null;
        boolean timedOut = false;
        int totalTimeWaitedMillis = 0;
        int seedWaitTimeMillis = 500;
        while (!timedOut) {
            // Note that the binder service on the native side is "dumpstate".
            ds = IDumpstate.Stub.asInterface(ServiceManager.getService("dumpstate"));
            if (ds != null) {
                Slog.i(TAG, "Got bugreport service handle.");
                break;
            }
            SystemClock.sleep(seedWaitTimeMillis);
            Slog.i(TAG,
                    "Waiting to get dumpstate service handle (" + totalTimeWaitedMillis + "ms)");
            totalTimeWaitedMillis += seedWaitTimeMillis;
            seedWaitTimeMillis *= 2;
            timedOut = totalTimeWaitedMillis > DEFAULT_BUGREPORT_SERVICE_TIMEOUT_MILLIS;
        }
        if (timedOut) {
            Slog.w(TAG,
                    "Timed out waiting to get dumpstate service handle ("
                    + totalTimeWaitedMillis + "ms)");
        }
        return ds;
    }
}
