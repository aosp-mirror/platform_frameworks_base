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

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.BugreportParams;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemConfig;

import java.io.FileDescriptor;
import java.util.Objects;

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
    private final TelephonyManager mTelephonyManager;
    private final ArraySet<String> mBugreportWhitelistedPackages;

    BugreportManagerServiceImpl(Context context) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mBugreportWhitelistedPackages =
                SystemConfig.getInstance().getBugreportWhitelistedPackages();
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(int callingUidUnused, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, IDumpstateListener listener, boolean isScreenshotRequested) {
        Objects.requireNonNull(callingPackage);
        Objects.requireNonNull(bugreportFd);
        Objects.requireNonNull(listener);
        validateBugreportMode(bugreportMode);

        int callingUid = Binder.getCallingUid();
        enforcePermission(callingPackage, callingUid, bugreportMode
                == BugreportParams.BUGREPORT_MODE_TELEPHONY /* checkCarrierPrivileges */);
        final long identity = Binder.clearCallingIdentity();
        try {
            ensureUserCanTakeBugReport(bugreportMode);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        synchronized (mLock) {
            startBugreportLocked(callingUid, callingPackage, bugreportFd, screenshotFd,
                    bugreportMode, listener, isScreenshotRequested);
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP) // or carrier privileges
    public void cancelBugreport(int callingUidUnused, String callingPackage) {
        int callingUid = Binder.getCallingUid();
        enforcePermission(callingPackage, callingUid, true /* checkCarrierPrivileges */);

        synchronized (mLock) {
            IDumpstate ds = getDumpstateBinderServiceLocked();
            if (ds == null) {
                Slog.w(TAG, "cancelBugreport: Could not find native dumpstate service");
                return;
            }
            try {
                // Note: this may throw SecurityException back out to the caller if they aren't
                // allowed to cancel the report, in which case we should NOT be setting ctl.stop,
                // since that would unintentionally kill some other app's bugreport, which we
                // specifically disallow.
                ds.cancelBugreport(callingUid, callingPackage);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException in cancelBugreport", e);
            }
            // This tells init to cancel bugreportd service. Note that this is achieved through
            // setting a system property which is not thread-safe. So the lock here offers
            // thread-safety only among callers of the API.
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

    private void enforcePermission(
            String callingPackage, int callingUid, boolean checkCarrierPrivileges) {
        mAppOps.checkPackage(callingUid, callingPackage);

        // To gain access through the DUMP permission, the OEM has to allow this package explicitly
        // via sysconfig and privileged permissions.
        if (mBugreportWhitelistedPackages.contains(callingPackage)
                && mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                        == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // For carrier privileges, this can include user-installed apps. This is essentially a
        // function of the current active SIM(s) in the device to let carrier apps through.
        final long token = Binder.clearCallingIdentity();
        try {
            if (checkCarrierPrivileges
                    && mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(callingPackage)
                            == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        String message =
                callingPackage
                        + " does not hold the DUMP permission or is not bugreport-whitelisted "
                        + (checkCarrierPrivileges ? "and does not have carrier privileges " : "")
                        + "to request a bugreport";
        Slog.w(TAG, message);
        throw new SecurityException(message);
    }

    /**
     * Validates that the current user is the primary user or when bugreport is requested remotely
     * and current user is affiliated user.
     *
     * @throws IllegalArgumentException if the current user is not the primary user
     */
    private void ensureUserCanTakeBugReport(int bugreportMode) {
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
            if (bugreportMode == BugreportParams.BUGREPORT_MODE_REMOTE
                    && isCurrentUserAffiliated(currentUser.id)) {
                return;
            }
            logAndThrow("Current user not primary user. Only primary user"
                    + " is allowed to take bugreports.");
        }
    }

    /**
     * Returns {@code true} if the device has device owner and the current user is affiliated
     * with the device owner.
     */
    private boolean isCurrentUserAffiliated(int currentUserId) {
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        int deviceOwnerUid = dpm.getDeviceOwnerUserId();
        if (deviceOwnerUid == UserHandle.USER_NULL) {
            return false;
        }

        int callingUserId = UserHandle.getUserId(Binder.getCallingUid());

        Slog.i(TAG, "callingUid: " + callingUserId + " deviceOwnerUid: " + deviceOwnerUid
                + " currentUserId: " + currentUserId);

        if (callingUserId != deviceOwnerUid) {
            logAndThrow("Caller is not device owner on provisioned device.");
        }
        if (!dpm.isAffiliatedUser(currentUserId)) {
            logAndThrow("Current user is not affiliated to the device owner.");
        }
        return true;
    }

    @GuardedBy("mLock")
    private void startBugreportLocked(int callingUid, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, IDumpstateListener listener, boolean isScreenshotRequested) {
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
                    bugreportFd, screenshotFd, bugreportMode, myListener, isScreenshotRequested);
        } catch (RemoteException e) {
            // bugreportd service is already started now. We need to kill it to manage the
            // lifecycle correctly. If we don't subsequent callers will get
            // BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS error.
            // Note that listener will be notified by the death recipient below.
            cancelBugreport(callingUid, callingPackage);
        }
    }

    @GuardedBy("mLock")
    private boolean isDumpstateBinderServiceRunningLocked() {
        return getDumpstateBinderServiceLocked() != null;
    }

    @GuardedBy("mLock")
    @Nullable
    private IDumpstate getDumpstateBinderServiceLocked() {
        // Note that the binder service on the native side is "dumpstate".
        return IDumpstate.Stub.asInterface(ServiceManager.getService("dumpstate"));
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
            ds = getDumpstateBinderServiceLocked();
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
        public void onScreenshotTaken(boolean success) throws RemoteException {
            mListener.onScreenshotTaken(success);
        }

        @Override
        public void onUiIntensiveBugreportDumpsFinished() throws RemoteException {
            mListener.onUiIntensiveBugreportDumpsFinished();
        }

        @Override
        public void binderDied() {
            try {
                // Allow a small amount of time for any error or finished callbacks to be made.
                // This ensures that the listener does not receive an erroneous runtime error
                // callback.
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
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
    }
}
