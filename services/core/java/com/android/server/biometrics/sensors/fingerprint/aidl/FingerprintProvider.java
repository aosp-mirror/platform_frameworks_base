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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Surface;

import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.ServiceProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider for a single instance of the {@link IFingerprint} HAL.
 */
public class FingerprintProvider implements IBinder.DeathRecipient, ServiceProvider {

    @NonNull private final Context mContext;
    @NonNull private final String mHalInstanceName;
    @NonNull private final SparseArray<Sensor> mSensors; // Map of sensors that this HAL supports
    @NonNull private final ClientMonitor.LazyDaemon<IFingerprint> mLazyDaemon;
    @NonNull private final Handler mHandler;

    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;

    public FingerprintProvider(@NonNull Context context, @NonNull SensorProps[] props,
            @NonNull String halInstanceName, @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        mContext = context;
        mHalInstanceName = halInstanceName;
        mSensors = new SparseArray<>();
        mLazyDaemon = this::getHalInstance;
        mHandler = new Handler(Looper.getMainLooper());

        for (SensorProps prop : props) {
            final int sensorId = prop.commonProps.sensorId;

            final FingerprintSensorPropertiesInternal internalProp =
                    new FingerprintSensorPropertiesInternal(prop.commonProps.sensorId,
                            prop.commonProps.sensorStrength,
                            prop.commonProps.maxEnrollmentsPerUser,
                            prop.sensorType,
                            true /* resetLockoutRequiresHardwareAuthToken */);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, internalProp,
                    gestureAvailabilityDispatcher);

            mSensors.put(sensorId, sensor);
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FingerprintProvider/" + mHalInstanceName;
    }

    @Nullable
    private synchronized IFingerprint getHalInstance() {
        final IFingerprint daemon = IFingerprint.Stub.asInterface(
                ServiceManager.waitForDeclaredService(mHalInstanceName));
        if (daemon == null) {
            Slog.e(getTag(), "Unable to get daemon");
            return null;
        }

        try {
            daemon.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(getTag(), "Unable to linkToDeath", e);
        }

        for (int i = 0; i < mSensors.size(); i++) {
            final int sensorId = mSensors.keyAt(i);
            scheduleLoadAuthenticatorIds(sensorId);
            scheduleInternalCleanup(sensorId, ActivityManager.getCurrentUser());
        }

        return daemon;
    }

    private void scheduleForSensor(int sensorId, @NonNull ClientMonitor<?> client) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
    }

    private void scheduleForSensor(int sensorId, @NonNull ClientMonitor<?> client,
            ClientMonitor.Callback callback) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client, callback);
    }

    private void scheduleCreateSessionWithoutHandler(@NonNull IFingerprint daemon, int sensorId,
            int userId) throws RemoteException {
        // Note that per IFingerprint createSession contract, this method will block until all
        // existing operations are canceled/finished. However, also note that this is fine, since
        // this method "withoutHandler" means it should only ever be invoked from the worker thread,
        // so callers will never be blocked.
        mSensors.get(sensorId).createNewSession(daemon, sensorId, userId);
    }

    private void scheduleLoadAuthenticatorIdsWithoutHandler(int sensorId) {

    }

    private void scheduleLoadAuthenticatorIds(int sensorId) {

    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensors.contains(sensorId);
    }

    @NonNull
    @Override
    public List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        for (int i = 0; i < mSensors.size(); i++) {
            props.add(mSensors.valueAt(i).getSensorProperties());
        }
        return props;
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken) {

    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final FingerprintGenerateChallengeClient client =
                    new FingerprintGenerateChallengeClient(mContext, mLazyDaemon, token,
                            new ClientMonitorCallbackConverter(receiver), opPackageName, sensorId);
            mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, @NonNull IBinder token,
            @NonNull String opPackageName) {

    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token, byte[] hardwareAuthToken,
            int userId, @NonNull IFingerprintServiceReceiver receiver,
            @NonNull String opPackageName, @Nullable Surface surface) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during enroll, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    scheduleCreateSessionWithoutHandler(daemon, sensorId, userId);
                }

                final int maxTemplatesPerUser = mSensors.get(sensorId).getSensorProperties()
                        .maxEnrollmentsPerUser;
                final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                        opPackageName, FingerprintUtils.getInstance(),
                        BiometricsProtoEnums.MODALITY_FINGERPRINT, sensorId,
                        mUdfpsOverlayController, maxTemplatesPerUser);
                scheduleForSensor(sensorId, client, new ClientMonitor.Callback() {
                    @Override
                    public void onClientFinished(@NonNull ClientMonitor<?> clientMonitor,
                            boolean success) {
                        if (success) {
                            scheduleLoadAuthenticatorIdsWithoutHandler(sensorId);
                        }
                    }
                });
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling enroll", e);
            }
        });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token) {

    }

    @Override
    public void scheduleFingerDetect(int sensorId, @NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName,
            @Nullable Surface surface, int statsClient) {

    }

    @Override
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean isKeyguard) {

    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {

    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {

    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {

    }

    @Override
    public void scheduleInternalCleanup(int userId, int sensorId) {

    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return false;
    }

    @Override
    public void rename(int sensorId, int fingerId, int userId, @NonNull String name) {

    }

    @NonNull
    @Override
    public List<Fingerprint> getEnrolledFingerprints(int sensorId, int userId) {
        return new ArrayList<>();
    }

    @Override
    public int getLockoutModeForUser(int sensorId, int userId) {
        return 0;
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return 0;
    }

    @Override
    public void onPointerDown(int sensorId, int x, int y, float minor, float major) {

    }

    @Override
    public void onPointerUp(int sensorId) {

    }

    @Override
    public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
        mUdfpsOverlayController = controller;
    }

    @Override
    public void dumpProto(int sensorId, @NonNull FileDescriptor fd) {

    }

    @Override
    public void dumpInternal(int sensorId, @NonNull PrintWriter pw) {

    }

    @Override
    public void binderDied() {

    }
}
