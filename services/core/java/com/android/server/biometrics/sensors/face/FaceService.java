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

package com.android.server.biometrics.sensors.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_FACE;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.biometrics.AuthenticationStateListener;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricStateListener;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceSensorConfigurations;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.FaceServiceReceiver;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.aidl.FaceProvider;
import com.android.server.biometrics.sensors.face.hidl.Face10;

import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face-related events.
 */
public class FaceService extends SystemService {

    protected static final String TAG = "FaceService";

    @VisibleForTesting final FaceServiceWrapper mServiceWrapper;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final FaceServiceRegistry mRegistry;
    @NonNull
    private final BiometricStateCallback<ServiceProvider, FaceSensorPropertiesInternal>
            mBiometricStateCallback;
    @NonNull
    private final AuthenticationStateListeners mAuthenticationStateListeners;
    @NonNull
    private final FaceProviderFunction mFaceProviderFunction;
    @NonNull private final Function<String, FaceProvider> mFaceProvider;
    @NonNull
    private final Supplier<String[]> mAidlInstanceNameSupplier;

    interface FaceProviderFunction {
        FaceProvider getFaceProvider(Pair<String, SensorProps[]> filteredSensorProps,
                boolean resetLockoutRequiresChallenge);
    }

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    @VisibleForTesting final class FaceServiceWrapper extends IFaceService.Stub {
        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
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

        @android.annotation.EnforcePermission(
                anyOf = {
                        android.Manifest.permission.USE_BIOMETRIC_INTERNAL,
                        android.Manifest.permission.USE_BACKGROUND_FACE_AUTHENTICATION
                })
        @Override // Binder call
        public List<FaceSensorPropertiesInternal> getSensorPropertiesInternal(
                String opPackageName) {
            super.getSensorPropertiesInternal_enforcePermission();

            return mRegistry.getAllProperties();
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public FaceSensorPropertiesInternal getSensorProperties(int sensorId,
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

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void generateChallenge(IBinder token, int sensorId, int userId,
                IFaceServiceReceiver receiver, String opPackageName) {
            super.generateChallenge_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for generateChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleGenerateChallenge(sensorId, userId, token, receiver, opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName,
                long challenge) {
            super.revokeChallenge_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName, challenge);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public long enroll(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures, Surface previewSurface, boolean debugConsent) {
            super.enroll_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return -1;
            }

            return provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, disabledFeatures, previewSurface, debugConsent);
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

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override // Binder call
        public long enrollRemotely(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            // TODO(b/145027036): Implement this.
            super.enrollRemotely_enforcePermission();

            return -1;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long authenticate(final IBinder token, final long operationId,
                final IFaceServiceReceiver receiver, final FaceAuthenticateOptions options) {
            // TODO(b/152413782): If the sensor supports face detect and the device is encrypted or
            //  lockdown, something wrong happened. See similar path in FingerprintService.

            super.authenticate_enforcePermission();

            final String opPackageName = options.getOpPackageName();
            final boolean restricted = false; // Face APIs are private
            final int statsClient = Utils.isKeyguard(getContext(), opPackageName)
                    ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_UNKNOWN;

            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }
            options.setSensorId(provider.first);

            return provider.second.scheduleAuthenticate(token, operationId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), options,
                    restricted, statsClient, isKeyguard);
        }

        @android.annotation.EnforcePermission(
                android.Manifest.permission.USE_BACKGROUND_FACE_AUTHENTICATION)
        @Override // Binder call
        public long authenticateInBackground(final IBinder token, final long operationId,
                final IFaceServiceReceiver receiver, final FaceAuthenticateOptions options) {
            // TODO(b/152413782): If the sensor supports face detect and the device is encrypted or
            //  lockdown, something wrong happened. See similar path in FingerprintService.

            super.authenticateInBackground_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }
            options.setSensorId(provider.first);

            return provider.second.scheduleAuthenticate(token, operationId,
                    0 /* cookie */, new ClientMonitorCallbackConverter(receiver), options,
                    false /* restricted */, BiometricsProtoEnums.CLIENT_UNKNOWN /* statsClient */,
                    true /* allowBackgroundAuthentication */);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public long detectFace(final IBinder token,
                final IFaceServiceReceiver receiver, final FaceAuthenticateOptions options) {
            super.detectFace_enforcePermission();

            final String opPackageName = options.getOpPackageName();
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFace called from non-sysui package: " + opPackageName);
                return -1;
            }

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFace");
                return -1;
            }
            options.setSensorId(provider.first);

            return provider.second.scheduleFaceDetect(token,
                    new ClientMonitorCallbackConverter(receiver), options,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void prepareForAuthentication(boolean requireConfirmation,
                IBinder token, long operationId, IBiometricSensorReceiver sensorReceiver,
                FaceAuthenticateOptions options, long requestId, int cookie,
                boolean allowBackgroundAuthentication) {
            super.prepareForAuthentication_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(options.getSensorId());
            if (provider == null) {
                Slog.w(TAG, "Null provider for prepareForAuthentication");
                return;
            }

            final boolean restricted = true; // BiometricPrompt is always restricted
            provider.scheduleAuthenticate(token, operationId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), options, requestId,
                    restricted, BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    allowBackgroundAuthentication);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName,
                final long requestId) {
            super.cancelAuthentication_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void cancelFaceDetect(final IBinder token, final String opPackageName,
                final long requestId) {
            super.cancelFaceDetect_enforcePermission();

            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFaceDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelFaceDetect");
                return;
            }

            provider.second.cancelFaceDetect(provider.first, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void cancelAuthenticationFromService(int sensorId, final IBinder token,
                final String opPackageName, final long requestId) {
            super.cancelAuthenticationFromService_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token, requestId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void remove(final IBinder token, final int faceId, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            super.remove_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }

            provider.second.scheduleRemove(provider.first, token, faceId, userId, receiver,
                    opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void removeAll(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            super.removeAll_enforcePermission();

            final FaceServiceReceiver internalReceiver = new FaceServiceReceiver() {
                int sensorsFinishedRemoving = 0;
                final int numSensors = getSensorPropertiesInternal(
                        getContext().getOpPackageName()).size();
                @Override
                public void onRemoved(Face face, int remaining) throws RemoteException {
                    if (remaining == 0) {
                        sensorsFinishedRemoving++;
                        Slog.d(TAG, "sensorsFinishedRemoving: " + sensorsFinishedRemoving
                                + ", numSensors: " + numSensors);
                        if (sensorsFinishedRemoving == numSensors) {
                            receiver.onRemoved(null, 0 /* remaining */);
                        }
                    }
                }

                @Override
                public void onError(int error, int vendorCode) throws RemoteException {
                    receiver.onError(error, vendorCode);
                }
            };

            // This effectively iterates through all sensors, but has to do so by finding all
            // sensors under each provider.
            for (ServiceProvider provider : mRegistry.getProviders()) {
                List<FaceSensorPropertiesInternal> props = provider.getSensorProperties();
                for (FaceSensorPropertiesInternal prop : props) {
                    provider.scheduleRemoveAll(prop.sensorId, token, userId, internalReceiver,
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
            (new FaceShellCommand(FaceService.this))
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
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto, false);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else if (args.length > 1 && "--hal".equals(args[0])) {
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpHal(props.sensorId, fd,
                                    Arrays.copyOfRange(args, 1, args.length, args.getClass()));
                        }
                    }
                } else {
                    for (ServiceProvider provider : mRegistry.getProviders()) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            pw.println("Dumping for sensorId: " + props.sensorId
                                    + ", provider: " + provider.getClass().getSimpleName());
                            provider.dumpInternal(props.sensorId, pw);
                            pw.println();
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            super.isHardwareDetected_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
                if (provider == null) {
                    Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                    return false;
                }
                return provider.isHardwareDetected(sensorId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public List<Face> getEnrolledFaces(int sensorId, int userId, String opPackageName) {
            super.getEnrolledFaces_enforcePermission();

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getEnrolledFaces, caller: " + opPackageName);
                return Collections.emptyList();
            }

            return provider.getEnrolledFaces(sensorId, userId);
        }

        @android.annotation.EnforcePermission(
                anyOf = {
                        android.Manifest.permission.USE_BIOMETRIC_INTERNAL,
                        android.Manifest.permission.USE_BACKGROUND_FACE_AUTHENTICATION
                })
        @Override // Binder call
        public boolean hasEnrolledFaces(int sensorId, int userId, String opPackageName) {
            super.hasEnrolledFaces_enforcePermission();

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for hasEnrolledFaces, caller: " + opPackageName);
                return false;
            }

            return provider.getEnrolledFaces(sensorId, userId).size() > 0;
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

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override // Binder call
        public void resetLockout(IBinder token, int sensorId, int userId, byte[] hardwareAuthToken,
                String opPackageName) {
            super.resetLockout_enforcePermission();

            final ServiceProvider provider = mRegistry.getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        @Override
        public void setFeature(final IBinder token, int userId, int feature, boolean enabled,
                final byte[] hardwareAuthToken, IFaceServiceReceiver receiver,
                final String opPackageName) {
            super.setFeature_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for setFeature");
                return;
            }

            provider.second.scheduleSetFeature(provider.first, token, userId, feature, enabled,
                    hardwareAuthToken, receiver, opPackageName);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_BIOMETRIC)
        @Override
        public void getFeature(final IBinder token, int userId, int feature,
                IFaceServiceReceiver receiver, final String opPackageName) {
            super.getFeature_enforcePermission();

            final Pair<Integer, ServiceProvider> provider = mRegistry.getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for getFeature");
                return;
            }

            provider.second.scheduleGetFeature(provider.first, token, userId, feature,
                    new ClientMonitorCallbackConverter(receiver), opPackageName);
        }
        @NonNull
        private List<ServiceProvider> getHidlProviders(
                @NonNull List<FaceSensorPropertiesInternal> hidlSensors) {
            final List<ServiceProvider> providers = new ArrayList<>();

            for (FaceSensorPropertiesInternal hidlSensor : hidlSensors) {
                providers.add(
                        Face10.newInstance(getContext(), mBiometricStateCallback,
                                mAuthenticationStateListeners, hidlSensor,
                                mLockoutResetDispatcher));
            }

            return providers;
        }

        @NonNull
        private List<ServiceProvider> getAidlProviders(@NonNull List<String> instances) {
            final List<ServiceProvider> providers = new ArrayList<>();

            for (String instance : instances) {
                final FaceProvider provider = mFaceProvider.apply(instance);
                Slog.i(TAG, "Adding AIDL provider: " + instance);
                providers.add(provider);
            }

            return providers;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        public void registerAuthenticators(
                @NonNull List<FaceSensorPropertiesInternal> hidlSensors) {
            super.registerAuthenticators_enforcePermission();

            mRegistry.registerAll(() -> {
                List<String> aidlSensors = new ArrayList<>();
                final String[] instances = mAidlInstanceNameSupplier.get();
                if (instances != null) {
                    aidlSensors.addAll(Lists.newArrayList(instances));
                }

                final Pair<List<FaceSensorPropertiesInternal>, List<String>>
                        filteredInstances = filterAvailableHalInstances(hidlSensors, aidlSensors);

                final List<ServiceProvider> providers = new ArrayList<>();
                providers.addAll(getHidlProviders(filteredInstances.first));
                providers.addAll(getAidlProviders(filteredInstances.second));
                return providers;
            });
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.USE_BIOMETRIC_INTERNAL)
        public void registerAuthenticatorsLegacy(
                FaceSensorConfigurations faceSensorConfigurations) {
            super.registerAuthenticatorsLegacy_enforcePermission();

            if (!faceSensorConfigurations.hasSensorConfigurations()) {
                Slog.d(TAG, "No face sensors to register.");
                return;
            }
            mRegistry.registerAll(() -> getProviders(faceSensorConfigurations));
        }

        private List<ServiceProvider> getProviders(
                FaceSensorConfigurations faceSensorConfigurations) {
            final List<ServiceProvider> providers = new ArrayList<>();
            final Pair<String, SensorProps[]> filteredSensorProps =
                    filterAvailableHalInstances(faceSensorConfigurations);
            providers.add(mFaceProviderFunction.getFaceProvider(filteredSensorProps,
                    faceSensorConfigurations.getResetLockoutRequiresChallenge()));
            return providers;
        }

        @NonNull
        private Pair<String, SensorProps[]> filterAvailableHalInstances(
                FaceSensorConfigurations faceSensorConfigurations) {
            Pair<String, SensorProps[]> finalSensorPair = faceSensorConfigurations.getSensorPair();

            if (faceSensorConfigurations.isSingleSensorConfigurationPresent()) {
                return finalSensorPair;
            }

            final Pair<String, SensorProps[]> virtualSensorProps = faceSensorConfigurations
                    .getSensorPairForInstance("virtual");

            if (Utils.isVirtualEnabled(getContext())) {
                if (virtualSensorProps != null) {
                    return virtualSensorProps;
                } else {
                    Slog.e(TAG, "Could not find virtual interface while it is enabled");
                    return finalSensorPair;
                }
            } else {
                if (virtualSensorProps != null) {
                    return faceSensorConfigurations.getSensorPairNotForInstance("virtual");
                }
            }
            return finalSensorPair;
        }

        private Pair<List<FaceSensorPropertiesInternal>, List<String>>
                filterAvailableHalInstances(
                @NonNull List<FaceSensorPropertiesInternal> hidlInstances,
                @NonNull List<String> aidlInstances) {
            if ((hidlInstances.size() + aidlInstances.size()) <= 1) {
                return new Pair(hidlInstances, aidlInstances);
            }

            if (Flags.faceVhalFeature()) {
                Slog.i(TAG, "Face VHAL feature is on");
            } else {
                Slog.i(TAG, "Face VHAL feature is off");
            }

            final int virtualAt = aidlInstances.indexOf("virtual");
            if (Flags.faceVhalFeature() && Utils.isVirtualEnabled(getContext())) {
                if (virtualAt != -1) {
                    //only virtual instance should be returned
                    Slog.i(TAG, "virtual hal is used");
                    return new Pair(new ArrayList<>(), List.of(aidlInstances.get(virtualAt)));
                } else {
                    Slog.e(TAG, "Could not find virtual interface while it is enabled");
                    return new Pair(hidlInstances, aidlInstances);
                }
            } else {
                //remove virtual instance
                aidlInstances = new ArrayList<>(aidlInstances);
                if (virtualAt != -1) {
                    aidlInstances.remove(virtualAt);
                }
                return new Pair(hidlInstances, aidlInstances);
            }
        }

        @Override
        public void addAuthenticatorsRegisteredCallback(
                IFaceAuthenticatorsRegisteredCallback callback) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mRegistry.addAllRegisteredCallback(callback);
        }

        @Override
        public void registerBiometricStateListener(@NonNull IBiometricStateListener listener) {
            mBiometricStateCallback.registerBiometricStateListener(listener);
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
    }

    public FaceService(Context context) {
        this(context, null /* faceProviderFunction */, () -> IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE)), null /* faceProvider */,
                () -> ServiceManager.getDeclaredInstances(IFace.DESCRIPTOR));
    }

    @VisibleForTesting FaceService(Context context,
            FaceProviderFunction faceProviderFunction,
            Supplier<IBiometricService> biometricServiceSupplier,
            Function<String, FaceProvider> faceProvider,
            Supplier<String[]> aidlInstanceNameSupplier) {
        super(context);
        mServiceWrapper = new FaceServiceWrapper();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mBiometricStateCallback = new BiometricStateCallback<>(UserManager.get(context));
        mAuthenticationStateListeners = new AuthenticationStateListeners();
        mRegistry = new FaceServiceRegistry(mServiceWrapper, biometricServiceSupplier);
        mRegistry.addAllRegisteredCallback(new IFaceAuthenticatorsRegisteredCallback.Stub() {
            @Override
            public void onAllAuthenticatorsRegistered(List<FaceSensorPropertiesInternal> sensors) {
                mBiometricStateCallback.start(mRegistry.getProviders());
            }
        });
        mAidlInstanceNameSupplier = aidlInstanceNameSupplier;

        mFaceProvider = faceProvider != null ? faceProvider : (name) -> {
            final String fqName = IFace.DESCRIPTOR + "/" + name;
            final IFace face = IFace.Stub.asInterface(
                    Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName)));
            if (face == null) {
                Slog.e(TAG, "Unable to get declared service: " + fqName);
                return null;
            }
            try {
                final SensorProps[] props = face.getSensorProps();
                return new FaceProvider(getContext(),
                        mBiometricStateCallback, mAuthenticationStateListeners, props, name,
                        mLockoutResetDispatcher, BiometricContext.getInstance(getContext()),
                        false /* resetLockoutRequiresChallenge */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in getSensorProps: " + fqName);
            }

            return null;
        };

        if (Flags.deHidl()) {
            mFaceProviderFunction = faceProviderFunction != null ? faceProviderFunction :
                    ((filteredSensorProps, resetLockoutRequiresChallenge) -> new FaceProvider(
                            getContext(), mBiometricStateCallback, mAuthenticationStateListeners,
                            filteredSensorProps.second,
                            filteredSensorProps.first, mLockoutResetDispatcher,
                            BiometricContext.getInstance(getContext()),
                            resetLockoutRequiresChallenge));
        } else {
            mFaceProviderFunction = ((filteredSensorProps, resetLockoutRequiresChallenge) -> null);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FACE_SERVICE, mServiceWrapper);
    }

    /**
     * Acquires a NativeHandle that can be used to access the provided surface. The returned handle
     * must be explicitly released with {@link #releaseSurfaceHandle(NativeHandle)} to avoid memory
     * leaks.
     *
     * The caller is responsible for ensuring that the surface is valid while using the handle.
     * This method provides no lifecycle synchronization between the surface and the handle.
     *
     * @param surface a valid Surface.
     * @return {@link android.os.NativeHandle} a NativeHandle for the provided surface.
     */
    public static native NativeHandle acquireSurfaceHandle(@NonNull Surface surface);

    /**
     * Releases resources associated with a NativeHandle that was acquired with
     * {@link #acquireSurfaceHandle(Surface)}.
     *
     * This method has no affect on the surface for which the handle was acquired. It only frees up
     * the resources that are associated with the handle.
     *
     * @param handle a handle that was obtained from {@link #acquireSurfaceHandle(Surface)}.
     */
    public static native void releaseSurfaceHandle(@NonNull NativeHandle handle);


    void syncEnrollmentsNow() {
        Utils.checkPermissionOrShell(getContext(), MANAGE_FACE);
        if (Flags.faceVhalFeature() && Utils.isVirtualEnabled(getContext())) {
            Slog.i(TAG, "Sync virtual enrollments");
            final int userId = ActivityManager.getCurrentUser();
            for (ServiceProvider provider : mRegistry.getProviders()) {
                for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                    provider.scheduleInternalCleanup(props.sensorId, userId, null /* callback */,
                            true /* favorHalEnrollments */);
                }
            }
        }
    }
}
