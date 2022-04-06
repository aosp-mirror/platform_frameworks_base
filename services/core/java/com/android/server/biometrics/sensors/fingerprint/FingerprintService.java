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
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintServiceReceiver;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IFingerprintStateListener;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 */
public class FingerprintService extends SystemService {

    protected static final String TAG = "FingerprintService";

    private final Object mLock = new Object();
    private final AppOpsManager mAppOps;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    private final FingerprintServiceWrapper mServiceWrapper;
    @NonNull private final List<ServiceProvider> mServiceProviders;
    @NonNull private final FingerprintStateCallback mFingerprintStateCallback;
    @NonNull private final Handler mHandler;

    @GuardedBy("mLock")
    @NonNull private final RemoteCallbackList<IFingerprintAuthenticatorsRegisteredCallback>
            mAuthenticatorsRegisteredCallbacks;

    @GuardedBy("mLock")
    @NonNull private final List<FingerprintSensorPropertiesInternal> mSensorProps;

    /**
     * Registers FingerprintStateListener in list stored by FingerprintService
     * @param listener new FingerprintStateListener being added
     */
    public void registerFingerprintStateListener(@NonNull IFingerprintStateListener listener) {
        mFingerprintStateCallback.registerFingerprintStateListener(listener);
        broadcastCurrentEnrollmentState(listener);
    }

    /**
     * @param listener if non-null, notifies only this listener. if null, notifies all listeners
     *                 in {@link FingerprintStateCallback}. This is slightly ugly, but reduces
     *                 redundant code.
     */
    private void broadcastCurrentEnrollmentState(@Nullable IFingerprintStateListener listener) {
        final UserManager um = UserManager.get(getContext());
        synchronized (mLock) {
            // Update the new listener with current state of all sensors
            for (FingerprintSensorPropertiesInternal prop : mSensorProps) {
                final ServiceProvider provider = getProviderForSensor(prop.sensorId);
                for (UserInfo userInfo : um.getAliveUsers()) {
                    final boolean enrolled = !provider
                            .getEnrolledFingerprints(prop.sensorId, userInfo.id).isEmpty();

                    // Defer this work and allow the loop to release the lock sooner
                    mHandler.post(() -> {
                        if (listener != null) {
                            mFingerprintStateCallback.notifyFingerprintEnrollmentStateChanged(
                                    listener, userInfo.id, prop.sensorId, enrolled);
                        } else {
                            mFingerprintStateCallback.notifyAllFingerprintEnrollmentStateChanged(
                                    userInfo.id, prop.sensorId, enrolled);
                        }
                    });
                }
            }
        }
    }

    /**
     * Receives the incoming binder calls from FingerprintManager.
     */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), TEST_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);

            if (provider == null) {
                Slog.w(TAG, "Null provider for createTestSession, sensorId: " + sensorId);
                return null;
            }

            return provider.createTestSession(sensorId, callback, opPackageName);
        }

        @Override
        public byte[] dumpSensorServiceStateProto(int sensorId, boolean clearSchedulerBuffer) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ProtoOutputStream proto = new ProtoOutputStream();
            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider != null) {
                provider.dumpProtoState(sensorId, proto, clearSchedulerBuffer);
            }
            proto.flush();
            return proto.getBytes();
        }

        @Override // Binder call
        public List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal(
                @NonNull String opPackageName) {
            if (getContext().checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                    != PackageManager.PERMISSION_GRANTED) {
                Utils.checkPermission(getContext(), TEST_BIOMETRIC);
            }

            return FingerprintService.this.getSensorProperties();
        }

        @Override
        public FingerprintSensorPropertiesInternal getSensorProperties(int sensorId,
                @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for getSensorProperties, sensorId: " + sensorId
                        + ", caller: " + opPackageName);
                return null;
            }
            return provider.getSensorProperties(sensorId);
        }

        @Override // Binder call
        public void generateChallenge(IBinder token, int sensorId, int userId,
                IFingerprintServiceReceiver receiver, String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for generateChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleGenerateChallenge(sensorId, userId, token, receiver, opPackageName);
        }

        @Override // Binder call
        public void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName,
                long challenge) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName,
                    challenge);
        }

        @Override // Binder call
        public long enroll(final IBinder token, @NonNull final byte[] hardwareAuthToken,
                final int userId, final IFingerprintServiceReceiver receiver,
                final String opPackageName, @FingerprintManager.EnrollReason int enrollReason) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return -1;
            }

            return provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, enrollReason);
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token, long requestId) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelEnrollment");
                return;
            }

            provider.second.cancelEnrollment(provider.first, token, requestId);
        }

        @SuppressWarnings("deprecation")
        @Override // Binder call
        public long authenticate(
                final IBinder token,
                final long operationId,
                final int sensorId,
                final int userId,
                final IFingerprintServiceReceiver receiver,
                final String opPackageName,
                final String attributionTag,
                boolean ignoreEnrollmentState) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(
                    opPackageName,
                    attributionTag,
                    true /* requireForeground */,
                    callingUid,
                    callingPid,
                    callingUserId)) {
                Slog.w(TAG, "Authenticate rejecting package: " + opPackageName);
                return -1;
            }

            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            // Clear calling identity when checking LockPatternUtils for StrongAuth flags.
            final long identity1 = Binder.clearCallingIdentity();
            try {
                if (isKeyguard && Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                    // If this happens, something in KeyguardUpdateMonitor is wrong.
                    // SafetyNet for b/79776455
                    EventLog.writeEvent(0x534e4554, "79776455");
                    Slog.e(TAG, "Authenticate invoked when user is encrypted or lockdown");
                    return -1;
                }
            } finally {
                Binder.restoreCallingIdentity(identity1);
            }

            final boolean restricted = getContext().checkCallingPermission(MANAGE_FINGERPRINT)
                    != PackageManager.PERMISSION_GRANTED;
            final int statsClient = isKeyguard ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER;

            final Pair<Integer, ServiceProvider> provider;
            if (sensorId == FingerprintManager.SENSOR_ID_ANY) {
                provider = getSingleProvider();
            } else {
                Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
                provider = new Pair<>(sensorId, getProviderForSensor(sensorId));
            }
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }

            final FingerprintSensorPropertiesInternal sensorProps =
                    provider.second.getSensorProperties(sensorId);
            if (!isKeyguard && !Utils.isSettings(getContext(), opPackageName)
                    && sensorProps != null && sensorProps.isAnyUdfpsType()) {
                final long identity2 = Binder.clearCallingIdentity();
                try {
                    return authenticateWithPrompt(operationId, sensorProps, userId, receiver,
                            ignoreEnrollmentState);
                } finally {
                    Binder.restoreCallingIdentity(identity2);
                }
            }
            return provider.second.scheduleAuthenticate(provider.first, token, operationId, userId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), opPackageName,
                    restricted, statsClient, isKeyguard);
        }

        private long authenticateWithPrompt(
                final long operationId,
                @NonNull final FingerprintSensorPropertiesInternal props,
                final int userId,
                final IFingerprintServiceReceiver receiver,
                boolean ignoreEnrollmentState) {

            final Context context = getUiContext();
            final Executor executor = context.getMainExecutor();

            final BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(context)
                    .setTitle(context.getString(R.string.biometric_dialog_default_title))
                    .setSubtitle(context.getString(R.string.fingerprint_dialog_default_subtitle))
                    .setNegativeButton(
                            context.getString(R.string.cancel),
                            executor,
                            (dialog, which) -> {
                                try {
                                    receiver.onError(
                                            FINGERPRINT_ERROR_USER_CANCELED, 0 /* vendorCode */);
                                } catch (RemoteException e) {
                                    Slog.e(TAG, "Remote exception in negative button onClick()", e);
                                }
                            })
                    .setAllowedSensorIds(new ArrayList<>(
                            Collections.singletonList(props.sensorId)))
                    .setIgnoreEnrollmentState(ignoreEnrollmentState)
                    .build();

            final BiometricPrompt.AuthenticationCallback promptCallback =
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            try {
                                if (FingerprintUtils.isKnownErrorCode(errorCode)) {
                                    receiver.onError(errorCode, 0 /* vendorCode */);
                                } else {
                                    receiver.onError(FINGERPRINT_ERROR_VENDOR, errorCode);
                                }
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Remote exception in onAuthenticationError()", e);
                            }
                        }

                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            final Fingerprint fingerprint = new Fingerprint("", 0, 0L);
                            final boolean isStrong = props.sensorStrength == STRENGTH_STRONG;
                            try {
                                receiver.onAuthenticationSucceeded(fingerprint, userId, isStrong);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Remote exception in onAuthenticationSucceeded()", e);
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            try {
                                receiver.onAuthenticationFailed();
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Remote exception in onAuthenticationFailed()", e);
                            }
                        }

                        @Override
                        public void onAuthenticationAcquired(int acquireInfo) {
                            try {
                                if (FingerprintUtils.isKnownAcquiredCode(acquireInfo)) {
                                    receiver.onAcquired(acquireInfo, 0 /* vendorCode */);
                                } else {
                                    receiver.onAcquired(FINGERPRINT_ACQUIRED_VENDOR, acquireInfo);
                                }
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Remote exception in onAuthenticationAcquired()", e);
                            }
                        }
                    };

            return biometricPrompt.authenticateUserForOperation(
                    new CancellationSignal(), executor, promptCallback, userId, operationId);
        }

        @Override
        public long detectFingerprint(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFingerprint called from non-sysui package: " + opPackageName);
                return -1;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFingerprint invoked when user is not encrypted or lockdown");
                return -1;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFingerprint");
                return -1;
            }

            return provider.second.scheduleFingerDetect(provider.first, token, userId,
                    new ClientMonitorCallbackConverter(receiver), opPackageName,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @Override // Binder call
        public void prepareForAuthentication(int sensorId, IBinder token, long operationId,
                int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
                long requestId, int cookie, boolean allowBackgroundAuthentication) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for prepareForAuthentication");
                return;
            }

            final boolean restricted = true; // BiometricPrompt is always restricted
            provider.scheduleAuthenticate(sensorId, token, operationId, userId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), opPackageName, requestId,
                    restricted, BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    allowBackgroundAuthentication);
        }

        @Override // Binder call
        public void startPreparedClient(int sensorId, int cookie) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for startPreparedClient");
                return;
            }

            provider.startPreparedClient(sensorId, cookie);
        }

        @Override // Binder call
        public void cancelAuthentication(
                final IBinder token,
                final String opPackageName,
                final String attributionTag,
                long requestId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(
                    opPackageName,
                    attributionTag,
                    true /* requireForeground */,
                    callingUid,
                    callingPid,
                    callingUserId)) {
                Slog.w(TAG, "cancelAuthentication rejecting package: " + opPackageName);
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @Override // Binder call
        public void cancelFingerprintDetect(final IBinder token, final String opPackageName,
                final long requestId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFingerprintDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            // For IBiometricsFingerprint2.1, cancelling fingerprint detect is the same as
            // cancelling authentication.
            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelFingerprintDetect");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final int sensorId, final IBinder token,
                final String opPackageName, final long requestId) {

            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            Slog.d(TAG, "cancelAuthenticationFromService, sensorId: " + sensorId);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token, requestId);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }
            provider.second.scheduleRemove(provider.first, token, receiver, fingerId, userId,
                    opPackageName);
        }

        @Override // Binder call
        public void removeAll(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final FingerprintServiceReceiver internalReceiver = new FingerprintServiceReceiver() {
                int sensorsFinishedRemoving = 0;
                final int numSensors = getSensorPropertiesInternal(
                        getContext().getOpPackageName()).size();
                @Override
                public void onRemoved(Fingerprint fp, int remaining) throws RemoteException {
                    if (remaining == 0) {
                        sensorsFinishedRemoving++;
                        Slog.d(TAG, "sensorsFinishedRemoving: " + sensorsFinishedRemoving
                                + ", numSensors: " + numSensors);
                        if (sensorsFinishedRemoving == numSensors) {
                            receiver.onRemoved(null, 0 /* remaining */);
                        }
                    }
                }
            };

            // This effectively iterates through all sensors, but has to do so by finding all
            // sensors under each provider.
            for (ServiceProvider provider : mServiceProviders) {
                List<FingerprintSensorPropertiesInternal> props = provider.getSensorProperties();
                for (FingerprintSensorPropertiesInternal prop : props) {
                    provider.scheduleRemoveAll(prop.sensorId, token, internalReceiver, userId,
                            opPackageName);
                }
            }
        }

        @Override // Binder call
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mLockoutResetDispatcher.addCallback(callback, opPackageName);
        }

        @Override // Binder call
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 1 && "--proto".equals(args[0]) && "--state".equals(args[1])) {
                    final ProtoOutputStream proto = new ProtoOutputStream(fd);
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto, false);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            pw.println("Dumping for sensorId: " + props.sensorId
                                    + ", provider: " + provider.getClass().getSimpleName());
                            pw.println("Fps state: "
                                    + mFingerprintStateCallback.getFingerprintState());
                            provider.dumpInternal(props.sensorId, pw);
                            pw.println();
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetectedDeprecated(String opPackageName, String attributionTag) {
            if (!canUseFingerprint(
                    opPackageName,
                    attributionTag,
                    false /* foregroundOnly */,
                    Binder.getCallingUid(),
                    Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                final Pair<Integer, ServiceProvider> provider = getSingleProvider();
                if (provider == null) {
                    Slog.w(TAG, "Null provider for isHardwareDetectedDeprecated, caller: "
                            + opPackageName);
                    return false;
                }
                return provider.second.isHardwareDetected(provider.first);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                return false;
            }

            return provider.isHardwareDetected(sensorId);
        }

        @Override // Binder call
        public void rename(final int fingerId, final int userId, final String name) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for rename");
                return;
            }

            provider.second.rename(provider.first, fingerId, userId, name);
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(
                int userId, String opPackageName, String attributionTag) {
            if (!canUseFingerprint(
                    opPackageName,
                    attributionTag,
                    false /* foregroundOnly */,
                    Binder.getCallingUid(),
                    Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            return FingerprintService.this.getEnrolledFingerprintsDeprecated(userId, opPackageName);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprintsDeprecated(
                int userId, String opPackageName, String attributionTag) {
            if (!canUseFingerprint(
                    opPackageName,
                    attributionTag,
                    false /* foregroundOnly */,
                    Binder.getCallingUid(),
                    Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }
            return !FingerprintService.this.getEnrolledFingerprintsDeprecated(userId, opPackageName)
                    .isEmpty();
        }

        public boolean hasEnrolledFingerprints(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for hasEnrolledFingerprints, caller: " + opPackageName);
                return false;
            }

            return provider.getEnrolledFingerprints(sensorId, userId).size() > 0;
        }

        @Override // Binder call
        public @LockoutTracker.LockoutMode int getLockoutModeForUser(int sensorId, int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getLockoutModeForUser");
                return LockoutTracker.LOCKOUT_NONE;
            }
            return provider.getLockoutModeForUser(sensorId, userId);
        }

        @Override
        public void invalidateAuthenticatorId(int sensorId, int userId,
                IInvalidationCallback callback) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for invalidateAuthenticatorId");
                return;
            }
            provider.scheduleInvalidateAuthenticatorId(sensorId, userId, callback);
        }

        @Override // Binder call
        public long getAuthenticatorId(int sensorId, int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getAuthenticatorId");
                return 0;
            }
            return provider.getAuthenticatorId(sensorId, userId);
        }

        @Override // Binder call
        public void resetLockout(IBinder token, int sensorId, int userId,
                @Nullable byte[] hardwareAuthToken, String opPackageName) {
            Utils.checkPermission(getContext(), RESET_FINGERPRINT_LOCKOUT);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @Override
        public boolean isClientActive() {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            return mGestureAvailabilityDispatcher.isAnySensorActive();
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityDispatcher.registerCallback(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            Utils.checkPermission(getContext(), MANAGE_FINGERPRINT);
            mGestureAvailabilityDispatcher.removeCallback(callback);
        }

        private void addHidlProviders(List<FingerprintSensorPropertiesInternal> hidlSensors) {
            for (FingerprintSensorPropertiesInternal hidlSensor : hidlSensors) {
                final Fingerprint21 fingerprint21;
                if ((Build.IS_USERDEBUG || Build.IS_ENG)
                        && getContext().getResources().getBoolean(R.bool.allow_test_udfps)
                        && Settings.Secure.getIntForUser(getContext().getContentResolver(),
                        Fingerprint21UdfpsMock.CONFIG_ENABLE_TEST_UDFPS, 0 /* default */,
                        UserHandle.USER_CURRENT) != 0) {
                    fingerprint21 = Fingerprint21UdfpsMock.newInstance(getContext(),
                            mFingerprintStateCallback, hidlSensor,
                            mLockoutResetDispatcher, mGestureAvailabilityDispatcher,
                            BiometricContext.getInstance(getContext()));
                } else {
                    fingerprint21 = Fingerprint21.newInstance(getContext(),
                            mFingerprintStateCallback, hidlSensor, mHandler,
                            mLockoutResetDispatcher, mGestureAvailabilityDispatcher);
                }
                mServiceProviders.add(fingerprint21);
            }
        }

        private void addAidlProviders() {
            final String[] instances = ServiceManager.getDeclaredInstances(IFingerprint.DESCRIPTOR);
            if (instances == null || instances.length == 0) {
                return;
            }
            for (String instance : instances) {
                final String fqName = IFingerprint.DESCRIPTOR + "/" + instance;
                final IFingerprint fp = IFingerprint.Stub.asInterface(
                        Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName)));
                if (fp == null) {
                    Slog.e(TAG, "Unable to get declared service: " + fqName);
                    continue;
                }
                try {
                    final SensorProps[] props = fp.getSensorProps();
                    final FingerprintProvider provider =
                            new FingerprintProvider(getContext(), mFingerprintStateCallback, props,
                                    instance, mLockoutResetDispatcher,
                                    mGestureAvailabilityDispatcher,
                                    BiometricContext.getInstance(getContext()));
                    mServiceProviders.add(provider);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception in getSensorProps: " + fqName);
                }
            }
        }

        @Override // Binder call
        public void registerAuthenticators(
                @NonNull List<FingerprintSensorPropertiesInternal> hidlSensors) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            // Some HAL might not be started before the system service and will cause the code below
            // to wait, and some of the operations below might take a significant amount of time to
            // complete (calls to the HALs). To avoid blocking the rest of system server we put
            // this on a background thread.
            final ServiceThread thread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                    true /* allowIo */);
            thread.start();
            final Handler handler = new Handler(thread.getLooper());

            handler.post(() -> {
                addHidlProviders(hidlSensors);
                addAidlProviders();

                final IBiometricService biometricService = IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE));

                // Register each sensor individually with BiometricService
                for (ServiceProvider provider : mServiceProviders) {
                    final List<FingerprintSensorPropertiesInternal> props =
                            provider.getSensorProperties();
                    for (FingerprintSensorPropertiesInternal prop : props) {
                        final int sensorId = prop.sensorId;
                        final @BiometricManager.Authenticators.Types int strength =
                                Utils.propertyStrengthToAuthenticatorStrength(prop.sensorStrength);
                        final FingerprintAuthenticator authenticator = new FingerprintAuthenticator(
                                mServiceWrapper, sensorId);
                        try {
                            biometricService.registerAuthenticator(sensorId, TYPE_FINGERPRINT,
                                    strength, authenticator);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception when registering sensorId: " + sensorId);
                        }
                    }
                }

                synchronized (mLock) {
                    for (ServiceProvider provider : mServiceProviders) {
                        mSensorProps.addAll(provider.getSensorProperties());
                    }
                }

                broadcastCurrentEnrollmentState(null); // broadcasts to all listeners
                broadcastAllAuthenticatorsRegistered();
            });
        }

        @Override
        public void addAuthenticatorsRegisteredCallback(
                IFingerprintAuthenticatorsRegisteredCallback callback) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (callback == null) {
                Slog.e(TAG, "addAuthenticatorsRegisteredCallback, callback is null");
                return;
            }

            final boolean registered;
            final boolean hasSensorProps;
            synchronized (mLock) {
                registered = mAuthenticatorsRegisteredCallbacks.register(callback);
                hasSensorProps = !mSensorProps.isEmpty();
            }
            if (registered && hasSensorProps) {
                broadcastAllAuthenticatorsRegistered();
            } else if (!registered) {
                Slog.e(TAG, "addAuthenticatorsRegisteredCallback failed to register callback");
            }
        }

        @Override
        public void onPointerDown(long requestId, int sensorId, int x, int y,
                float minor, float major) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerDown, sensorId: " + sensorId);
                return;
            }
            provider.onPointerDown(requestId, sensorId, x, y, minor, major);
        }

        @Override
        public void onPointerUp(long requestId, int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerUp, sensorId: " + sensorId);
                return;
            }
            provider.onPointerUp(requestId, sensorId);
        }

        @Override
        public void onUiReady(long requestId, int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onUiReady, sensorId: " + sensorId);
                return;
            }
            provider.onUiReady(requestId, sensorId);
        }

        @Override
        public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            for (ServiceProvider provider : mServiceProviders) {
                provider.setUdfpsOverlayController(controller);
            }
        }

        @Override
        public void setSidefpsController(@NonNull ISidefpsController controller) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            for (ServiceProvider provider : mServiceProviders) {
                provider.setSidefpsController(controller);
            }
        }

        @Override
        public void registerFingerprintStateListener(@NonNull IFingerprintStateListener listener) {
            FingerprintService.this.registerFingerprintStateListener(listener);
        }
    }

    public FingerprintService(Context context) {
        super(context);
        mServiceWrapper = new FingerprintServiceWrapper();
        mAppOps = context.getSystemService(AppOpsManager.class);
        mGestureAvailabilityDispatcher = new GestureAvailabilityDispatcher();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mServiceProviders = new ArrayList<>();
        mFingerprintStateCallback = new FingerprintStateCallback();
        mAuthenticatorsRegisteredCallbacks = new RemoteCallbackList<>();
        mSensorProps = new ArrayList<>();
        mHandler = new Handler(Looper.getMainLooper());
    }

    // Notifies the callbacks that all of the authenticators have been registered and removes the
    // invoked callbacks from the callback list.
    private void broadcastAllAuthenticatorsRegistered() {
        // Make a local copy of the data so it can be used outside of the synchronized block when
        // making Binder calls.
        final List<IFingerprintAuthenticatorsRegisteredCallback> callbacks = new ArrayList<>();
        final List<FingerprintSensorPropertiesInternal> props;
        synchronized (mLock) {
            if (!mSensorProps.isEmpty()) {
                props = new ArrayList<>(mSensorProps);
            } else {
                Slog.e(TAG, "mSensorProps is empty");
                return;
            }
            final int n = mAuthenticatorsRegisteredCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                final IFingerprintAuthenticatorsRegisteredCallback cb =
                        mAuthenticatorsRegisteredCallbacks.getBroadcastItem(i);
                callbacks.add(cb);
                mAuthenticatorsRegisteredCallbacks.unregister(cb);
            }
            mAuthenticatorsRegisteredCallbacks.finishBroadcast();
        }
        for (IFingerprintAuthenticatorsRegisteredCallback cb : callbacks) {
            try {
                cb.onAllAuthenticatorsRegistered(props);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in onAllAuthenticatorsRegistered", e);
            }
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, mServiceWrapper);
    }

    @Nullable
    private ServiceProvider getProviderForSensor(int sensorId) {
        for (ServiceProvider provider : mServiceProviders) {
            if (provider.containsSensor(sensorId)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * For devices with only a single provider, returns that provider. If multiple providers,
     * returns the first one. If no providers, returns null.
     */
    @Nullable
    private Pair<Integer, ServiceProvider> getSingleProvider() {
        final List<FingerprintSensorPropertiesInternal> properties = getSensorProperties();
        if (properties.isEmpty()) {
            Slog.e(TAG, "No providers found");
            return null;
        }

        // Theoretically we can just return the first provider, but maybe this is easier to
        // understand.
        final int sensorId = properties.get(0).sensorId;
        for (ServiceProvider provider : mServiceProviders) {
            if (provider.containsSensor(sensorId)) {
                return new Pair<>(sensorId, provider);
            }
        }

        Slog.e(TAG, "Provider not found");
        return null;
    }

    @NonNull
    private List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        synchronized (mLock) {
            return mSensorProps;
        }
    }

    @NonNull
    private List<Fingerprint> getEnrolledFingerprintsDeprecated(int userId, String opPackageName) {
        final Pair<Integer, ServiceProvider> provider = getSingleProvider();
        if (provider == null) {
            Slog.w(TAG, "Null provider for getEnrolledFingerprintsDeprecated, caller: "
                    + opPackageName);
            return Collections.emptyList();
        }

        return provider.second.getEnrolledFingerprints(provider.first, userId);
    }

    /** Checks for public API invocations to ensure that permissions, etc are granted/correct. */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean canUseFingerprint(
            String opPackageName,
            String attributionTag,
            boolean requireForeground,
            int uid,
            int pid,
            int userId) {
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
        if (!checkAppOps(uid, opPackageName, attributionTag)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; permission denied");
            return false;
        }
        if (requireForeground && !Utils.isForeground(uid, pid)) {
            Slog.w(TAG, "Rejecting " + opPackageName + "; not in foreground");
            return false;
        }
        return true;
    }

    private boolean checkAppOps(int uid, String opPackageName, String attributionTag) {
        boolean appOpsOk = false;
        if (mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid, opPackageName, attributionTag, null)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        } else if (mAppOps.noteOp(
                        AppOpsManager.OP_USE_FINGERPRINT, uid, opPackageName, attributionTag, null)
                == AppOpsManager.MODE_ALLOWED) {
            appOpsOk = true;
        }
        return appOpsOk;
    }
}
