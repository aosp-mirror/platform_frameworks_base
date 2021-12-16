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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.util.proto.ProtoOutputStream;

import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Superset of features/functionalities that HALs provide to the rest of the framework. This is
 * more or less mapped to the public and private APIs that {@link FingerprintManager} provide, and
 * is used at the system server layer to provide easy mapping between request and provider.
 *
 * Note that providers support both single-sensor and multi-sensor HALs. In either case,
 * {@link FingerprintService} must ensure that providers are only requested to perform operations
 * on sensors that they own.
 *
 * For methods other than {@link #containsSensor(int)}, the caller must ensure that the sensorId
 * passed in is supported by the provider. For example,
 * if (serviceProvider.containsSensor(sensorId)) {
 *     serviceProvider.operation(sensorId, ...);
 * }
 *
 * For operations that are supported by some providers but not others, clients are required
 * to check (e.g. via {@link FingerprintManager#getSensorPropertiesInternal()}) to ensure that the
 * code path isn't taken. ServiceProviders will provide a no-op for unsupported operations to
 * fail safely.
 */
@SuppressWarnings("deprecation")
public interface ServiceProvider {
    /**
     * Checks if the specified sensor is owned by this provider.
     */
    boolean containsSensor(int sensorId);

    @NonNull
    List<FingerprintSensorPropertiesInternal> getSensorProperties();

    /**
     * Returns the internal properties of the specified sensor, if owned by this provider.
     *
     * @param sensorId The ID of a fingerprint sensor, or -1 for any sensor.
     * @return An object representing the internal properties of the specified sensor.
     */
    @Nullable
    FingerprintSensorPropertiesInternal getSensorProperties(int sensorId);

    void scheduleResetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken);

    void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, String opPackageName);

    void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge);

    /**
     * Schedules fingerprint enrollment.
     */
    void scheduleEnroll(int sensorId, @NonNull IBinder token, @NonNull byte[] hardwareAuthToken,
            int userId, @NonNull IFingerprintServiceReceiver receiver,
            @NonNull String opPackageName, @FingerprintManager.EnrollReason int enrollReason);

    void cancelEnrollment(int sensorId, @NonNull IBinder token);

    long scheduleFingerDetect(int sensorId, @NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName,
            int statsClient);

    void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId, int userId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, long requestId, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication);

    long scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId, int userId,
            int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication);

    void startPreparedClient(int sensorId, int cookie);

    void cancelAuthentication(int sensorId, @NonNull IBinder token, long requestId);

    void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName);

    void scheduleRemoveAll(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int userId,
            @NonNull String opPackageName);

    void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable BaseClientMonitor.Callback callback);

    boolean isHardwareDetected(int sensorId);

    void rename(int sensorId, int fingerId, int userId, @NonNull String name);

    @NonNull
    List<Fingerprint> getEnrolledFingerprints(int sensorId, int userId);

    @LockoutTracker.LockoutMode
    int getLockoutModeForUser(int sensorId, int userId);

    /**
     * Requests for the authenticatorId (whose source of truth is in the TEE or equivalent) to
     * be invalidated. See {@link com.android.server.biometrics.sensors.InvalidationRequesterClient}
     */
    void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback);

    long getAuthenticatorId(int sensorId, int userId);

    void onPointerDown(int sensorId, int x, int y, float minor, float major);

    void onPointerUp(int sensorId);

    void onUiReady(int sensorId);

    void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller);

    /**
     * Sets side-fps controller
     * @param controller side-fps controller
     */
    void setSidefpsController(@NonNull ISidefpsController controller);

    void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer);

    void dumpProtoMetrics(int sensorId, @NonNull FileDescriptor fd);

    void dumpInternal(int sensorId, @NonNull PrintWriter pw);

    @NonNull
    ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull String opPackageName);
}
