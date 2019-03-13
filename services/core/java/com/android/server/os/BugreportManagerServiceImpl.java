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
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.BugreportParams;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.IDumpstateToken;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;

/**
 * Implementation of the service that provides a privileged API to capture and consume bugreports.
 *
 * <p>Delegates the actualy generation to a native implementation of {@code IDumpstate}.
 */
class BugreportManagerServiceImpl extends IDumpstate.Stub {
    private static final String TAG = "BugreportManagerService";
    private static final String BUGREPORT_SERVICE = "bugreportd";
    private static final long DEFAULT_BUGREPORT_SERVICE_TIMEOUT_MILLIS = 30 * 1000;

    private final Object mLock = new Object();
    private final Context mContext;
    private final AppOpsManager mAppOps;

    BugreportManagerServiceImpl(Context context) {
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public IDumpstateToken setListener(String name, IDumpstateListener listener,
            boolean getSectionDetails) {
        throw new UnsupportedOperationException("setListener is not allowed on this service");
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(int callingUidUnused, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, IDumpstateListener listener) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, "startBugreport");
        Preconditions.checkNotNull(callingPackage);
        Preconditions.checkNotNull(bugreportFd);
        Preconditions.checkNotNull(listener);
        validateBugreportMode(bugreportMode);
        ensureIsPrimaryUser();

        int callingUid = Binder.getCallingUid();
        mAppOps.checkPackage(callingUid, callingPackage);

        synchronized (mLock) {
            startBugreportLocked(callingUid, callingPackage, bugreportFd, screenshotFd,
                    bugreportMode, listener);
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void cancelBugreport() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, "startBugreport");
        // This tells init to cancel bugreportd service. Note that this is achieved through setting
        // a system property which is not thread-safe. So the lock here offers thread-safety only
        // among callers of the API.
        synchronized (mLock) {
            SystemProperties.set("ctl.stop", BUGREPORT_SERVICE);
        }
    }

    private void validateBugreportMode(@BugreportParams.BugreportMode int mode) {
        if (mode != BugreportParams.BUGREPORT_MODE_FULL
                && mode != BugreportParams.BUGREPORT_MODE_INTERACTIVE
                && mode != BugreportParams.BUGREPORT_MODE_REMOTE
                && mode != BugreportParams.BUGREPORT_MODE_WEAR
                && mode != BugreportParams.BUGREPORT_MODE_TELEPHONY
                && mode != BugreportParams.BUGREPORT_MODE_WIFI) {
            Slog.w(TAG, "Unknown bugreport mode: " + mode);
            throw new IllegalArgumentException("Unknown bugreport mode: " + mode);
        }
    }

    /**
     * Validates that the current user is the primary user.
     *
     * @throws IllegalArgumentException if the current user is not the primary user
     */
    private void ensureIsPrimaryUser() {
        UserInfo currentUser = null;
        try {
            currentUser = ActivityManager.getService().getCurrentUser();
        } catch (RemoteException e) {
            // Impossible to get RemoteException for an in-process call.
        }

        UserInfo primaryUser = UserManager.get(mContext).getPrimaryUser();
        if (currentUser == null) {
            logAndThrow("No current user. Only primary user is allowed to take bugreports.");
        }
        if (primaryUser == null) {
            logAndThrow("No primary user. Only primary user is allowed to take bugreports.");
        }
        if (primaryUser.id != currentUser.id) {
            logAndThrow("Current user not primary user. Only primary user"
                    + " is allowed to take bugreports.");
        }
    }

    @GuardedBy("mLock")
    private void startBugreportLocked(int callingUid, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, IDumpstateListener listener) {
        if (isDumpstateBinderServiceRunningLocked()) {
            Slog.w(TAG, "'dumpstate' is already running. Cannot start a new bugreport"
                    + " while another one is currently in progress.");
            reportError(listener,
                    IDumpstateListener.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS);
            return;
        }

        IDumpstate ds = startAndGetDumpstateBinderServiceLocked();
        if (ds == null) {
            Slog.w(TAG, "Unable to get bugreport service");
            reportError(listener, IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
            return;
        }

        // Wrap the listener so we can intercept binder events directly.
        IDumpstateListener myListener = new DumpstateListener(listener, ds);
        try {
            ds.startBugreport(callingUid, callingPackage,
                    bugreportFd, screenshotFd, bugreportMode, myListener);
        } catch (RemoteException e) {
            reportError(listener, IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
        }
    }

    @GuardedBy("mLock")
    private boolean isDumpstateBinderServiceRunningLocked() {
        IDumpstate ds = IDumpstate.Stub.asInterface(ServiceManager.getService("dumpstate"));
        return ds != null;
    }

    /*
     * Start and get a handle to the native implementation of {@code IDumpstate} which does the
     * actual bugreport generation.
     *
     * <p>Generating bugreports requires root privileges. To limit the footprint
     * of the root access, the actual generation in Dumpstate binary is accessed as a
     * oneshot service 'bugreport'.
     *
     * <p>Note that starting the service is achieved through setting a system property, which is
     * not thread-safe. So the lock here offers thread-safety only among callers of the API.
     */
    @GuardedBy("mLock")
    private IDumpstate startAndGetDumpstateBinderServiceLocked() {
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

    private void reportError(IDumpstateListener listener, int errorCode) {
        try {
            listener.onError(errorCode);
        } catch (RemoteException e) {
            // Something went wrong in binder or app process. There's nothing to do here.
            Slog.w(TAG, "onError() transaction threw RemoteException: " + e.getMessage());
        }
    }

    private void logAndThrow(String message) {
        Slog.w(TAG, message);
        throw new IllegalArgumentException(message);
    }


    private final class DumpstateListener extends IDumpstateListener.Stub
            implements DeathRecipient {
        private final IDumpstateListener mListener;
        private final IDumpstate mDs;
        private boolean mDone = false;

        DumpstateListener(IDumpstateListener listener, IDumpstate ds) {
            mListener = listener;
            mDs = ds;
            try {
                mDs.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to register Death Recipient for IDumpstate", e);
            }
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            mListener.onProgress(progress);
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            synchronized (mLock) {
                mDone = true;
            }
            mListener.onError(errorCode);
        }

        @Override
        public void onFinished() throws RemoteException {
            synchronized (mLock) {
                mDone = true;
            }
            mListener.onFinished();
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (!mDone) {
                    // If we have not gotten a "done" callback this must be a crash.
                    Slog.e(TAG, "IDumpstate likely crashed. Notifying listener");
                    try {
                        mListener.onError(IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
                    } catch (RemoteException ignored) {
                        // If listener is not around, there isn't anything to do here.
                    }
                }
            }
            mDs.asBinder().unlinkToDeath(this, 0);
        }

        // Old methods; unused in the API flow.
        @Override
        public void onProgressUpdated(int progress) throws RemoteException {
        }

        @Override
        public void onMaxProgressUpdated(int maxProgress) throws RemoteException {
        }

        @Override
        public void onSectionComplete(String title, int status, int size, int durationMs)
                throws RemoteException {
        }
    }
}
