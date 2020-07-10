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

package com.android.server.biometrics.sensors.fingerprint;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetTracker;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 */
public class FingerprintService extends SystemService {

    protected static final String TAG = "FingerprintService";

    private final AppOpsManager mAppOps;
    private final LockoutResetTracker mLockoutResetTracker;
    private final GestureAvailabilityTracker mGestureAvailabilityTracker;
    private Fingerprint21 mFingerprint21;
    private IUdfpsOverlayController mUdfpsOverlayController;

    /**
     * Receives the incoming binder calls from FingerprintManager.
     */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override // Binder call
        public void generateChallenge(IBinder token, IFingerprintServiceReceiver receiver,
                String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mFingerprint21.scheduleGenerateChallenge(token, receiver, opPackageName);
        }

        @Override // Binder call
        public void revokeChallenge(IBinder token, String owner) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mFingerprint21.scheduleRevokeChallenge(token, owner);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] hardwareAuthToken, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName,
                Surface surface) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mFingerprint21.scheduleEnroll(token, hardwareAuthToken, userId, receiver, opPackageName,
                    surface);
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mFingerprint21.cancelEnrollment(token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long operationId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName,
                Surface surface) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(opPackageName, true /* requireForeground */, callingUid,
                    callingPid, callingUserId)) {
                Slog.w(TAG, "Authenticate rejecting package: " + opPackageName);
                return;
            }

            final boolean restricted = getContext().checkCallingPermission(MANAGE_FINGERPRINT)
                    != PackageManager.PERMISSION_GRANTED;
            final int statsClient = Utils.isKeyguard(getContext(), opPackageName)
                    ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER;
            mFingerprint21.scheduleAuthenticate(token, operationId, userId, 0 /* cookie */,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, surface,
                    restricted, statsClient);
        }

        @Override // Binder call
        public void prepareForAuthentication(IBinder token, long operationId, int userId,
                IBiometricSensorReceiver sensorReceiver, String opPackageName,
                int cookie, int callingUid, int callingPid, int callingUserId,
                Surface surface) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final boolean restricted = true; // BiometricPrompt is always restricted
            mFingerprint21.scheduleAuthenticate(token, operationId, userId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), opPackageName, surface,
                    restricted, BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT);
        }

        @Override // Binder call
        public void startPreparedClient(int cookie) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFingerprint21.startPreparedClient(cookie);
        }


        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(opPackageName, true /* requireForeground */, callingUid,
                    callingPid, callingUserId)) {
                Slog.w(TAG, "cancelAuthentication rejecting package: " + opPackageName);
                return;
            }

            mFingerprint21.cancelAuthentication(token);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final IBinder token, final String opPackageName,
                int callingUid, int callingPid, int callingUserId) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFingerprint21.cancelAuthentication(token);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mFingerprint21.scheduleRemove(token, receiver, fingerId, userId, opPackageName);
        }

        @Override
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mLockoutResetTracker.addCallback(callback, opPackageName);
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 0 && "--proto".equals(args[0])) {
                    mFingerprint21.dumpProto(fd);
                } else {
                    mFingerprint21.dumpInternal(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetected(String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                return mFingerprint21.isHardwareDetected();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void rename(final int fingerId, final int userId, final String name) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
                return;
            }

            mFingerprint21.rename(fingerId, userId, name);
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }
            return mFingerprint21.getEnrolledFingerprints(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }
            return mFingerprint21.getEnrolledFingerprints(userId).size() > 0;
        }

        @Override // Binder call
        public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFingerprint21.getLockoutModeForUser(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFingerprint21.getAuthenticatorId(userId);
        }

        @Override // Binder call
        public void resetLockout(int userId, byte [] hardwareAuthToken) {
            Utils.checkPermission(getContext(), RESET_FINGERPRINT_LOCKOUT);
            mFingerprint21.scheduleResetLockout(userId, hardwareAuthToken);
        }

        @Override
        public boolean isClientActive() {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            return mGestureAvailabilityTracker.isAnySensorActive();
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityTracker.registerCallback(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityTracker.removeCallback(callback);
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFingerprint21 = new Fingerprint21(getContext(), sensorId, mLockoutResetTracker,
                    mGestureAvailabilityTracker);
        }

        @Override
        public void onFingerDown(int x, int y, float minor, float major) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFingerprint21.onFingerDown(x, y, minor, major);
        }

        @Override
        public void onFingerUp() {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFingerprint21.onFingerUp();
        }

        @Override
        public boolean isUdfps(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFingerprint21.isUdfps();
        }

        @Override
        public void showUdfpsOverlay() {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (mUdfpsOverlayController == null) {
                Slog.e(TAG, "showUdfpsOverlay | mUdfpsOverlayController is null");
                return;
            }
            try {
                mUdfpsOverlayController.showUdfpsOverlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "showUdfpsOverlay | RemoteException: ", e);
            }
        }

        @Override
        public void hideUdfpsOverlay() {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (mUdfpsOverlayController == null) {
                Slog.e(TAG, "hideUdfpsOverlay | mUdfpsOverlayController is null");
                return;
            }
            try {
                mUdfpsOverlayController.hideUdfpsOverlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "hideUdfpsOverlay | RemoteException: ", e);
            }
        }

        @Override
        public void setUdfpsOverlayController(IUdfpsOverlayController controller) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mUdfpsOverlayController = controller;
        }
    }

    public FingerprintService(Context context) {
        super(context);
        mAppOps = context.getSystemService(AppOpsManager.class);
        mGestureAvailabilityTracker = new GestureAvailabilityTracker();
        mLockoutResetTracker = new LockoutResetTracker(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean canUseFingerprint(String opPackageName, boolean requireForeground, int uid,
            int pid, int userId) {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC);
        }

        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            return true; // System process (BiometricService, etc) is always allowed
        }
        if (Utils.isKeyguard(getContext(), opPackageName)) {
            return true;
        }
        if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; not a current user or profile");
            return false;
        }
        if (!checkAppOps(uid, opPackageName)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            return false;
        }
        if (requireForeground && !(isForegroundActivity(uid, pid))) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; not in foreground");
            return false;
        }
        return true;
    }

    private boolean checkAppOps(int uid, String opPackageName) {
        boolean appOpsOk = false;
        if (mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        } else if (mAppOps.noteOp(AppOpsManager.OP_USE_FINGERPRINT, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        }
        return appOpsOk;
    }

    private boolean isForegroundActivity(int uid, int pid) {
        try {
            final List<ActivityManager.RunningAppProcessInfo> procs =
                    ActivityManager.getService().getRunningAppProcesses();
            if (procs == null) {
                Slog.e(TAG, "Processes null, defaulting to true");
                return true;
            }

            int N = procs.size();
            for (int i = 0; i < N; i++) {
                ActivityManager.RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == pid && proc.uid == uid
                        && proc.importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    private native NativeHandle convertSurfaceToNativeHandle(Surface surface);
}
