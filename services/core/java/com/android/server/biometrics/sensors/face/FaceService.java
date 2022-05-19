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
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.MANAGE_FACE;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.FaceServiceReceiver;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.aidl.FaceProvider;
import com.android.server.biometrics.sensors.face.hidl.Face10;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face-related events.
 */
public class FaceService extends SystemService {

    protected static final String TAG = "FaceService";

    private final FaceServiceWrapper mServiceWrapper;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final List<ServiceProvider> mServiceProviders;

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
     * For devices with only a single provider, returns that provider. If no providers, or multiple
     * providers exist, returns null.
     */
    @Nullable
    private Pair<Integer, ServiceProvider> getSingleProvider() {
        final List<FaceSensorPropertiesInternal> properties = getSensorProperties();
        if (properties.size() != 1) {
            Slog.e(TAG, "Multiple sensors found: " + properties.size());
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

        Slog.e(TAG, "Single sensor, but provider not found");
        return null;
    }

    @NonNull
    private List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> properties = new ArrayList<>();
        for (ServiceProvider provider : mServiceProviders) {
            properties.addAll(provider.getSensorProperties());
        }
        return properties;
    }

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    private final class FaceServiceWrapper extends IFaceService.Stub {
        @Override
        public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
                @NonNull String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

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
        public List<FaceSensorPropertiesInternal> getSensorPropertiesInternal(
                String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            return FaceService.this.getSensorProperties();
        }

        @Override // Binder call
        public FaceSensorPropertiesInternal getSensorProperties(int sensorId,
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
                IFaceServiceReceiver receiver, String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

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
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "No matching sensor for revokeChallenge, sensorId: " + sensorId);
                return;
            }

            provider.scheduleRevokeChallenge(sensorId, userId, token, opPackageName, challenge);
        }

        @Override // Binder call
        public long enroll(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures, Surface previewSurface, boolean debugConsent) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for enroll");
                return -1;
            }

            return provider.second.scheduleEnroll(provider.first, token, hardwareAuthToken, userId,
                    receiver, opPackageName, disabledFeatures, previewSurface, debugConsent);
        }

        @Override // Binder call
        public long enrollRemotely(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            // TODO(b/145027036): Implement this.
            return -1;
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token, long requestId) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelEnrollment");
                return;
            }

            provider.second.cancelEnrollment(provider.first, token, requestId);
        }

        @Override // Binder call
        public long authenticate(final IBinder token, final long operationId, int userId,
                final IFaceServiceReceiver receiver, final String opPackageName,
                boolean isKeyguardBypassEnabled) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            // TODO(b/152413782): If the sensor supports face detect and the device is encrypted or
            //  lockdown, something wrong happened. See similar path in FingerprintService.

            final boolean restricted = false; // Face APIs are private
            final int statsClient = Utils.isKeyguard(getContext(), opPackageName)
                    ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_UNKNOWN;

            // Keyguard check must be done on the caller's binder identity, since it also checks
            // permission.
            final boolean isKeyguard = Utils.isKeyguard(getContext(), opPackageName);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for authenticate");
                return -1;
            }

            return provider.second.scheduleAuthenticate(provider.first, token, operationId, userId,
                    0 /* cookie */,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, restricted,
                    statsClient, isKeyguard, isKeyguardBypassEnabled);
        }

        @Override // Binder call
        public long detectFace(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFace called from non-sysui package: " + opPackageName);
                return -1;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFace invoked when user is not encrypted or lockdown");
                return -1;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for detectFace");
                return -1;
            }

            return provider.second.scheduleFaceDetect(provider.first, token, userId,
                    new ClientMonitorCallbackConverter(receiver), opPackageName,
                    BiometricsProtoEnums.CLIENT_KEYGUARD);
        }

        @Override // Binder call
        public void prepareForAuthentication(int sensorId, boolean requireConfirmation,
                IBinder token, long operationId, int userId,
                IBiometricSensorReceiver sensorReceiver, String opPackageName, long requestId,
                int cookie, boolean allowBackgroundAuthentication) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for prepareForAuthentication");
                return;
            }

            final boolean isKeyguardBypassEnabled = false; // only valid for keyguard clients
            final boolean restricted = true; // BiometricPrompt is always restricted
            provider.scheduleAuthenticate(sensorId, token, operationId, userId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), opPackageName, requestId,
                    restricted, BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    allowBackgroundAuthentication, isKeyguardBypassEnabled);
        }

        @Override // Binder call
        public void startPreparedClient(int sensorId, int cookie) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for startPreparedClient");
                return;
            }

            provider.startPreparedClient(sensorId, cookie);
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName,
                final long requestId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthentication");
                return;
            }

            provider.second.cancelAuthentication(provider.first, token, requestId);
        }

        @Override // Binder call
        public void cancelFaceDetect(final IBinder token, final String opPackageName,
                final long requestId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFaceDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelFaceDetect");
                return;
            }

            provider.second.cancelFaceDetect(provider.first, token, requestId);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(int sensorId, final IBinder token,
                final String opPackageName, final long requestId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for cancelAuthenticationFromService");
                return;
            }

            provider.cancelAuthentication(sensorId, token, requestId);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int faceId, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for remove");
                return;
            }

            provider.second.scheduleRemove(provider.first, token, faceId, userId, receiver,
                    opPackageName);
        }

        @Override // Binder call
        public void removeAll(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

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
            };

            // This effectively iterates through all sensors, but has to do so by finding all
            // sensors under each provider.
            for (ServiceProvider provider : mServiceProviders) {
                List<FaceSensorPropertiesInternal> props = provider.getSensorProperties();
                for (FaceSensorPropertiesInternal prop : props) {
                    provider.scheduleRemoveAll(prop.sensorId, token, userId, internalReceiver,
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
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoState(props.sensorId, proto, false);
                        }
                    }
                    proto.flush();
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpProtoMetrics(props.sensorId, fd);
                        }
                    }
                } else if (args.length > 1 && "--hal".equals(args[0])) {
                    for (ServiceProvider provider : mServiceProviders) {
                        for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                            provider.dumpHal(props.sensorId, fd,
                                    Arrays.copyOfRange(args, 1, args.length, args.getClass()));
                        }
                    }
                } else {
                    for (ServiceProvider provider : mServiceProviders) {
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

        @Override // Binder call
        public boolean isHardwareDetected(int sensorId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final long token = Binder.clearCallingIdentity();
            try {
                final ServiceProvider provider = getProviderForSensor(sensorId);
                if (provider == null) {
                    Slog.w(TAG, "Null provider for isHardwareDetected, caller: " + opPackageName);
                    return false;
                }
                return provider.isHardwareDetected(sensorId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public List<Face> getEnrolledFaces(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for getEnrolledFaces, caller: " + opPackageName);
                return Collections.emptyList();
            }

            return provider.getEnrolledFaces(sensorId, userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFaces(int sensorId, int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            if (userId != UserHandle.getCallingUserId()) {
                Utils.checkPermission(getContext(), INTERACT_ACROSS_USERS);
            }

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for hasEnrolledFaces, caller: " + opPackageName);
                return false;
            }

            return provider.getEnrolledFaces(sensorId, userId).size() > 0;
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
        public void resetLockout(IBinder token, int sensorId, int userId, byte[] hardwareAuthToken,
                String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final ServiceProvider provider = getProviderForSensor(sensorId);
            if (provider == null) {
                Slog.w(TAG, "Null provider for resetLockout, caller: " + opPackageName);
                return;
            }

            provider.scheduleResetLockout(sensorId, userId, hardwareAuthToken);
        }

        @Override
        public void setFeature(final IBinder token, int userId, int feature, boolean enabled,
                final byte[] hardwareAuthToken, IFaceServiceReceiver receiver,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for setFeature");
                return;
            }

            provider.second.scheduleSetFeature(provider.first, token, userId, feature, enabled,
                    hardwareAuthToken, receiver, opPackageName);
        }

        @Override
        public void getFeature(final IBinder token, int userId, int feature,
                IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);

            final Pair<Integer, ServiceProvider> provider = getSingleProvider();
            if (provider == null) {
                Slog.w(TAG, "Null provider for getFeature");
                return;
            }

            provider.second.scheduleGetFeature(provider.first, token, userId, feature,
                    new ClientMonitorCallbackConverter(receiver), opPackageName);
        }

        private void addHidlProviders(@NonNull List<FaceSensorPropertiesInternal> hidlSensors) {
            for (FaceSensorPropertiesInternal hidlSensor : hidlSensors) {
                mServiceProviders.add(
                        Face10.newInstance(getContext(), hidlSensor, mLockoutResetDispatcher));
            }
        }

        private void addAidlProviders() {
            final String[] instances = ServiceManager.getDeclaredInstances(IFace.DESCRIPTOR);
            if (instances == null || instances.length == 0) {
                return;
            }
            for (String instance : instances) {
                final String fqName = IFace.DESCRIPTOR + "/" + instance;
                final IFace face = IFace.Stub.asInterface(
                        Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName)));
                if (face == null) {
                    Slog.e(TAG, "Unable to get declared service: " + fqName);
                    continue;
                }
                try {
                    final SensorProps[] props = face.getSensorProps();
                    final FaceProvider provider = new FaceProvider(getContext(), props, instance,
                            mLockoutResetDispatcher, BiometricContext.getInstance(getContext()));
                    mServiceProviders.add(provider);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception in getSensorProps: " + fqName);
                }
            }
        }

        @Override // Binder call
        public void registerAuthenticators(
                @NonNull List<FaceSensorPropertiesInternal> hidlSensors) {
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
                    final List<FaceSensorPropertiesInternal> props = provider.getSensorProperties();
                    for (FaceSensorPropertiesInternal prop : props) {
                        final int sensorId = prop.sensorId;
                        final @BiometricManager.Authenticators.Types int strength =
                                Utils.propertyStrengthToAuthenticatorStrength(prop.sensorStrength);
                        final FaceAuthenticator authenticator = new FaceAuthenticator(
                                mServiceWrapper, sensorId);
                        try {
                            biometricService.registerAuthenticator(sensorId, TYPE_FACE, strength,
                                    authenticator);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Remote exception when registering sensorId: " + sensorId);
                        }
                    }
                }
            });
        }
    }

    public FaceService(Context context) {
        super(context);
        mServiceWrapper = new FaceServiceWrapper();
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mServiceProviders = new ArrayList<>();
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
        if (Utils.isVirtualEnabled(getContext())) {
            Slog.i(TAG, "Sync virtual enrollments");
            final int userId = ActivityManager.getCurrentUser();
            for (ServiceProvider provider : mServiceProviders) {
                for (FaceSensorPropertiesInternal props : provider.getSensorProperties()) {
                    provider.scheduleInternalCleanup(props.sensorId, userId, null /* callback */,
                            true /* favorHalEnrollments */);
                }
            }
        }
    }
}
