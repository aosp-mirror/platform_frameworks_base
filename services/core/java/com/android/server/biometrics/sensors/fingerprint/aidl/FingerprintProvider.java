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
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.UserInfo;
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
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Surface;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.ServiceProvider;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider for a single instance of the {@link IFingerprint} HAL.
 */
@SuppressWarnings("deprecation")
public class FingerprintProvider implements IBinder.DeathRecipient, ServiceProvider {

    @NonNull private final Context mContext;
    @NonNull private final String mHalInstanceName;
    @NonNull private final SparseArray<Sensor> mSensors; // Map of sensors that this HAL supports
    @NonNull private final ClientMonitor.LazyDaemon<IFingerprint> mLazyDaemon;
    @NonNull private final Handler mHandler;
    @NonNull private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull private final IActivityTaskManager mActivityTaskManager;
    @NonNull private final BiometricTaskStackListener mTaskStackListener;

    @Nullable private IFingerprint mDaemon;
    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                for (int i = 0; i < mSensors.size(); i++) {
                    final ClientMonitor<?> client = mSensors.get(i).getScheduler()
                            .getCurrentClient();
                    if (!(client instanceof AuthenticationClient)) {
                        Slog.e(getTag(), "Task stack changed for client: " + client);
                        continue;
                    }
                    if (Utils.isKeyguard(mContext, client.getOwnerString())) {
                        continue; // Keyguard is always allowed
                    }

                    try {
                        final List<ActivityManager.RunningTaskInfo> runningTasks =
                                mActivityTaskManager.getTasks(1);
                        if (!runningTasks.isEmpty()) {
                            final String topPackage =
                                    runningTasks.get(0).topActivity.getPackageName();
                            if (!topPackage.contentEquals(client.getOwnerString())
                                    && !client.isAlreadyDone()) {
                                Slog.e(getTag(), "Stopping background authentication, top: "
                                        + topPackage + " currentClient: " + client);
                                mSensors.get(i).getScheduler()
                                        .cancelAuthentication(client.getToken());
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Unable to get running tasks", e);
                    }
                }
            });
        }
    }

    public FingerprintProvider(@NonNull Context context, @NonNull SensorProps[] props,
            @NonNull String halInstanceName, @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        mContext = context;
        mHalInstanceName = halInstanceName;
        mSensors = new SparseArray<>();
        mLazyDaemon = this::getHalInstance;
        mHandler = new Handler(Looper.getMainLooper());
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mActivityTaskManager = ActivityTaskManager.getService();
        mTaskStackListener = new BiometricTaskStackListener();

        for (SensorProps prop : props) {
            final int sensorId = prop.commonProps.sensorId;

            final FingerprintSensorPropertiesInternal internalProp =
                    new FingerprintSensorPropertiesInternal(prop.commonProps.sensorId,
                            prop.commonProps.sensorStrength,
                            prop.commonProps.maxEnrollmentsPerUser,
                            prop.sensorType,
                            true /* resetLockoutRequiresHardwareAuthToken */);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, mContext, mHandler,
                    internalProp, gestureAvailabilityDispatcher);

            mSensors.put(sensorId, sensor);
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FingerprintProvider/" + mHalInstanceName;
    }

    @Nullable
    private synchronized IFingerprint getHalInstance() {
        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(getTag(), "Daemon was null, reconnecting");

        mDaemon = IFingerprint.Stub.asInterface(
                ServiceManager.waitForDeclaredService(mHalInstanceName));
        if (mDaemon == null) {
            Slog.e(getTag(), "Unable to get daemon");
            return null;
        }

        try {
            mDaemon.asBinder().linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(getTag(), "Unable to linkToDeath", e);
        }

        for (int i = 0; i < mSensors.size(); i++) {
            final int sensorId = mSensors.keyAt(i);
            scheduleLoadAuthenticatorIds(sensorId);
            scheduleInternalCleanup(sensorId, ActivityManager.getCurrentUser());
        }

        return mDaemon;
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

    private void createNewSessionWithoutHandler(@NonNull IFingerprint daemon, int sensorId,
            int userId) throws RemoteException {
        // Note that per IFingerprint createSession contract, this method will block until all
        // existing operations are canceled/finished. However, also note that this is fine, since
        // this method "withoutHandler" means it should only ever be invoked from the worker thread,
        // so callers will never be blocked.
        mSensors.get(sensorId).createNewSession(daemon, sensorId, userId);
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensors.contains(sensorId);
    }

    @NonNull
    @Override
    public List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        final List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        for (int i = 0; i < mSensors.size(); i++) {
            props.add(mSensors.valueAt(i).getSensorProperties());
        }
        return props;
    }

    private void scheduleLoadAuthenticatorIds(int sensorId) {
        for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
            scheduleLoadAuthenticatorIdsForUser(sensorId, user.id);
        }
    }

    private void scheduleLoadAuthenticatorIdsForUser(int sensorId, int userId) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during loadAuthenticatorIds, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FingerprintGetAuthenticatorIdClient client =
                        new FingerprintGetAuthenticatorIdClient(mContext,
                                mSensors.get(sensorId).getLazySession(), userId,
                                mContext.getOpPackageName(), sensorId,
                                mSensors.get(sensorId).getAuthenticatorIds());
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling loadAuthenticatorId"
                        + ", sensorId: " + sensorId
                        + ", userId: " + userId, e);
            }
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during resetLockout, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FingerprintResetLockoutClient client = new FingerprintResetLockoutClient(
                        mContext, mSensors.get(sensorId).getLazySession(), userId,
                        mContext.getOpPackageName(), sensorId, hardwareAuthToken,
                        mSensors.get(sensorId).getLockoutCache(), mLockoutResetDispatcher);
                scheduleForSensor(sensorId, client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling resetLockout", e);
            }
        });
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final FingerprintGenerateChallengeClient client =
                    new FingerprintGenerateChallengeClient(mContext, mLazyDaemon, token,
                            new ClientMonitorCallbackConverter(receiver), opPackageName, sensorId);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final FingerprintRevokeChallengeClient client =
                    new FingerprintRevokeChallengeClient(mContext, mLazyDaemon, token,
                            opPackageName, sensorId, challenge);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token, byte[] hardwareAuthToken,
            int userId, @NonNull IFingerprintServiceReceiver receiver,
            @NonNull String opPackageName, @Nullable Surface surface) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during enroll, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final int maxTemplatesPerUser = mSensors.get(sensorId).getSensorProperties()
                        .maxEnrollmentsPerUser;
                final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                        opPackageName, FingerprintUtils.getInstance(sensorId), sensorId,
                        mUdfpsOverlayController, maxTemplatesPerUser);
                scheduleForSensor(sensorId, client, new ClientMonitor.Callback() {
                    @Override
                    public void onClientFinished(@NonNull ClientMonitor<?> clientMonitor,
                            boolean success) {
                        if (success) {
                            scheduleLoadAuthenticatorIdsForUser(sensorId, userId);
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
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelEnrollment(token));
    }

    @Override
    public void scheduleFingerDetect(int sensorId, @NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName,
            @Nullable Surface surface, int statsClient) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during finger detect, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
                final FingerprintDetectClient client = new FingerprintDetectClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token, callback, userId,
                        opPackageName, sensorId, mUdfpsOverlayController, isStrongBiometric,
                        statsClient);
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling finger detect", e);
            }
        });
    }

    @Override
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean isKeyguard) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during authenticate, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
                final FingerprintAuthenticationClient client = new FingerprintAuthenticationClient(
                        mContext, mSensors.get(sensorId).getLazySession(), token, callback, userId,
                        operationId, restricted, opPackageName, cookie,
                        false /* requireConfirmation */, sensorId, isStrongBiometric, statsClient,
                        mTaskStackListener, mSensors.get(sensorId).getLockoutCache(),
                        mUdfpsOverlayController);
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling authenticate", e);
            }
        });
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().startPreparedClient(cookie));
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelAuthentication(token));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during remove, sensorId: " + sensorId);
                // If this happens, we need to send HW_UNAVAILABLE after the scheduler gets to
                // this operation. We should not send the callback yet, since the scheduler may
                // be processing something else.
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                        mSensors.get(sensorId).getLazySession(), token,
                        new ClientMonitorCallbackConverter(receiver), fingerId, userId,
                        opPackageName, FingerprintUtils.getInstance(sensorId), sensorId,
                        mSensors.get(sensorId).getAuthenticatorIds());
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling remove", e);
            }
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId) {
        mHandler.post(() -> {
            final IFingerprint daemon = getHalInstance();
            if (daemon == null) {
                Slog.e(getTag(), "Null daemon during internal cleanup, sensorId: " + sensorId);
                return;
            }

            try {
                if (!mSensors.get(sensorId).hasSessionForUser(userId)) {
                    createNewSessionWithoutHandler(daemon, sensorId, userId);
                }

                final List<Fingerprint> enrolledList = getEnrolledFingerprints(sensorId, userId);
                final FingerprintInternalCleanupClient client =
                        new FingerprintInternalCleanupClient(mContext,
                                mSensors.get(sensorId).getLazySession(), userId,
                                mContext.getOpPackageName(), sensorId, enrolledList,
                                FingerprintUtils.getInstance(sensorId),
                                mSensors.get(sensorId).getAuthenticatorIds());
                mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when scheduling internal cleanup", e);
            }
        });
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return getHalInstance() != null;
    }

    @Override
    public void rename(int sensorId, int fingerId, int userId, @NonNull String name) {
        FingerprintUtils.getInstance(sensorId)
                .renameBiometricForUser(mContext, userId, fingerId, name);
    }

    @NonNull
    @Override
    public List<Fingerprint> getEnrolledFingerprints(int sensorId, int userId) {
        return FingerprintUtils.getInstance(sensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public int getLockoutModeForUser(int sensorId, int userId) {
        return mSensors.get(sensorId).getLockoutCache().getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int sensorId, int userId) {
        return mSensors.get(sensorId).getAuthenticatorIds().getOrDefault(userId, 0L);
    }

    @Override
    public void onPointerDown(int sensorId, int x, int y, float minor, float major) {
        final ClientMonitor<?> client = mSensors.get(sensorId).getScheduler().getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.e(getTag(), "onPointerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerDown(x, y, minor, major);
    }

    @Override
    public void onPointerUp(int sensorId) {
        final ClientMonitor<?> client = mSensors.get(sensorId).getScheduler().getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.e(getTag(), "onPointerUp received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerUp();
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
        Slog.e(getTag(), "HAL died");
        mHandler.post(() -> {
            mDaemon = null;

            for (int i = 0; i < mSensors.size(); i++) {
                final int sensorId = mSensors.keyAt(i);
                PerformanceTracker.getInstanceForSensorId(sensorId).incrementHALDeathCount();
            }
        });
    }
}
