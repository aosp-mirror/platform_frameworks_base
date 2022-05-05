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
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricStateListener;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintServiceReceiver;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21;
import com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock;

import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 */
public class FingerprintService extends SystemService {

    protected static final String TAG = "FingerprintService";

    private final AppOpsManager mAppOps;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final BiometricContext mBiometricContext;
    @NonNull
    private final Supplier<String[]> mAidlInstanceNameSupplier;
    @NonNull
    private final Function<String, IFingerprint> mIFingerprintProvider;
    @NonNull
    private final BiometricStateCallback<ServiceProvider, FingerprintSensorPropertiesInternal>
            mBiometricStateCallback;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final FingerprintServiceRegistry mRegistry;

    /** Receives the incoming binder calls from FingerprintManager. */
    @VisibleForTesting
    final IFingerprintService.Stub mServiceWrapper = new IFingerprintService.Stub() {
        @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);

            if (provider == null) {
                Slog.w(TAG, "Null provider for createTestSession, sensorId: " + sensorId);
                return null;
            }

            return provider.createTestSession(sensorId, callback, opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public byte[] dumpSensorServiceStateProto(int sensorId, boolean clearSchedulerBuffer) {
            final ProtoOutputStream proto = new ProtoOutputStream();
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
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
            return mRegistry.getAllProperties();
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public FingerprintSensorPropertiesInternal getSensorProperties(int sensorId,
                @NonNull String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for getSensorProperties, sensorId: " + sensorId
                        + ", caller: " + opPackageName);
                return null;
            }
            return provider.getSensorProperties(sensorId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void generateChallenge(IBinder token, int sensorId, int userId,
                IFingerprintServiceReceiver receiver, String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for generateChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleGenerateChallenge(sensorId, userId, token, receiver, opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName,
                long challenge) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName,
                    challenge);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public long enroll(final IBinder token, @NonNull final byte[] hardwareAuthToken,
                final int userId, final IFingerprintServiceReceiver receiver,
                final String opPackageName, @FingerprintManager.EnrollReason int enrollReason) {
            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return -1;
            }

            return provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, enrollReason);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void cancelEnrollment(final IBinder token, long requestId) {
            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
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
                provider = mRegistry.getSingleProvider();
            } else {
                Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
                provider = new Pair<>(sensorId, mRegistry.getProviderForSensor(sensorId));
            }
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }

            final FingerprintSensorPropertiesInternal sensorProps =
                    provider.second.getSensorProperties(sensorId);
            if (!isKeyguard && !Utils.isSettings(getContext(), opPackageName)
                    && sensorProps != null && sensorProps.isAnyUdfpsType()) {
                try {
                    return authenticateWithPrompt(operationId, sensorProps, callingUid,
                            callingUserId, receiver, opPackageName, ignoreEnrollmentState);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Invalid package", e);
                    return -1;
                }
            }
            return provider.second.scheduleAuthenticate(provider.first, token, operationId, userId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), opPackageName,
                    restricted, statsClient, isKeyguard);
        }

        private long authenticateWithPrompt(
                final long operationId,
                @NonNull final FingerprintSensorPropertiesInternal props,
                final int uId,
                final int userId,
                final IFingerprintServiceReceiver receiver,
                final String opPackageName,
                boolean ignoreEnrollmentState) throws PackageManager.NameNotFoundException {
            final Context context = getUiContext();
            final Context promptContext = context.createPackageContextAsUser(
                    opPackageName, 0 /* flags */, UserHandle.getUserHandleForUid(uId));
            final Executor executor = context.getMainExecutor();

            final BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(promptContext)
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
                    .setIsForLegacyFingerprintManager(props.sensorId)
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

            return biometricPrompt.authenticateForOperation(
                    new CancellationSignal(), executor, promptCallback, operationId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public long detectFingerprint(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
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

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFingerprint");
                return -1;
            }

            return provider.second.scheduleFingerDetect(provider.first, token, userId,
                    new ClientMonitorCallbackConverter(receiver), opPackageName,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void prepareForAuthentication(int sensorId, IBinder token, long operationId,
                int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
                long requestId, int cookie, boolean allowBackgroundAuthentication) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
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

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void startPreparedClient(int sensorId, int cookie) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
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

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void cancelFingerprintDetect(final IBinder token, final String opPackageName,
                final long requestId) {
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFingerprintDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            // For IBiometricsFingerprint2.1, cancelling fingerprint detect is the same as
            // cancelling authentication.
            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelFingerprintDetect");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void cancelAuthenticationFromService(final int sensorId, final IBinder token,
                final String opPackageName, final long requestId) {
            Slog.d(TAG, "cancelAuthenticationFromService, sensorId: " + sensorId);

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {
            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }
            provider.second.scheduleRemove(provider.first, token, receiver, fingerId, userId,
                    opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void removeAll(final IBinder token, final int userId,
                final IFingerprintServiceReceiver receiver, final String opPackageName) {

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
            for (ServiceProvider provider : mRegistry.getProviders()) {
                List<FingerprintSensorPropertiesInternal> props = provider.getSensorProperties();
                for (FingerprintSensorPropertiesInternal prop : props) {
                    provider.scheduleRemoveAll(prop.sensorId, token, internalReceiver, userId,
                            opPackageName);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback,
                final String opPackageName) {
            mLockoutResetDispatcher.addCallback(callback, opPackageName);
        }

        @Override // Binder call
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver)
                throws RemoteException {
            (new FingerprintShellCommand(getContext(), FingerprintService.this))
                    .exec(this, in, out, err, args, callback, resultReceiver);
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
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto, false);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else {
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FingerprintSensorPropertiesInternal props
                                : provider.getSensorProperties()) {
                            pw.println("Dumping for sensorId: " + props.sensorId
                                    + ", provider: " + provider.getClass().getSimpleName());
                            pw.println("Fps state: "
                                    + mBiometricStateCallback.getBiometricState());
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
                final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                return false;
            }

            return provider.isHardwareDetected(sensorId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void rename(final int fingerId, final int userId, final String name) {
            if (!Utils.isCurrentUserOrProfile(getContext(), userId)) {
                return;
            }

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        public boolean hasEnrolledFingerprints(int sensorId, int userId, String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for hasEnrolledFingerprints, caller: " + opPackageName);
                return false;
            }

            return provider.getEnrolledFingerprints(sensorId, userId).size() > 0;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public @LockoutTracker.LockoutMode int getLockoutModeForUser(int sensorId, int userId) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getLockoutModeForUser");
                return LockoutTracker.LOCKOUT_NONE;
            }
            return provider.getLockoutModeForUser(sensorId, userId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void invalidateAuthenticatorId(int sensorId, int userId,
                IInvalidationCallback callback) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for invalidateAuthenticatorId");
                return;
            }
            provider.scheduleInvalidateAuthenticatorId(sensorId, userId, callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long getAuthenticatorId(int sensorId, int userId) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getAuthenticatorId");
                return 0;
            }
            return provider.getAuthenticatorId(sensorId, userId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT)
        @Override // Binder call
        public void resetLockout(IBinder token, int sensorId, int userId,
                @Nullable byte[] hardwareAuthToken, String opPackageName) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override
        public boolean isClientActive() {
            return mGestureAvailabilityDispatcher.isAnySensorActive();
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            mGestureAvailabilityDispatcher.registerCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            mGestureAvailabilityDispatcher.removeCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void registerAuthenticators(
                @NonNull List<FingerprintSensorPropertiesInternal> hidlSensors) {
            mRegistry.registerAll(() -> {
                final List<ServiceProvider> providers = new ArrayList<>();
                providers.addAll(getHidlProviders(hidlSensors));
                List<String> aidlSensors = new ArrayList<>();
                final String[] instances = mAidlInstanceNameSupplier.get();
                if (instances != null) {
                    aidlSensors.addAll(Lists.newArrayList(instances));
                }
                providers.addAll(getAidlProviders(
                        Utils.filterAvailableHalInstances(getContext(), aidlSensors)));
                return providers;
            });
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void addAuthenticatorsRegisteredCallback(
                IFingerprintAuthenticatorsRegisteredCallback callback) {
            mRegistry.addAllRegisteredCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void registerBiometricStateListener(@NonNull IBiometricStateListener listener) {
            mBiometricStateCallback.registerBiometricStateListener(listener);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onPointerDown(long requestId, int sensorId, int x, int y,
                float minor, float major) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerDown, sensorId: " + sensorId);
                return;
            }
            provider.onPointerDown(requestId, sensorId, x, y, minor, major);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onPointerUp(long requestId, int sensorId) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerUp, sensorId: " + sensorId);
                return;
            }
            provider.onPointerUp(requestId, sensorId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onUiReady(long requestId, int sensorId) {
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onUiReady, sensorId: " + sensorId);
                return;
            }
            provider.onUiReady(requestId, sensorId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
            for (ServiceProvider provider : mRegistry.getProviders()) {
                provider.setUdfpsOverlayController(controller);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void setSidefpsController(@NonNull ISidefpsController controller) {
            for (ServiceProvider provider : mRegistry.getProviders()) {
                provider.setSidefpsController(controller);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onPowerPressed() {
            for (ServiceProvider provider : mRegistry.getProviders()) {
                provider.onPowerPressed();
            }
        }
    };

    public FingerprintService(Context context) {
        this(context, BiometricContext.getInstance(context),
                () -> IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE)),
                () -> ServiceManager.getDeclaredInstances(IFingerprint.DESCRIPTOR),
                (fqName) -> IFingerprint.Stub.asInterface(
                        Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName))));
    }

    @VisibleForTesting
    FingerprintService(Context context,
            BiometricContext biometricContext,
            Supplier<IBiometricService> biometricServiceSupplier,
            Supplier<String[]> aidlInstanceNameSupplier,
            Function<String, IFingerprint> fingerprintProvider) {
        super(context);
        mBiometricContext = biometricContext;
        mAidlInstanceNameSupplier = aidlInstanceNameSupplier;
        mIFingerprintProvider = fingerprintProvider;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mGestureAvailabilityDispatcher = new GestureAvailabilityDispatcher();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mBiometricStateCallback = new BiometricStateCallback<>(UserManager.get(context));
        mHandler = new Handler(Looper.getMainLooper());
        mRegistry = new FingerprintServiceRegistry(mServiceWrapper, biometricServiceSupplier);
        mRegistry.addAllRegisteredCallback(new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
            @Override
            public void onAllAuthenticatorsRegistered(
                    List<FingerprintSensorPropertiesInternal> sensors) {
                mBiometricStateCallback.start(mRegistry.getProviders());
            }
        });
    }

    @NonNull
    private List<ServiceProvider> getHidlProviders(
            @NonNull List<FingerprintSensorPropertiesInternal> hidlSensors) {
        final List<ServiceProvider> providers = new ArrayList<>();

        for (FingerprintSensorPropertiesInternal hidlSensor : hidlSensors) {
            final Fingerprint21 fingerprint21;
            if ((Build.IS_USERDEBUG || Build.IS_ENG)
                    && getContext().getResources().getBoolean(R.bool.allow_test_udfps)
                    && Settings.Secure.getIntForUser(getContext().getContentResolver(),
                    Fingerprint21UdfpsMock.CONFIG_ENABLE_TEST_UDFPS, 0 /* default */,
                    UserHandle.USER_CURRENT) != 0) {
                fingerprint21 = Fingerprint21UdfpsMock.newInstance(getContext(),
                        mBiometricStateCallback, hidlSensor,
                        mLockoutResetDispatcher, mGestureAvailabilityDispatcher,
                        BiometricContext.getInstance(getContext()));
            } else {
                fingerprint21 = Fingerprint21.newInstance(getContext(),
                        mBiometricStateCallback, hidlSensor, mHandler,
                        mLockoutResetDispatcher, mGestureAvailabilityDispatcher);
            }
            providers.add(fingerprint21);
        }

        return providers;
    }

    @NonNull
    private List<ServiceProvider> getAidlProviders(@NonNull List<String> instances) {
        final List<ServiceProvider> providers = new ArrayList<>();

        for (String instance : instances) {
            final String fqName = IFingerprint.DESCRIPTOR + "/" + instance;
            final IFingerprint fp = mIFingerprintProvider.apply(fqName);

            if (fp != null) {
                try {
                    final FingerprintProvider provider = new FingerprintProvider(getContext(),
                            mBiometricStateCallback, fp.getSensorProps(), instance,
                            mLockoutResetDispatcher, mGestureAvailabilityDispatcher,
                            mBiometricContext);
                    Slog.i(TAG, "Adding AIDL provider: " + fqName);
                    providers.add(provider);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception in getSensorProps: " + fqName);
                }
            } else {
                Slog.e(TAG, "Unable to get declared service: " + fqName);
            }
        }

        return providers;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, mServiceWrapper);
    }

    @NonNull
    private List<Fingerprint> getEnrolledFingerprintsDeprecated(int userId, String opPackageName) {
        final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
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

    void syncEnrollmentsNow() {
        Utils.checkPermissionOrShell(getContext(), MANAGE_FINGERPRINT);
        if (Utils.isVirtualEnabled(getContext())) {
            Slog.i(TAG, "Sync virtual enrollments");
            final int userId = ActivityManager.getCurrentUser();
            for (ServiceProvider provider : mRegistry.getProviders()) {
                for (FingerprintSensorPropertiesInternal props : provider.getSensorProperties()) {
                    provider.scheduleInternalCleanup(props.sensorId, userId, null /* callback */,
                            true /* favorHalEnrollments */);
                }
            }
        }
    }
}
