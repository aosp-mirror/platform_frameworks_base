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

import static android.hardware.fingerprint.FingerprintManager.SENSOR_ID_ANY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.common.ComponentInfo;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.InvalidationRequesterClient;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.fingerprint.FingerprintStateCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.ServiceProvider;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider for a single instance of the {@link IFingerprint} HAL.
 */
@SuppressWarnings("deprecation")
public class FingerprintProvider implements IBinder.DeathRecipient, ServiceProvider {

    private boolean mTestHalEnabled;

    @NonNull private final Context mContext;
    @NonNull private final String mHalInstanceName;
    @NonNull @VisibleForTesting
    final SparseArray<Sensor> mSensors; // Map of sensors that this HAL supports
    @NonNull private final Handler mHandler;
    @NonNull private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull private final ActivityTaskManager mActivityTaskManager;
    @NonNull private final BiometricTaskStackListener mTaskStackListener;

    @Nullable private IFingerprint mDaemon;
    @Nullable private IUdfpsOverlayController mUdfpsOverlayController;
    @Nullable private ISidefpsController mSidefpsController;

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> {
                for (int i = 0; i < mSensors.size(); i++) {
                    final BaseClientMonitor client = mSensors.valueAt(i).getScheduler()
                            .getCurrentClient();
                    if (!(client instanceof AuthenticationClient)) {
                        Slog.e(getTag(), "Task stack changed for client: " + client);
                        continue;
                    }
                    if (Utils.isKeyguard(mContext, client.getOwnerString())
                            || Utils.isSystem(mContext, client.getOwnerString())) {
                        continue; // Keyguard is always allowed
                    }

                    final List<ActivityManager.RunningTaskInfo> runningTasks =
                            mActivityTaskManager.getTasks(1);
                    if (!runningTasks.isEmpty()) {
                        final String topPackage =
                                runningTasks.get(0).topActivity.getPackageName();
                        if (!topPackage.contentEquals(client.getOwnerString())
                                && !client.isAlreadyDone()) {
                            Slog.e(getTag(), "Stopping background authentication, top: "
                                    + topPackage + " currentClient: " + client);
                            mSensors.valueAt(i).getScheduler()
                                    .cancelAuthenticationOrDetection(client.getToken());
                        }
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
        mHandler = new Handler(Looper.getMainLooper());
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mActivityTaskManager = ActivityTaskManager.getInstance();
        mTaskStackListener = new BiometricTaskStackListener();

        for (SensorProps prop : props) {
            final int sensorId = prop.commonProps.sensorId;

            final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
            if (prop.commonProps.componentInfo != null) {
                for (ComponentInfo info : prop.commonProps.componentInfo) {
                    componentInfo.add(new ComponentInfoInternal(info.componentId,
                            info.hardwareVersion, info.firmwareVersion, info.serialNumber,
                            info.softwareVersion));
                }
            }

            final FingerprintSensorPropertiesInternal internalProp =
                    new FingerprintSensorPropertiesInternal(prop.commonProps.sensorId,
                            prop.commonProps.sensorStrength,
                            prop.commonProps.maxEnrollmentsPerUser,
                            componentInfo,
                            prop.sensorType,
                            true /* resetLockoutRequiresHardwareAuthToken */,
                            prop.sensorLocations[0].sensorLocationX,
                            prop.sensorLocations[0].sensorLocationY,
                            prop.sensorLocations[0].sensorRadius);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, this, mContext, mHandler,
                    internalProp, lockoutResetDispatcher, gestureAvailabilityDispatcher);

            mSensors.put(sensorId, sensor);
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FingerprintProvider/" + mHalInstanceName;
    }

    boolean hasHalInstance() {
        if (mTestHalEnabled) {
            return true;
        }
        return (ServiceManager.checkService(IFingerprint.DESCRIPTOR + "/" + mHalInstanceName)
                != null);
    }

    @Nullable
    @VisibleForTesting
    synchronized IFingerprint getHalInstance() {
        if (mTestHalEnabled) {
            // Enabling the test HAL for a single sensor in a multi-sensor HAL currently enables
            // the test HAL for all sensors under that HAL. This can be updated in the future if
            // necessary.
            return new TestHal();
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(getTag(), "Daemon was null, reconnecting");

        mDaemon = IFingerprint.Stub.asInterface(
                Binder.allowBlocking(
                        ServiceManager.waitForDeclaredService(
                                IFingerprint.DESCRIPTOR + "/" + mHalInstanceName)));
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
            scheduleInternalCleanup(sensorId, ActivityManager.getCurrentUser(),
                    null /* callback */);
        }

        return mDaemon;
    }

    private void scheduleForSensor(int sensorId, @NonNull BaseClientMonitor client) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client);
    }

    private void scheduleForSensor(int sensorId, @NonNull BaseClientMonitor client,
            BaseClientMonitor.Callback callback) {
        if (!mSensors.contains(sensorId)) {
            throw new IllegalStateException("Unable to schedule client: " + client
                    + " for sensor: " + sensorId);
        }
        mSensors.get(sensorId).getScheduler().scheduleClientMonitor(client, callback);
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

    @Nullable
    @Override
    public FingerprintSensorPropertiesInternal getSensorProperties(int sensorId) {
        if (mSensors.size() == 0) {
            return null;
        } else if (sensorId == SENSOR_ID_ANY) {
            return mSensors.valueAt(0).getSensorProperties();
        } else {
            final Sensor sensor = mSensors.get(sensorId);
            return sensor != null ? sensor.getSensorProperties() : null;
        }
    }

    private void scheduleLoadAuthenticatorIds(int sensorId) {
        for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
            scheduleLoadAuthenticatorIdsForUser(sensorId, user.id);
        }
    }

    private void scheduleLoadAuthenticatorIdsForUser(int sensorId, int userId) {
        mHandler.post(() -> {
            final FingerprintGetAuthenticatorIdClient client =
                    new FingerprintGetAuthenticatorIdClient(mContext,
                            mSensors.get(sensorId).getLazySession(), userId,
                            mContext.getOpPackageName(), sensorId,
                            mSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client);
        });
    }

    void scheduleInvalidationRequest(int sensorId, int userId) {
        mHandler.post(() -> {
            final InvalidationRequesterClient<Fingerprint> client =
                    new InvalidationRequesterClient<>(mContext, userId, sensorId,
                            FingerprintUtils.getInstance(sensorId));
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @Nullable byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            final FingerprintResetLockoutClient client = new FingerprintResetLockoutClient(
                    mContext, mSensors.get(sensorId).getLazySession(), userId,
                    mContext.getOpPackageName(), sensorId, hardwareAuthToken,
                    mSensors.get(sensorId).getLockoutCache(), mLockoutResetDispatcher);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final FingerprintGenerateChallengeClient client =
                    new FingerprintGenerateChallengeClient(mContext,
                            mSensors.get(sensorId).getLazySession(), token,
                            new ClientMonitorCallbackConverter(receiver), userId, opPackageName,
                            sensorId);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final FingerprintRevokeChallengeClient client =
                    new FingerprintRevokeChallengeClient(mContext,
                            mSensors.get(sensorId).getLazySession(), token,
                            userId, opPackageName, sensorId, challenge);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token, byte[] hardwareAuthToken,
            int userId, @NonNull IFingerprintServiceReceiver receiver,
            @NonNull String opPackageName, @FingerprintManager.EnrollReason int enrollReason,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            final int maxTemplatesPerUser = mSensors.get(sensorId).getSensorProperties()
                    .maxEnrollmentsPerUser;
            final FingerprintEnrollClient client = new FingerprintEnrollClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                    opPackageName, FingerprintUtils.getInstance(sensorId), sensorId,
                    mSensors.get(sensorId).getSensorProperties(),
                    mUdfpsOverlayController, mSidefpsController, maxTemplatesPerUser, enrollReason);
            scheduleForSensor(sensorId, client, new BaseClientMonitor.Callback() {

                @Override
                public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                    fingerprintStateCallback.onClientStarted(clientMonitor);
                }

                @Override
                public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                        boolean success) {
                    fingerprintStateCallback.onClientFinished(clientMonitor, success);
                    if (success) {
                        scheduleLoadAuthenticatorIdsForUser(sensorId, userId);
                        scheduleInvalidationRequest(sensorId, userId);
                    }
                }
            });
        });
    }

    @Override
    public void cancelEnrollment(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelEnrollment(token));
    }

    @Override
    public void scheduleFingerDetect(int sensorId, @NonNull IBinder token, int userId,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName,
            int statsClient,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
            final FingerprintDetectClient client = new FingerprintDetectClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token, callback, userId,
                    opPackageName, sensorId, mUdfpsOverlayController, isStrongBiometric,
                    statsClient);
            scheduleForSensor(sensorId, client, fingerprintStateCallback);
        });
    }

    @Override
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintStateCallback fingerprintStateCallback) {
        mHandler.post(() -> {
            final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
            final FingerprintAuthenticationClient client = new FingerprintAuthenticationClient(
                    mContext, mSensors.get(sensorId).getLazySession(), token, callback, userId,
                    operationId, restricted, opPackageName, cookie,
                    false /* requireConfirmation */, sensorId, isStrongBiometric, statsClient,
                    mTaskStackListener, mSensors.get(sensorId).getLockoutCache(),
                    mUdfpsOverlayController, allowBackgroundAuthentication);
            scheduleForSensor(sensorId, client, fingerprintStateCallback);
        });
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().startPreparedClient(cookie));
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler()
                .cancelAuthenticationOrDetection(token));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int fingerId, int userId,
            @NonNull String opPackageName) {
        scheduleRemoveSpecifiedIds(sensorId, token, new int[]{fingerId}, userId, receiver,
                opPackageName);
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token,
            @NonNull IFingerprintServiceReceiver receiver, int userId,
            @NonNull String opPackageName) {
        final List<Fingerprint> fingers = FingerprintUtils.getInstance(sensorId)
                .getBiometricsForUser(mContext, userId);
        final int[] fingerIds = new int[fingers.size()];
        for (int i = 0; i < fingers.size(); i++) {
            fingerIds[i] = fingers.get(i).getBiometricId();
        }

        scheduleRemoveSpecifiedIds(sensorId, token, fingerIds, userId, receiver, opPackageName);
    }

    private void scheduleRemoveSpecifiedIds(int sensorId, @NonNull IBinder token,
            int[] fingerprintIds, int userId, @NonNull IFingerprintServiceReceiver receiver,
            @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FingerprintRemovalClient client = new FingerprintRemovalClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), fingerprintIds, userId,
                    opPackageName, FingerprintUtils.getInstance(sensorId), sensorId,
                    mSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable BaseClientMonitor.Callback callback) {
        mHandler.post(() -> {
            final List<Fingerprint> enrolledList = getEnrolledFingerprints(sensorId, userId);
            final FingerprintInternalCleanupClient client =
                    new FingerprintInternalCleanupClient(mContext,
                            mSensors.get(sensorId).getLazySession(), userId,
                            mContext.getOpPackageName(), sensorId, enrolledList,
                            FingerprintUtils.getInstance(sensorId),
                            mSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client, callback);
        });
    }

    @Override
    public boolean isHardwareDetected(int sensorId) {
        return hasHalInstance();
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
    public void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback) {
        mHandler.post(() -> {
            final FingerprintInvalidationClient client =
                    new FingerprintInvalidationClient(mContext,
                            mSensors.get(sensorId).getLazySession(), userId, sensorId,
                            mSensors.get(sensorId).getAuthenticatorIds(), callback);
            scheduleForSensor(sensorId, client);
        });
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
        final BaseClientMonitor client =
                mSensors.get(sensorId).getScheduler().getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.e(getTag(), "onPointerDown received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerDown(x, y, minor, major);
    }

    @Override
    public void onPointerUp(int sensorId) {
        final BaseClientMonitor client =
                mSensors.get(sensorId).getScheduler().getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.e(getTag(), "onPointerUp received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onPointerUp();
    }

    @Override
    public void onUiReady(int sensorId) {
        final BaseClientMonitor client =
                mSensors.get(sensorId).getScheduler().getCurrentClient();
        if (!(client instanceof Udfps)) {
            Slog.e(getTag(), "onUiReady received during client: " + client);
            return;
        }
        final Udfps udfps = (Udfps) client;
        udfps.onUiReady();
    }

    @Override
    public void setUdfpsOverlayController(@NonNull IUdfpsOverlayController controller) {
        mUdfpsOverlayController = controller;
    }

    @Override
    public void setSidefpsController(@NonNull ISidefpsController controller) {
        mSidefpsController = controller;
    }

    @Override
    public void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer) {
        if (mSensors.contains(sensorId)) {
            mSensors.get(sensorId).dumpProtoState(sensorId, proto, clearSchedulerBuffer);
        }
    }

    @Override
    public void dumpProtoMetrics(int sensorId, @NonNull FileDescriptor fd) {

    }

    @Override
    public void dumpInternal(int sensorId, @NonNull PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(sensorId);

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", getTag());

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(mContext).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int c = FingerprintUtils.getInstance(sensorId)
                        .getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", c);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure fingerprint transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", performanceTracker.getAcceptCryptoForUser(userId));
                set.put("rejectCrypto", performanceTracker.getRejectCryptoForUser(userId));
                set.put("acquireCrypto", performanceTracker.getAcquireCryptoForUser(userId));
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(getTag(), "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());

        mSensors.get(sensorId).getScheduler().dump(pw);
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull FingerprintStateCallback fingerprintStateCallback,
            @NonNull String opPackageName) {
        return mSensors.get(sensorId).createTestSession(callback, fingerprintStateCallback);
    }

    @Override
    public void binderDied() {
        Slog.e(getTag(), "HAL died");
        mHandler.post(() -> {
            mDaemon = null;

            for (int i = 0; i < mSensors.size(); i++) {
                final Sensor sensor = mSensors.valueAt(i);
                final int sensorId = mSensors.keyAt(i);
                PerformanceTracker.getInstanceForSensorId(sensorId).incrementHALDeathCount();
                sensor.onBinderDied();
            }
        });
    }

    void setTestHalEnabled(boolean enabled) {
        mTestHalEnabled = enabled;
    }
}
