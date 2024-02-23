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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.IBinder;
import android.view.Surface;

import com.android.server.biometrics.sensors.BiometricServiceProvider;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import java.io.FileDescriptor;
import java.util.List;

/**
 * Superset of features across all the face HAL interfaces that are available to the framework. This
 * is more or less mapped to the public and private APIs that {@link FaceManager} provides, and
 * is used at the system server layer to provide easy mapping between requests and providers.
 *
 * Note that providers support both single-sensor and multi-sensor HALs. In either case,
 * {@link FaceService} must ensure that providers are only requested to perform operations
 * on sensors that they own.
 *
 * For methods other than {@link #containsSensor(int)}, the caller must ensure that the sensorId
 * is supported by the provider. For example:
 * if (serviceProvider.containsSensor(sensorId)) {
 * serviceProvider.operation(sensorId, ...);
 * }
 *
 * For operations that are supported by some providers but not others, clients are required
 * to check (e.g. via {@link FaceManager#getSensorPropertiesInternal()}) that the code path isn't
 * taken. ServiceProviders will provide a no-op for unsupported operations to fail safely.
 */
public interface ServiceProvider extends BiometricServiceProvider<FaceSensorPropertiesInternal> {

    @NonNull
    List<Face> getEnrolledFaces(int sensorId, int userId);

    /**
     * Requests for the authenticatorId (whose source of truth is in the TEE or equivalent) to be
     * invalidated. See {@link com.android.server.biometrics.sensors.InvalidationRequesterClient}
     */
    default void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback) {
        throw new IllegalStateException("Providers that support invalidation must override"
                + " this method");
    }

    void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, String opPackageName);

    void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge);

    long scheduleEnroll(int sensorId, @NonNull IBinder token, @NonNull byte[] hardwareAuthToken,
            int userId, @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName,
            @NonNull int[] disabledFeatures, @Nullable Surface previewSurface,
            boolean debugConsent, FaceEnrollOptions options);

    void cancelEnrollment(int sensorId, @NonNull IBinder token, long requestId);

    long scheduleFaceDetect(@NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options,
            int statsClient);

    void cancelFaceDetect(int sensorId, @NonNull IBinder token, long requestId);

    long scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options,
            boolean restricted, int statsClient, boolean allowBackgroundAuthentication);

    void scheduleAuthenticate(@NonNull IBinder token, long operationId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull FaceAuthenticateOptions options, long requestId,
            boolean restricted, int statsClient, boolean allowBackgroundAuthentication);

    void cancelAuthentication(int sensorId, @NonNull IBinder token, long requestId);

    void scheduleRemove(int sensorId, @NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName);

    void scheduleRemoveAll(int sensorId, @NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName);

    void scheduleResetLockout(int sensorId, int userId, @NonNull byte[] hardwareAuthToken);

    void scheduleSetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName);

    void scheduleGetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName);

    void startPreparedClient(int sensorId, int cookie);

    void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback);

    void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable ClientMonitorCallback callback, boolean favorHalEnrollments);

    @NonNull
    ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull String opPackageName);

    void dumpHal(int sensorId, @NonNull FileDescriptor fd, @NonNull String[] args);

    /**
     * Schedules watchdog for canceling hung operations
     * @param sensorId sensor ID of the associated operation
     */
    default void scheduleWatchdog(int sensorId) {}
}
