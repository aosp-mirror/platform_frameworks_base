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

import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.face.Face;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.FaceSensorProperties;
import android.os.Binder;
import android.os.IBinder;
import android.os.NativeHandle;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face-related events.
 */
public class FaceService extends SystemService {

    protected static final String TAG = "FaceService";

    private Face10 mFace10;
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    private final LockPatternUtils mLockPatternUtils;

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    private final class FaceServiceWrapper extends IFaceService.Stub {
        @Override // Binder call
        public List<FaceSensorProperties> getSensorProperties(String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            final List<FaceSensorProperties> properties = new ArrayList<>();

            if (mFace10 != null) {
                properties.add(mFace10.getFaceSensorProperties());
            }

            Slog.d(TAG, "Retrieved sensor properties for: " + opPackageName
                    + ", sensors: " + properties.size());
            return properties;
        }

        @Override // Binder call
        public void generateChallenge(IBinder token, IFaceServiceReceiver receiver,
                String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFace10.scheduleGenerateChallenge(token, receiver, opPackageName);
        }

        @Override // Binder call
        public void revokeChallenge(IBinder token, String owner) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFace10.scheduleRevokeChallenge(token, owner);
        }

        @Override // Binder call
        public void enroll(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures, Surface surface) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFace10.scheduleEnroll(token, hardwareAuthToken, userId, receiver, opPackageName,
                    disabledFeatures, convertSurfaceToNativeHandle(surface));
        }

        @Override // Binder call
        public void enrollRemotely(int userId, final IBinder token, final byte[] hardwareAuthToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            // TODO(b/145027036): Implement this.
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFace10.cancelEnrollment(token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long operationId, int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            // TODO(b/152413782): If the sensor supports face detect and the device is encrypted or
            //  lockdown, something wrong happened. See similar path in FingerprintService.

            final boolean restricted = false; // Face APIs are private
            final int statsClient = Utils.isKeyguard(getContext(), opPackageName)
                    ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_UNKNOWN;
            mFace10.scheduleAuthenticate(token, operationId, userId, 0 /* cookie */,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, restricted,
                    statsClient);
        }

        @Override // Binder call
        public void detectFace(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "detectFace called from non-sysui package: " + opPackageName);
                return;
            }

            if (!Utils.isUserEncryptedOrLockdown(mLockPatternUtils, userId)) {
                // If this happens, something in KeyguardUpdateMonitor is wrong. This should only
                // ever be invoked when the user is encrypted or lockdown.
                Slog.e(TAG, "detectFace invoked when user is not encrypted or lockdown");
                return;
            }

            // TODO(b/152413782): Implement this once it's supported in the HAL
        }

        @Override // Binder call
        public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
                long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
                String opPackageName, int cookie, int callingUid, int callingPid,
                int callingUserId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            final boolean restricted = true; // BiometricPrompt is always restricted
            mFace10.scheduleAuthenticate(token, operationId, userId, cookie,
                    new ClientMonitorCallbackConverter(sensorReceiver), opPackageName,
                    restricted, BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT);
        }

        @Override // Binder call
        public void startPreparedClient(int cookie) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.startPreparedClient(cookie);
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.cancelAuthentication(token);
        }

        @Override // Binder call
        public void cancelFaceDetect(final IBinder token, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (!Utils.isKeyguard(getContext(), opPackageName)) {
                Slog.w(TAG, "cancelFaceDetect called from non-sysui package: "
                        + opPackageName);
                return;
            }

            // TODO(b/152413782): Implement this once it's supported in the HAL
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final IBinder token, final String opPackageName,
                int callingUid, int callingPid, int callingUserId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.cancelAuthentication(token);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int faceId, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.scheduleRemove(token, faceId, userId, receiver, opPackageName);
        }

        @Override
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
                if (args.length > 1 && "--hal".equals(args[0])) {
                    mFace10.dumpHal(fd, Arrays.copyOfRange(args, 1, args.length, args.getClass()));
                } else {
                    mFace10.dump(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isHardwareDetected(String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            if (mFace10 == null) {
                Slog.wtf(TAG, "No HAL, caller: " + opPackageName);
                return false;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return mFace10.isHardwareDetected();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public List<Face> getEnrolledFaces(int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFace10.getEnrolledFaces(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFaces(int userId, String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return !mFace10.getEnrolledFaces(userId).isEmpty();
        }

        @Override
        public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFace10.getLockoutModeForUser(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int userId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            return mFace10.getAuthenticatorId(userId);
        }

        @Override // Binder call
        public void resetLockout(int userId, byte[] hardwareAuthToken) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.scheduleResetLockout(userId, hardwareAuthToken);
        }

        @Override
        public void setFeature(final IBinder token, int userId, int feature, boolean enabled,
                final byte[] hardwareAuthToken, IFaceServiceReceiver receiver,
                final String opPackageName) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10.scheduleSetFeature(token, userId, feature, enabled, hardwareAuthToken, receiver,
                    opPackageName);
        }

        @Override
        public void getFeature(final IBinder token, int userId, int feature,
                IFaceServiceReceiver receiver, final String opPackageName) {
            Utils.checkPermission(getContext(), MANAGE_BIOMETRIC);
            mFace10.scheduleGetFeature(token, userId, feature, receiver, opPackageName);
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
            mFace10 = new Face10(getContext(), sensorId, mLockoutResetDispatcher);
        }
    }

    public FaceService(Context context) {
        super(context);
        mLockoutResetDispatcher = new LockoutResetDispatcher(context);
        mLockPatternUtils = new LockPatternUtils(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FACE_SERVICE, new FaceServiceWrapper());
    }

    private native NativeHandle convertSurfaceToNativeHandle(Surface surface);
}
