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
import android.hardware.biometrics.AuthenticationStateListener;
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
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorConfigurations;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintServiceReceiver;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
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
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
    private final Function<String, FingerprintProvider> mFingerprintProvider;
    @NonNull
    private final FingerprintProviderFunction mFingerprintProviderFunction;
    @NonNull
    private final BiometricStateCallback<ServiceProvider, FingerprintSensorPropertiesInternal>
            mBiometricStateCallback;
    @NonNull
    private final AuthenticationStateListeners mAuthenticationStateListeners;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final FingerprintServiceRegistry mRegistry;

    interface FingerprintProviderFunction {
        FingerprintProvider getFingerprintProvider(Pair<String, SensorProps[]> filteredSensorProp,
                boolean resetLockoutRequiresHardwareAuthToken);
    }

    /** Receives the incoming binder calls from FingerprintManager. */
    @VisibleForTesting
    final IFingerprintService.Stub mServiceWrapper = new IFingerprintService.Stub() {
        @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) {
            super.createTestSession_enforcePermission();

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
            super.dumpSensorServiceStateProto_enforcePermission();

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
            super.getSensorProperties_enforcePermission();

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
            super.generateChallenge_enforcePermission();

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
            super.revokeChallenge_enforcePermission();

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
                final String opPackageName, @FingerprintManager.EnrollReason int enrollReason,
                FingerprintEnrollOptions options) {
            super.enroll_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return -1;
            }

            return provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, enrollReason, options);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override // Binder call
        public void cancelEnrollment(final IBinder token, long requestId) {
            super.cancelEnrollment_enforcePermission();

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
                final IFingerprintServiceReceiver receiver,
                final FingerprintAuthenticateOptions options) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();
            final String opPackageName = options.getOpPackageName();
            final String attributionTag = options.getAttributionTag();
            final int userId = options.getUserId();

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
            if (options.getSensorId() == FingerprintManager.SENSOR_ID_ANY) {
                provider = mRegistry.getSingleProvider();
            } else {
                Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
                provider = new Pair<>(options.getSensorId(),
                        mRegistry.getProviderForSensor(options.getSensorId()));
            }

            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }
            options.setSensorId(provider.first);

            final FingerprintSensorPropertiesInternal sensorProps =
                    provider.second.getSensorProperties(options.getSensorId());
            if (!isKeyguard && !Utils.isSettings(getContext(), opPackageName)
                    && sensorProps != null && (sensorProps.isAnyUdfpsType()
                    || sensorProps.isAnySidefpsType())) {
                try {
                    return authenticateWithPrompt(operationId, sensorProps, callingUid,
                            callingUserId, receiver, opPackageName,
                            options.isIgnoreEnrollmentState());
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Invalid package", e);
                    return -1;
                }
            }
            final long identity2 = Binder.clearCallingIdentity();
            try {
                VirtualDeviceManagerInternal vdm = getLocalService(
                        VirtualDeviceManagerInternal.class);
                if (vdm != null) {
                    vdm.onAuthenticationPrompt(callingUid);
                }
            } finally {
                Binder.restoreCallingIdentity(identity2);
            }
            return provider.second.scheduleAuthenticate(token, operationId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), options,
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

                        @Override
                        public void onAuthenticationHelp(int acquireInfo, CharSequence helpString) {
                            onAuthenticationAcquired(acquireInfo);
                        }
                    };

            return biometricPrompt.authenticateForOperation(
                    new CancellationSignal(), executor, promptCallback, operationId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public long detectFingerprint(final IBinder token,
                final IFingerprintServiceReceiver receiver,
                final FingerprintAuthenticateOptions options) {
            super.detectFingerprint_enforcePermission();

            final String opPackageName = options.getOpPackageName();
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFingerprint called from non-sysui package: " + opPackageName);
                return -1;
            }

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFingerprint");
                return -1;
            }
            options.setSensorId(provider.first);

            return provider.second.scheduleFingerDetect(token,
                    new ClientMonitorCallbackConverter(receiver), options,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void prepareForAuthentication(IBinder token, long operationId,
                IBiometricSensorReceiver sensorReceiver,
                @NonNull FingerprintAuthenticateOptions options,
                long requestId, int cookie, boolean allowBackgroundAuthentication,
                boolean isForLegacyFingerprintManager) {
            super.prepareForAuthentication_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(options.getSensorId());
            if (provider == null) {
                Slog.w(TAG, "Null provider for prepareForAuthentication");
                return;
            }

            final int statsClient =
                    isForLegacyFingerprintManager ? BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER
                            : BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT;
            final boolean restricted = true; // BiometricPrompt is always restricted
            provider.scheduleAuthenticate(token, operationId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), options, requestId,
                    restricted, statsClient,
                    allowBackgroundAuthentication);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void startPreparedClient(int sensorId, int cookie) {
            super.startPreparedClient_enforcePermission();

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
            super.cancelFingerprintDetect_enforcePermission();

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
            super.cancelAuthenticationFromService_enforcePermission();

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
            super.remove_enforcePermission();

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

            super.removeAll_enforcePermission();

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
            super.addLockoutResetCallback_enforcePermission();

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
            super.isHardwareDetected_enforcePermission();

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
            super.rename_enforcePermission();

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
            super.hasEnrolledFingerprints_enforcePermission();

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
            super.getLockoutModeForUser_enforcePermission();

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
            super.invalidateAuthenticatorId_enforcePermission();

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
            super.getAuthenticatorId_enforcePermission();

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
            super.resetLockout_enforcePermission();

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
            super.isClientActive_enforcePermission();

            return mGestureAvailabilityDispatcher.isAnySensorActive();
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            super.addClientActiveCallback_enforcePermission();

            mGestureAvailabilityDispatcher.registerCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_FINGERPRINT)
        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            super.removeClientActiveCallback_enforcePermission();

            mGestureAvailabilityDispatcher.removeCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void registerAuthenticators(
                @NonNull FingerprintSensorConfigurations fingerprintSensorConfigurations) {
            super.registerAuthenticators_enforcePermission();
            if (!fingerprintSensorConfigurations.hasSensorConfigurations()) {
                Slog.d(TAG, "No fingerprint sensors available.");
                return;
            }
            mRegistry.registerAll(() -> getProviders(fingerprintSensorConfigurations));
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void addAuthenticatorsRegisteredCallback(
                IFingerprintAuthenticatorsRegisteredCallback callback) {
            super.addAuthenticatorsRegisteredCallback_enforcePermission();

            mRegistry.addAllRegisteredCallback(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void registerAuthenticationStateListener(
                @NonNull AuthenticationStateListener listener) {
            super.registerAuthenticationStateListener_enforcePermission();

            mAuthenticationStateListeners.registerAuthenticationStateListener(listener);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void unregisterAuthenticationStateListener(
                @NonNull AuthenticationStateListener listener) {
            super.unregisterAuthenticationStateListener_enforcePermission();

            mAuthenticationStateListeners.unregisterAuthenticationStateListener(listener);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void registerBiometricStateListener(@NonNull IBiometricStateListener listener) {
            super.registerBiometricStateListener_enforcePermission();

            mBiometricStateCallback.registerBiometricStateListener(listener);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onPointerDown(long requestId, int sensorId, PointerContext pc) {
            super.onPointerDown_enforcePermission();
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerDown, sensorId: " + sensorId);
                return;
            }
            provider.onPointerDown(requestId, sensorId, pc);
        }
        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override

        public void onPointerUp(long requestId, int sensorId, PointerContext pc) {
            super.onPointerUp_enforcePermission();
            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onFingerUp, sensorId: " + sensorId);
                return;
            }
            provider.onPointerUp(requestId, sensorId, pc);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onUdfpsUiEvent(@FingerprintManager.UdfpsUiEvent int event, long requestId,
                int sensorId) {
            super.onUdfpsUiEvent_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching provider for onUdfpsUiEvent, sensorId: " + sensorId);
                return;
            }
            provider.onUdfpsUiEvent(event, requestId, sensorId);
        }


        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
            super.setUdfpsOverlayController_enforcePermission();

            for (ServiceProvider provider : mRegistry.getProviders()) {
                provider.setUdfpsOverlayController(controller);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void onPowerPressed() {
            super.onPowerPressed_enforcePermission();

            for (ServiceProvider provider : mRegistry.getProviders()) {
                provider.onPowerPressed();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void scheduleWatchdog() {
            super.scheduleWatchdog_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for scheduling watchdog");
                return;
            }

            provider.second.scheduleWatchdog(provider.first);
        }
    };

    public FingerprintService(Context context) {
        this(context, BiometricContext.getInstance(context),
                () -> IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE)),
                () -> ServiceManager.getDeclaredInstances(IFingerprint.DESCRIPTOR),
                null /* fingerprintProvider */,
                null /* fingerprintProviderFunction */);
    }

    @VisibleForTesting
    FingerprintService(Context context,
            BiometricContext biometricContext,
            Supplier<IBiometricService> biometricServiceSupplier,
            Supplier<String[]> aidlInstanceNameSupplier,
            Function<String, FingerprintProvider> fingerprintProvider,
            FingerprintProviderFunction fingerprintProviderFunction) {
        super(context);
        mBiometricContext = biometricContext;
        mAidlInstanceNameSupplier = aidlInstanceNameSupplier;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mGestureAvailabilityDispatcher = new GestureAvailabilityDispatcher();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mBiometricStateCallback = new BiometricStateCallback<>(UserManager.get(context));
        mAuthenticationStateListeners = new AuthenticationStateListeners();
        mFingerprintProvider = fingerprintProvider != null ? fingerprintProvider :
                (name) -> {
                    final String fqName = IFingerprint.DESCRIPTOR + "/" + name;
                    final IFingerprint fp = IFingerprint.Stub.asInterface(
                            Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName)));
                    if (fp != null) {
                        try {
                            return new FingerprintProvider(getContext(),
                                    mBiometricStateCallback, mAuthenticationStateListeners,
                                    fp.getSensorProps(), name, mLockoutResetDispatcher,
                                    mGestureAvailabilityDispatcher, mBiometricContext,
                                    true /* resetLockoutRequiresHardwareAuthToken */);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception in getSensorProps: " + fqName);
                        }
                    } else {
                        Slog.e(TAG, "Unable to get declared service: " + fqName);
                    }

                    return null;
                };
        mFingerprintProviderFunction = fingerprintProviderFunction != null
                ? fingerprintProviderFunction :
                        (filteredSensorProps, resetLockoutRequiresHardwareAuthToken) ->
                                new FingerprintProvider(getContext(), mBiometricStateCallback,
                                        mAuthenticationStateListeners, filteredSensorProps.second,
                                        filteredSensorProps.first, mLockoutResetDispatcher,
                                        mGestureAvailabilityDispatcher, mBiometricContext,
                                        resetLockoutRequiresHardwareAuthToken);

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
    private List<ServiceProvider> getProviders(@NonNull FingerprintSensorConfigurations
            fingerprintSensorConfigurations) {
        final List<ServiceProvider> providers = new ArrayList<>();
        final Pair<String, SensorProps[]> filteredSensorProps = filterAvailableHalInstances(
                fingerprintSensorConfigurations);
        providers.add(mFingerprintProviderFunction.getFingerprintProvider(filteredSensorProps,
                fingerprintSensorConfigurations.getResetLockoutRequiresHardwareAuthToken()));

        return providers;
    }

    @NonNull
    private Pair<String, SensorProps[]> filterAvailableHalInstances(
            FingerprintSensorConfigurations fingerprintSensorConfigurations) {
        final String finalSensorInstance = fingerprintSensorConfigurations.getSensorInstance();
        if (fingerprintSensorConfigurations.isSingleSensorConfigurationPresent()) {
            return new Pair<>(finalSensorInstance,
                    fingerprintSensorConfigurations.getSensorPropForInstance(finalSensorInstance));
        }
        final String virtualInstance = "virtual";
        final boolean isVirtualHalPresent =
                fingerprintSensorConfigurations.doesInstanceExist(virtualInstance);
        if (Utils.isFingerprintVirtualEnabled(getContext())) {
            if (isVirtualHalPresent) {
                return new Pair<>(virtualInstance,
                        fingerprintSensorConfigurations.getSensorPropForInstance(virtualInstance));
            } else {
                Slog.e(TAG, "Could not find virtual interface while it is enabled");
                return new Pair<>(finalSensorInstance,
                        fingerprintSensorConfigurations.getSensorPropForInstance(
                                finalSensorInstance));
            }
        } else {
            if (isVirtualHalPresent) {
                final String notAVirtualInstance = fingerprintSensorConfigurations
                        .getSensorNameNotForInstance(virtualInstance);
                if (notAVirtualInstance != null) {
                    return new Pair<>(notAVirtualInstance, fingerprintSensorConfigurations
                            .getSensorPropForInstance(notAVirtualInstance));
                }
            }
        }
        return new Pair<>(finalSensorInstance, fingerprintSensorConfigurations
                .getSensorPropForInstance(finalSensorInstance));
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
        if (Utils.isFingerprintVirtualEnabled(getContext())) {
            Slog.i(TAG, "Sync virtual enrollments");
            final int userId = ActivityManager.getCurrentUser();
            final CountDownLatch latch = new CountDownLatch(mRegistry.getProviders().size());
            for (ServiceProvider provider : mRegistry.getProviders()) {
                for (FingerprintSensorPropertiesInternal props : provider.getSensorProperties()) {
                    provider.scheduleInternalCleanup(props.sensorId, userId,
                            new ClientMonitorCallback() {
                                @Override
                                public void onClientFinished(
                                        @NonNull BaseClientMonitor clientMonitor,
                                        boolean success) {
                                    latch.countDown();
                                    if (!success) {
                                        Slog.e(TAG, "Sync virtual enrollments failed");
                                    }
                                }
                            }, true /* favorHalEnrollments */);
                }
            }
            try {
                latch.await(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to wait for sync finishing", e);
            }
        }
    }

    void simulateVhalFingerDown() {
        if (Utils.isFingerprintVirtualEnabled(getContext())) {
            Slog.i(TAG, "Simulate virtual HAL finger down event");
            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider != null) {
                provider.second.simulateVhalFingerDown(UserHandle.getCallingUserId(),
                        provider.first);
            }
        }
    }

    /**
     * This should only be called from FingerprintShellCommand
     */
    void sendFingerprintReEnrollNotification() {
        Utils.checkPermissionOrShell(getContext(), MANAGE_FINGERPRINT);
        if (Build.IS_DEBUGGABLE) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
                if (provider != null) {
                    FingerprintProvider fingerprintProvider = (FingerprintProvider) provider.second;
                    fingerprintProvider.sendFingerprintReEnrollNotification();
                } else {
                    Slog.w(TAG, "Null provider for notification");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
