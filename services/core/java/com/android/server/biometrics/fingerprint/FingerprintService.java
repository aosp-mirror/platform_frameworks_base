/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.biometrics.fingerprint;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_2.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.AuthenticationClient;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.ClientMonitor;
import com.android.server.biometrics.Constants;
import com.android.server.biometrics.EnumerateClient;
import com.android.server.biometrics.RemovalClient;
import com.android.server.biometrics.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 *
 * @hide
 */
public class FingerprintService extends BiometricServiceBase {

    protected static final String TAG = "FingerprintService";
    private static final boolean DEBUG = true;
    private static final String FP_DATA_DIR = "fpdata";
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.biometrics.fingerprint.ACTION_LOCKOUT_RESET";
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30 * 1000;
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";

    private final class ResetFailedAttemptsForUserRunnable implements Runnable {
        @Override
        public void run() {
            resetFailedAttemptsForUser(true /* clearAttemptCounter */,
                    ActivityManager.getCurrentUser());
        }
    }

    private final class LockoutReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.v(getTag(), "Resetting lockout: " + intent.getAction());
            if (getLockoutResetIntent().equals(intent.getAction())) {
                final int user = intent.getIntExtra(KEY_LOCKOUT_RESET_USER, 0);
                resetFailedAttemptsForUser(false /* clearAttemptCounter */, user);
            }
        }
    }

    private final class FingerprintAuthClient extends AuthenticationClientImpl {
        private final boolean mDetectOnly;

        @Override
        protected boolean isFingerprint() {
            return true;
        }

        public FingerprintAuthClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int targetUserId, int groupId, long opId,
                boolean restricted, String owner, int cookie,
                boolean requireConfirmation, boolean detectOnly) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId,
                    restricted, owner, cookie, requireConfirmation);
            mDetectOnly = detectOnly;
        }

        @Override
        protected int statsModality() {
            return FingerprintService.this.statsModality();
        }

        @Override
        public void resetFailedAttempts() {
            resetFailedAttemptsForUser(true /* clearAttemptCounter */,
                    ActivityManager.getCurrentUser());
        }

        @Override
        public boolean shouldFrameworkHandleLockout() {
            return true;
        }

        @Override
        public boolean wasUserDetected() {
            // TODO: Return a proper value for devices that use ERROR_TIMEOUT
            return false;
        }

        @Override
        public boolean isStrongBiometric() {
            return FingerprintService.this.isStrongBiometric();
        }

        @Override
        public int handleFailedAttempt() {
            final int currentUser = ActivityManager.getCurrentUser();
            mFailedAttempts.put(currentUser, mFailedAttempts.get(currentUser, 0) + 1);
            mTimedLockoutCleared.put(ActivityManager.getCurrentUser(), false);

            if (getLockoutMode() != AuthenticationClient.LOCKOUT_NONE) {
                scheduleLockoutResetForUser(currentUser);
            }

            return super.handleFailedAttempt();
        }

        boolean isDetectOnly() {
            return mDetectOnly;
        }
    }

    /**
     * Receives the incoming binder calls from FingerprintManager.
     */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        private static final int ENROLL_TIMEOUT_SEC = 60;

        /**
         * The following methods contain common code which is shared in biometrics/common.
         */

        @Override // Binder call
        public long preEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPreEnroll(token);
        }

        @Override // Binder call
        public int postEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPostEnroll(token);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int userId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName) {
            checkPermission(MANAGE_FINGERPRINT);

            final boolean restricted = isRestricted();
            final int groupId = userId; // default group for fingerprint enrollment
            final EnrollClientImpl client = new EnrollClientImpl(getContext(), mDaemonWrapper,
                    mHalDeviceId, token, new ServiceListenerImpl(receiver), mCurrentUserId, groupId,
                    cryptoToken, restricted, opPackageName, new int[0] /* disabledFeatures */,
                    ENROLL_TIMEOUT_SEC) {
                @Override
                public boolean shouldVibrate() {
                    return true;
                }

                @Override
                protected int statsModality() {
                    return FingerprintService.this.statsModality();
                }
            };

            enrollInternal(client, userId);
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            cancelEnrollmentInternal(token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long opId, final int userId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName) {
            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            // Clear calling identity when checking LockPatternUtils for StrongAuth flags.
            final long identity = Binder.clearCallingIdentity();
            try {
                if (isKeyguard && Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                    // If this happens, something in KeyguardUpdateMonitor is wrong.
                    // SafetyNet for b/79776455
                    EventLog.writeEvent(0x534e4554, "79776455");
                    Slog.e(TAG, "Authenticate invoked when user is encrypted or lockdown");
                    return;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            updateActiveGroup(userId, opPackageName);
            final boolean restricted = isRestricted();
            final AuthenticationClientImpl client = new FingerprintAuthClient(getContext(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver),
                    mCurrentUserId, userId, opId, restricted, opPackageName,
                    0 /* cookie */, false /* requireConfirmation */, false /* detectOnly */);
            authenticateInternal(client, opId, opPackageName);
        }

        @Override
        public void detectFingerprint(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFingerprint called from non-sysui package: " + opPackageName);
                return;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFingerprint invoked when user is not encrypted or lockdown");
                return;
            }

            Slog.d(TAG, "detectFingerprint, owner: " + opPackageName + ", user: " + userId);

            updateActiveGroup(userId, opPackageName);
            final boolean restricted = isRestricted();
            final int operationId = 0;
            final AuthenticationClientImpl client = new FingerprintAuthClient(getContext(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver),
                    mCurrentUserId, userId, operationId, restricted, opPackageName,
                    0 /* cookie */, false /* requireConfirmation */, true /* detectOnly */);
            authenticateInternal(client, operationId, opPackageName);
        }

        @Override // Binder call
        public void prepareForAuthentication(IBinder token, long opId, int groupId,
                IBiometricServiceReceiverInternal wrapperReceiver, String opPackageName,
                int cookie, int callingUid, int callingPid, int callingUserId) {
            checkPermission(MANAGE_BIOMETRIC);
            updateActiveGroup(groupId, opPackageName);
            final boolean restricted = true; // BiometricPrompt is always restricted
            final AuthenticationClientImpl client = new FingerprintAuthClient(getContext(),
                    mDaemonWrapper, mHalDeviceId, token,
                    new BiometricPromptServiceListenerImpl(wrapperReceiver),
                    mCurrentUserId, groupId, opId, restricted, opPackageName, cookie,
                    false /* requireConfirmation */, false /* detectOnly */);
            authenticateInternal(client, opId, opPackageName, callingUid, callingPid,
                    callingUserId);
        }

        @Override // Binder call
        public void startPreparedClient(int cookie) {
            checkPermission(MANAGE_BIOMETRIC);
            startCurrentClient(cookie);
        }


        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            cancelAuthenticationInternal(token, opPackageName);
        }

        @Override // Binder call
        public void cancelFingerprintDetect(final IBinder token, final String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFingerprintDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }
            cancelAuthenticationInternal(token, opPackageName);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final IBinder token, final String opPackageName,
                int callingUid, int callingPid, int callingUserId, boolean fromClient) {
            checkPermission(MANAGE_BIOMETRIC);
            cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid,
                    callingUserId, fromClient);
        }

        @Override // Binder call
        public void setActiveUser(final int userId) {
            checkPermission(MANAGE_FINGERPRINT);
            setActiveUserInternal(userId);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int groupId,
                final int userId, final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT);

            if (token == null) {
                Slog.w(TAG, "remove(): token is null");
                return;
            }

            final boolean restricted = isRestricted();
            final RemovalClient client = new RemovalClient(getContext(), getConstants(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver),
                    fingerId, groupId, userId, restricted, token.toString(), getBiometricUtils()) {
                @Override
                protected int statsModality() {
                    return FingerprintService.this.statsModality();
                }
            };
            removeInternal(client);
        }

        @Override // Binder call
        public void enumerate(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT);

            final boolean restricted = isRestricted();
            final EnumerateClient client = new EnumerateClient(getContext(), getConstants(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver), userId,
                    userId, restricted, getContext().getOpPackageName()) {
                @Override
                protected int statsModality() {
                    return FingerprintService.this.statsModality();
                }
            };
            enumerateInternal(client);
        }

        @Override
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback)
                throws RemoteException {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            FingerprintService.super.addLockoutResetCallback(callback);
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 0 && "--proto".equals(args[0])) {
                    dumpProto(fd);
                } else {
                    dumpInternal(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * The following methods don't use any common code from BiometricService
         */

        // TODO: refactor out common code here
        @Override // Binder call
        public boolean isHardwareDetected(String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                IBiometricsFingerprint daemon = getFingerprintDaemon();
                return daemon != null && mHalDeviceId != 0;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void rename(final int fingerId, final int groupId, final String name) {
            checkPermission(MANAGE_FINGERPRINT);
            if (!isCurrentUserOrProfile(groupId)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    getBiometricUtils().renameBiometricForUser(getContext(), groupId,
                            fingerId, name);
                }
            });
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            return FingerprintService.this.getEnrolledTemplates(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            return FingerprintService.this.hasEnrolledBiometrics(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            return FingerprintService.this.getAuthenticatorId(callingUserId);
        }

        @Override // Binder call
        public void resetTimeout(byte [] token) {
            checkPermission(RESET_FINGERPRINT_LOCKOUT);

            if (!FingerprintService.this.hasEnrolledBiometrics(mCurrentUserId)) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled");
                return;
            }

            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(mResetFailedAttemptsForCurrentUserRunnable);
        }

        @Override
        public boolean isClientActive() {
            checkPermission(MANAGE_FINGERPRINT);
            synchronized(FingerprintService.this) {
                return (getCurrentClient() != null) || (getPendingClient() != null);
            }
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.add(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.remove(callback);
        }

        @Override // Binder call
        public void initConfiguredStrength(int strength) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            initConfiguredStrengthInternal(strength);
        }
    }

    /**
     * Receives callbacks from the ClientMonitor implementations. The results are forwarded to
     * BiometricPrompt.
     */
    private class BiometricPromptServiceListenerImpl extends BiometricServiceListener {
        BiometricPromptServiceListenerImpl(IBiometricServiceReceiverInternal wrapperReceiver) {
            super(wrapperReceiver);
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode)
                throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAcquired(acquiredInfo, FingerprintManager.getAcquiredString(
                            getContext(), acquiredInfo, vendorCode));
            }
        }

        @Override
        public void onError(long deviceId, int error, int vendorCode, int cookie)
                throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onError(cookie, TYPE_FINGERPRINT, error, vendorCode);
            }
        }
    }

    /**
     * Receives callbacks from the ClientMonitor implementations. The results are forwarded to
     * the FingerprintManager.
     */
    private class ServiceListenerImpl implements ServiceListener {
        private IFingerprintServiceReceiver mFingerprintServiceReceiver;

        public ServiceListenerImpl(IFingerprintServiceReceiver receiver) {
            mFingerprintServiceReceiver = receiver;
        }

        @Override
        public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                final Fingerprint fp = (Fingerprint) identifier;
                mFingerprintServiceReceiver.onEnrollResult(fp.getDeviceId(), fp.getBiometricId(),
                        fp.getGroupId(), remaining);
            }
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                mFingerprintServiceReceiver.onAcquired(deviceId, acquiredInfo, vendorCode);
            }
        }

        @Override
        public void onAuthenticationSucceeded(long deviceId,
                BiometricAuthenticator.Identifier biometric, int userId)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                final ClientMonitor client = getCurrentClient();
                if (client instanceof FingerprintAuthClient
                        && ((FingerprintAuthClient) client).isDetectOnly()) {
                    mFingerprintServiceReceiver
                            .onFingerprintDetected(deviceId, userId, isStrongBiometric());
                } else if (biometric == null || biometric instanceof Fingerprint) {
                    mFingerprintServiceReceiver.onAuthenticationSucceeded(deviceId,
                            (Fingerprint) biometric, userId, isStrongBiometric());
                } else {
                    Slog.e(TAG, "onAuthenticationSucceeded received non-fingerprint biometric");
                }
            }
        }

        @Override
        public void onAuthenticationFailed(long deviceId) throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                mFingerprintServiceReceiver.onAuthenticationFailed(deviceId);
            }
        }

        @Override
        public void onError(long deviceId, int error, int vendorCode, int cookie)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                mFingerprintServiceReceiver.onError(deviceId, error, vendorCode);
            }
        }

        @Override
        public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                final Fingerprint fp = (Fingerprint) identifier;
                mFingerprintServiceReceiver.onRemoved(fp.getDeviceId(), fp.getBiometricId(),
                        fp.getGroupId(), remaining);
            }
        }

        @Override
        public void onEnumerated(BiometricAuthenticator.Identifier identifier, int remaining)
                throws RemoteException {
            if (mFingerprintServiceReceiver != null) {
                final Fingerprint fp = (Fingerprint) identifier;
                mFingerprintServiceReceiver.onEnumerated(fp.getDeviceId(), fp.getBiometricId(),
                        fp.getGroupId(), remaining);
            }
        }
    }

    private final FingerprintConstants mFingerprintConstants = new FingerprintConstants();
    private final CopyOnWriteArrayList<IFingerprintClientActiveCallback> mClientActiveCallbacks =
            new CopyOnWriteArrayList<>();

    @GuardedBy("this")
    private IBiometricsFingerprint mDaemon;
    private final SparseBooleanArray mTimedLockoutCleared;
    private final SparseIntArray mFailedAttempts;
    private final AlarmManager mAlarmManager;
    private final LockoutReceiver mLockoutReceiver = new LockoutReceiver();
    protected final ResetFailedAttemptsForUserRunnable mResetFailedAttemptsForCurrentUserRunnable =
            new ResetFailedAttemptsForUserRunnable();
    private final LockPatternUtils mLockPatternUtils;

    /**
     * Receives callbacks from the HAL.
     */
    private IBiometricsFingerprintClientCallback mDaemonCallback =
            new IBiometricsFingerprintClientCallback.Stub() {
        @Override
        public void onEnrollResult(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                final Fingerprint fingerprint =
                        new Fingerprint(getBiometricUtils().getUniqueName(getContext(), groupId),
                                groupId, fingerId, deviceId);
                FingerprintService.super.handleEnrollResult(fingerprint, remaining);
            });
        }

        @Override
        public void onAcquired(final long deviceId, final int acquiredInfo, final int vendorCode) {
            onAcquired_2_2(deviceId, acquiredInfo, vendorCode);
        }

        @Override
        public void onAcquired_2_2(long deviceId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                FingerprintService.super.handleAcquired(deviceId, acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int fingerId, final int groupId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                boolean authenticated = fingerId != 0;
                final ClientMonitor client = getCurrentClient();
                if (client instanceof FingerprintAuthClient) {
                    if (((FingerprintAuthClient) client).isDetectOnly()) {
                        Slog.w(TAG, "Detect-only. Device is encrypted or locked down");
                        authenticated = true;
                    }
                }

                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleAuthenticated(authenticated, fp, token);
            });
        }

        @Override
        public void onError(final long deviceId, final int error, final int vendorCode) {
            mHandler.post(() -> {
                FingerprintService.super.handleError(deviceId, error, vendorCode);
                // TODO: this chunk of code should be common to all biometric services
                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    // If we get HW_UNAVAILABLE, try to connect again later...
                    Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                    synchronized (this) {
                        mDaemon = null;
                        mHalDeviceId = 0;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                ClientMonitor client = getCurrentClient();
                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleRemoved(fp, remaining);
            });
        }

        @Override
        public void onEnumerate(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(() -> {
                final Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
                FingerprintService.super.handleEnumerate(fp, remaining);
            });

        }
    };

    /**
     * Wraps the HAL-specific code and is passed to the ClientMonitor implementations so that they
     * can be shared between the multiple biometric services.
     */
    private final DaemonWrapper mDaemonWrapper = new DaemonWrapper() {
        @Override
        public int authenticate(long operationId, int groupId) throws RemoteException {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "authenticate(): no fingerprint HAL!");
                return ERROR_ESRCH;
            }
            return daemon.authenticate(operationId, groupId);
        }

        @Override
        public int cancel() throws RemoteException {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "cancel(): no fingerprint HAL!");
                return ERROR_ESRCH;
            }
            return daemon.cancel();
        }

        @Override
        public int remove(int groupId, int biometricId) throws RemoteException {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "remove(): no fingerprint HAL!");
                return ERROR_ESRCH;
            }
            return daemon.remove(groupId, biometricId);
        }

        @Override
        public int enumerate() throws RemoteException {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "enumerate(): no fingerprint HAL!");
                return ERROR_ESRCH;
            }
            return daemon.enumerate();
        }

        @Override
        public int enroll(byte[] cryptoToken, int groupId, int timeout,
                ArrayList<Integer> disabledFeatures) throws RemoteException {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "enroll(): no fingerprint HAL!");
                return ERROR_ESRCH;
            }
            return daemon.enroll(cryptoToken, groupId, timeout);
        }

        @Override
        public void resetLockout(byte[] token) throws RemoteException {
            // TODO: confirm security token when we move timeout management into the HAL layer.
            Slog.e(TAG, "Not supported");
            return;
        }
    };

    public FingerprintService(Context context) {
        super(context);
        mTimedLockoutCleared = new SparseBooleanArray();
        mFailedAttempts = new SparseIntArray();
        mAlarmManager = context.getSystemService(AlarmManager.class);
        context.registerReceiver(mLockoutReceiver, new IntentFilter(getLockoutResetIntent()),
                getLockoutBroadcastPermission(), null /* handler */);
        mLockPatternUtils = new LockPatternUtils(context);
    }

    @Override
    public void onStart() {
        super.onStart();
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        SystemServerInitThreadPool.submit(this::getFingerprintDaemon, TAG + ".onStart");
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected DaemonWrapper getDaemonWrapper() {
        return mDaemonWrapper;
    }

    @Override
    protected BiometricUtils getBiometricUtils() {
        return FingerprintUtils.getInstance();
    }

    @Override
    protected Constants getConstants() {
        return mFingerprintConstants;
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_fingerprintMaxTemplatesPerUser);
        final int enrolled = FingerprintService.this.getEnrolledTemplates(userId).size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many fingerprints registered");
            return true;
        }
        return false;
    }

    @Override
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        mDaemon = null;
    }

    @Override
    protected void updateActiveGroup(int userId, String clientPackage) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();

        if (daemon != null) {
            try {
                userId = getUserOrWorkProfileId(clientPackage, userId);
                if (userId != mCurrentUserId) {
                    int firstSdkInt = Build.VERSION.DEVICE_INITIAL_SDK_INT;
                    if (firstSdkInt < Build.VERSION_CODES.BASE) {
                        Slog.e(TAG, "First SDK version " + firstSdkInt + " is invalid; must be " +
                                "at least VERSION_CODES.BASE");
                    }
                    File baseDir;
                    if (firstSdkInt <= Build.VERSION_CODES.O_MR1) {
                        baseDir = Environment.getUserSystemDirectory(userId);
                    } else {
                        baseDir = Environment.getDataVendorDeDirectory(userId);
                    }

                    File fpDir = new File(baseDir, FP_DATA_DIR);
                    if (!fpDir.exists()) {
                        if (!fpDir.mkdir()) {
                            Slog.v(TAG, "Cannot make directory: " + fpDir.getAbsolutePath());
                            return;
                        }
                        // Calling mkdir() from this process will create a directory with our
                        // permissions (inherited from the containing dir). This command fixes
                        // the label.
                        if (!SELinux.restorecon(fpDir)) {
                            Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }

                    daemon.setActiveGroup(userId, fpDir.getAbsolutePath());
                    mCurrentUserId = userId;
                }
                mAuthenticatorIds.put(userId,
                        hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId() : 0L);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    @Override
    protected String getLockoutResetIntent() {
        return ACTION_LOCKOUT_RESET;
    }

    @Override
    protected String getLockoutBroadcastPermission() {
        return RESET_FINGERPRINT_LOCKOUT;
    }

    @Override
    protected long getHalDeviceId() {
        return mHalDeviceId;
    }

    @Override
    protected boolean hasEnrolledBiometrics(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId).size() > 0;
    }

    @Override
    protected String getManageBiometricPermission() {
        return MANAGE_FINGERPRINT;
    }

    @Override
    protected void checkUseBiometricPermission() {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermission(USE_BIOMETRIC);
        }
    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
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

    @Override
    protected List<Fingerprint> getEnrolledTemplates(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
    }

    @Override
    protected void notifyClientActiveCallbacks(boolean isActive) {
        List<IFingerprintClientActiveCallback> callbacks = mClientActiveCallbacks;
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onClientActiveChanged(isActive);
            } catch (RemoteException re) {
                // If the remote is dead, stop notifying it
                mClientActiveCallbacks.remove(callbacks.get(i));
            }
        }
    }

    @Override
    protected int statsModality() {
        return BiometricsProtoEnums.MODALITY_FINGERPRINT;
    }

    @Override
    protected int getLockoutMode() {
        final int currentUser = ActivityManager.getCurrentUser();
        final int failedAttempts = mFailedAttempts.get(currentUser, 0);
        if (failedAttempts >= MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
            return AuthenticationClient.LOCKOUT_PERMANENT;
        } else if (failedAttempts > 0
                && !mTimedLockoutCleared.get(currentUser, false)
                && (failedAttempts % MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED == 0)) {
            return AuthenticationClient.LOCKOUT_TIMED;
        }
        return AuthenticationClient.LOCKOUT_NONE;
    }

    /** Gets the fingerprint daemon */
    private synchronized IBiometricsFingerprint getFingerprintDaemon() {
        if (mDaemon == null) {
            Slog.v(TAG, "mDaemon was null, reconnect to fingerprint");
            try {
                mDaemon = IBiometricsFingerprint.getService();
            } catch (java.util.NoSuchElementException e) {
                // Service doesn't exist or cannot be opened. Logged below.
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get biometric interface", e);
            }
            if (mDaemon == null) {
                Slog.w(TAG, "fingerprint HIDL not available");
                return null;
            }

            mDaemon.asBinder().linkToDeath(this, 0);

            try {
                mHalDeviceId = mDaemon.setNotify(mDaemonCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open fingerprint HAL", e);
                mDaemon = null; // try again later!
            }

            if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
            if (mHalDeviceId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
                doTemplateCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w(TAG, "Failed to open Fingerprint HAL!");
                MetricsLogger.count(getContext(), "fingerprintd_openhal_error", 1);
                mDaemon = null;
            }
        }
        return mDaemon;
    }

    private long startPreEnroll(IBinder token) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no fingerprint HAL!");
            return 0;
        }
        try {
            return daemon.preEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPreEnroll failed", e);
        }
        return 0;
    }

    private int startPostEnroll(IBinder token) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPostEnroll: no fingerprint HAL!");
            return 0;
        }
        try {
            return daemon.postEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPostEnroll failed", e);
        }
        return 0;
    }

    // Attempt counter should only be cleared when Keyguard goes away or when
    // a biometric is successfully authenticated. Lockout should eventually be done below the HAL.
    // See AuthenticationClient#shouldFrameworkHandleLockout().
    private void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {
        if (DEBUG && getLockoutMode() != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(getTag(), "Reset biometric lockout, clearAttemptCounter=" + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            mFailedAttempts.put(userId, 0);
        }
        mTimedLockoutCleared.put(userId, true);
        // If we're asked to reset failed attempts externally (i.e. from Keyguard),
        // the alarm might still be pending; remove it.
        cancelLockoutResetForUser(userId);
        notifyLockoutResetMonitors();
    }


    private void cancelLockoutResetForUser(int userId) {
        mAlarmManager.cancel(getLockoutResetIntentForUser(userId));
    }

    private void scheduleLockoutResetForUser(int userId) {
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS,
                getLockoutResetIntentForUser(userId));
    }

    private PendingIntent getLockoutResetIntentForUser(int userId) {
        return PendingIntent.getBroadcast(getContext(), userId,
                new Intent(getLockoutResetIntent()).putExtra(KEY_LOCKOUT_RESET_USER, userId),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = getBiometricUtils().getBiometricsForUser(getContext(), userId).size();
                PerformanceStats stats = mPerformanceMap.get(userId);
                PerformanceStats cryptoStats = mCryptoPerformanceMap.get(userId);
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
                set.put("accept", (stats != null) ? stats.accept : 0);
                set.put("reject", (stats != null) ? stats.reject : 0);
                set.put("acquire", (stats != null) ? stats.acquire : 0);
                set.put("lockout", (stats != null) ? stats.lockout : 0);
                set.put("permanentLockout", (stats != null) ? stats.permanentLockout : 0);
                // cryptoStats measures statistics about secure fingerprint transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", (cryptoStats != null) ? cryptoStats.accept : 0);
                set.put("rejectCrypto", (cryptoStats != null) ? cryptoStats.reject : 0);
                set.put("acquireCrypto", (cryptoStats != null) ? cryptoStats.acquire : 0);
                set.put("lockoutCrypto", (cryptoStats != null) ? cryptoStats.lockout : 0);
                set.put("permanentLockoutCrypto",
                    (cryptoStats != null) ? cryptoStats.permanentLockout : 0);
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + mHALDeathCount);
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FingerprintServiceDumpProto.USERS);

            proto.write(FingerprintUserStatsProto.USER_ID, userId);
            proto.write(FingerprintUserStatsProto.NUM_FINGERPRINTS,
                    getBiometricUtils().getBiometricsForUser(getContext(), userId).size());

            // Normal fingerprint authentications (e.g. lockscreen)
            final PerformanceStats normal = mPerformanceMap.get(userId);
            if (normal != null) {
                final long countsToken = proto.start(FingerprintUserStatsProto.NORMAL);
                proto.write(PerformanceStatsProto.ACCEPT, normal.accept);
                proto.write(PerformanceStatsProto.REJECT, normal.reject);
                proto.write(PerformanceStatsProto.ACQUIRE, normal.acquire);
                proto.write(PerformanceStatsProto.LOCKOUT, normal.lockout);
                proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, normal.permanentLockout);
                proto.end(countsToken);
            }

            // Statistics about secure fingerprint transactions (e.g. to unlock password
            // storage, make secure purchases, etc.)
            final PerformanceStats crypto = mCryptoPerformanceMap.get(userId);
            if (crypto != null) {
                final long countsToken = proto.start(FingerprintUserStatsProto.CRYPTO);
                proto.write(PerformanceStatsProto.ACCEPT, crypto.accept);
                proto.write(PerformanceStatsProto.REJECT, crypto.reject);
                proto.write(PerformanceStatsProto.ACQUIRE, crypto.acquire);
                proto.write(PerformanceStatsProto.LOCKOUT, crypto.lockout);
                proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, crypto.permanentLockout);
                proto.end(countsToken);
            }

            proto.end(userToken);
        }
        proto.flush();
        mPerformanceMap.clear();
        mCryptoPerformanceMap.clear();
    }
}
