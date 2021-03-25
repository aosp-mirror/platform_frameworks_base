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

package com.android.server.biometrics.sensors.face.aidl;

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
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.SensorProps;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.NativeHandle;
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
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.InvalidationRequesterClient;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.face.FaceUtils;
import com.android.server.biometrics.sensors.face.ServiceProvider;
import com.android.server.biometrics.sensors.face.UsageStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider for a single instance of the {@link IFace} HAL.
 */
public class FaceProvider implements IBinder.DeathRecipient, ServiceProvider {
    private static final int ENROLL_TIMEOUT_SEC = 75;

    private boolean mTestHalEnabled;

    @NonNull private final Context mContext;
    @NonNull private final String mHalInstanceName;
    @NonNull @VisibleForTesting
    final SparseArray<Sensor> mSensors; // Map of sensors that this HAL supports
    @NonNull private final Handler mHandler;
    @NonNull private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull private final UsageStats mUsageStats;
    @NonNull private final ActivityTaskManager mActivityTaskManager;
    @NonNull private final BiometricTaskStackListener mTaskStackListener;

    @Nullable private IFace mDaemon;

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
                    if (Utils.isKeyguard(mContext, client.getOwnerString())) {
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
                                    .cancelAuthentication(client.getToken());
                        }
                    }
                }
            });
        }
    }

    public FaceProvider(@NonNull Context context, @NonNull SensorProps[] props,
            @NonNull String halInstanceName,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher) {
        mContext = context;
        mHalInstanceName = halInstanceName;
        mSensors = new SparseArray<>();
        mHandler = new Handler(Looper.getMainLooper());
        mUsageStats = new UsageStats(context);
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

            final FaceSensorPropertiesInternal internalProp = new FaceSensorPropertiesInternal(
                    prop.commonProps.sensorId, prop.commonProps.sensorStrength,
                    prop.commonProps.maxEnrollmentsPerUser, componentInfo, prop.sensorType,
                    false /* supportsFaceDetection */, prop.halControlsPreview,
                    false /* resetLockoutRequiresChallenge */);
            final Sensor sensor = new Sensor(getTag() + "/" + sensorId, this, mContext, mHandler,
                    internalProp);

            mSensors.put(sensorId, sensor);
            Slog.d(getTag(), "Added: " + internalProp);
        }
    }

    private String getTag() {
        return "FaceProvider/" + mHalInstanceName;
    }

    @Nullable
    @VisibleForTesting
    synchronized IFace getHalInstance() {
        if (mTestHalEnabled) {
            return new TestHal();
        }

        if (mDaemon != null) {
            return mDaemon;
        }

        Slog.d(getTag(), "Daemon was null, reconnecting");

        mDaemon = IFace.Stub.asInterface(
                ServiceManager.waitForDeclaredService(IFace.DESCRIPTOR + "/" + mHalInstanceName));
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

    private void scheduleLoadAuthenticatorIds(int sensorId) {
        for (UserInfo user : UserManager.get(mContext).getAliveUsers()) {
            scheduleLoadAuthenticatorIdsForUser(sensorId, user.id);
        }
    }

    private void scheduleLoadAuthenticatorIdsForUser(int sensorId, int userId) {
        mHandler.post(() -> {
            final FaceGetAuthenticatorIdClient client = new FaceGetAuthenticatorIdClient(
                    mContext, mSensors.get(sensorId).getLazySession(), userId,
                    mContext.getOpPackageName(), sensorId,
                    mSensors.get(sensorId).getAuthenticatorIds());

            scheduleForSensor(sensorId, client);
        });
    }

    void scheduleInvalidationRequest(int sensorId, int userId) {
        mHandler.post(() -> {
            final InvalidationRequesterClient<Face> client =
                    new InvalidationRequesterClient<>(mContext, userId, sensorId,
                            FaceUtils.getInstance(sensorId));
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public boolean containsSensor(int sensorId) {
        return mSensors.contains(sensorId);
    }

    @NonNull
    @Override
    public List<FaceSensorPropertiesInternal> getSensorProperties() {
        final List<FaceSensorPropertiesInternal> props = new ArrayList<>();
        for (int i = 0; i < mSensors.size(); ++i) {
            props.add(mSensors.valueAt(i).getSensorProperties());
        }
        return props;
    }

    @NonNull
    @Override
    public FaceSensorPropertiesInternal getSensorProperties(int sensorId) {
        return mSensors.get(sensorId).getSensorProperties();
    }

    @NonNull
    @Override
    public List<Face> getEnrolledFaces(int sensorId, int userId) {
        return FaceUtils.getInstance(sensorId).getBiometricsForUser(mContext, userId);
    }

    @Override
    public void scheduleInvalidateAuthenticatorId(int sensorId, int userId,
            @NonNull IInvalidationCallback callback) {
        mHandler.post(() -> {
            final FaceInvalidationClient client = new FaceInvalidationClient(mContext,
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
    public boolean isHardwareDetected(int sensorId) {
        return getHalInstance() != null;
    }

    @Override
    public void scheduleGenerateChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull IFaceServiceReceiver receiver, String opPackageName) {
        mHandler.post(() -> {
            final FaceGenerateChallengeClient client = new FaceGenerateChallengeClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), opPackageName, sensorId);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleRevokeChallenge(int sensorId, int userId, @NonNull IBinder token,
            @NonNull String opPackageName, long challenge) {
        mHandler.post(() -> {
            final FaceRevokeChallengeClient client = new FaceRevokeChallengeClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token, opPackageName, sensorId,
                    challenge);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleEnroll(int sensorId, @NonNull IBinder token,
            @NonNull byte[] hardwareAuthToken, int userId, @NonNull IFaceServiceReceiver receiver,
            @NonNull String opPackageName, @NonNull int[] disabledFeatures,
            @Nullable NativeHandle previewSurface, boolean debugConsent) {
        mHandler.post(() -> {
            final int maxTemplatesPerUser = mSensors.get(
                    sensorId).getSensorProperties().maxEnrollmentsPerUser;
            final FaceEnrollClient client = new FaceEnrollClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), userId, hardwareAuthToken,
                    opPackageName, FaceUtils.getInstance(sensorId), disabledFeatures,
                    ENROLL_TIMEOUT_SEC, previewSurface, sensorId, maxTemplatesPerUser,
                    debugConsent);
            scheduleForSensor(sensorId, client, new BaseClientMonitor.Callback() {
                @Override
                public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                        boolean success) {
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
    public void scheduleAuthenticate(int sensorId, @NonNull IBinder token, long operationId,
            int userId, int cookie, @NonNull ClientMonitorCallbackConverter callback,
            @NonNull String opPackageName, boolean restricted, int statsClient,
            boolean allowBackgroundAuthentication) {
        mHandler.post(() -> {
            final boolean isStrongBiometric = Utils.isStrongBiometric(sensorId);
            final FaceAuthenticationClient client = new FaceAuthenticationClient(
                    mContext, mSensors.get(sensorId).getLazySession(), token, callback, userId,
                    operationId, restricted, opPackageName, cookie,
                    false /* requireConfirmation */, sensorId, isStrongBiometric, statsClient,
                    mUsageStats, mSensors.get(sensorId).getLockoutCache(),
                    allowBackgroundAuthentication);
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void cancelAuthentication(int sensorId, @NonNull IBinder token) {
        mHandler.post(() -> mSensors.get(sensorId).getScheduler().cancelAuthentication(token));
    }

    @Override
    public void scheduleRemove(int sensorId, @NonNull IBinder token, int faceId, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        scheduleRemoveSpecifiedIds(sensorId, token, new int[] {faceId}, userId, receiver,
                opPackageName);
    }

    @Override
    public void scheduleRemoveAll(int sensorId, @NonNull IBinder token, int userId,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        final List<Face> faces = FaceUtils.getInstance(sensorId)
                .getBiometricsForUser(mContext, userId);
        final int[] faceIds = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            faceIds[i] = faces.get(i).getBiometricId();
        }

        scheduleRemoveSpecifiedIds(sensorId, token, faceIds, userId, receiver, opPackageName);
    }

    private void scheduleRemoveSpecifiedIds(int sensorId, @NonNull IBinder token, int[] faceIds,
            int userId, @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        mHandler.post(() -> {
            final FaceRemovalClient client = new FaceRemovalClient(mContext,
                    mSensors.get(sensorId).getLazySession(), token,
                    new ClientMonitorCallbackConverter(receiver), faceIds, userId,
                    opPackageName, FaceUtils.getInstance(sensorId), sensorId,
                    mSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleResetLockout(int sensorId, int userId, @NonNull byte[] hardwareAuthToken) {
        mHandler.post(() -> {
            final FaceResetLockoutClient client = new FaceResetLockoutClient(
                    mContext, mSensors.get(sensorId).getLazySession(), userId,
                    mContext.getOpPackageName(), sensorId, hardwareAuthToken,
                    mSensors.get(sensorId).getLockoutCache(), mLockoutResetDispatcher);

            scheduleForSensor(sensorId, client);
        });
    }

    @Override
    public void scheduleSetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            boolean enabled, @NonNull byte[] hardwareAuthToken,
            @NonNull IFaceServiceReceiver receiver, @NonNull String opPackageName) {
        // TODO(b/171335732): implement this.
    }

    @Override
    public void scheduleGetFeature(int sensorId, @NonNull IBinder token, int userId, int feature,
            @NonNull ClientMonitorCallbackConverter callback, @NonNull String opPackageName) {
        // TODO(b/171335732): implement this.
    }

    @Override
    public void startPreparedClient(int sensorId, int cookie) {
        mHandler.post(() -> {
            mSensors.get(sensorId).getScheduler().startPreparedClient(cookie);
        });
    }

    @Override
    public void scheduleInternalCleanup(int sensorId, int userId,
            @Nullable BaseClientMonitor.Callback callback) {
        mHandler.post(() -> {
            final List<Face> enrolledList = getEnrolledFaces(sensorId, userId);
            final FaceInternalCleanupClient client =
                    new FaceInternalCleanupClient(mContext,
                            mSensors.get(sensorId).getLazySession(), userId,
                            mContext.getOpPackageName(), sensorId, enrolledList,
                            FaceUtils.getInstance(sensorId),
                            mSensors.get(sensorId).getAuthenticatorIds());
            scheduleForSensor(sensorId, client);
        });
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
                final int c = FaceUtils.getInstance(sensorId)
                        .getBiometricsForUser(mContext, userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", c);
                set.put("accept", performanceTracker.getAcceptForUser(userId));
                set.put("reject", performanceTracker.getRejectForUser(userId));
                set.put("acquire", performanceTracker.getAcquireForUser(userId));
                set.put("lockout", performanceTracker.getTimedLockoutForUser(userId));
                set.put("permanentLockout", performanceTracker.getPermanentLockoutForUser(userId));
                // cryptoStats measures statistics about secure face transactions
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
        mUsageStats.print(pw);
    }

    @NonNull
    @Override
    public ITestSession createTestSession(int sensorId, @NonNull ITestSessionCallback callback,
            @NonNull String opPackageName) {
        return mSensors.get(sensorId).createTestSession(callback);
    }

    @Override
    public void dumpHal(int sensorId, @NonNull FileDescriptor fd, @NonNull String[] args) {}

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
