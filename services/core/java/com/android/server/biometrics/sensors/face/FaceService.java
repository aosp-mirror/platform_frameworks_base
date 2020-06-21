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
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricServiceBase;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.PerformanceTracker;
import com.android.server.biometrics.sensors.RemovalClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face-related events.
 *
 * @hide
 */
public class FaceService extends BiometricServiceBase {

    protected static final String TAG = "FaceService";
    private static final boolean DEBUG = true;
    private static final String FACE_DATA_DIR = "facedata";
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.biometrics.face.ACTION_LOCKOUT_RESET";
    private static final int CHALLENGE_TIMEOUT_SEC = 600; // 10 minutes

    static final String NOTIFICATION_TAG = "FaceService";
    static final int NOTIFICATION_ID = 1;

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    private final class FaceServiceWrapper extends IFaceService.Stub {
        private static final int ENROLL_TIMEOUT_SEC = 75;

        /**
         * The following methods contain common code which is shared in biometrics/common.
         */

        @Override // Binder call
        public long generateChallenge(IBinder token) {
            checkPermission(MANAGE_BIOMETRIC);
            return startGenerateChallenge(token);
        }

        @Override // Binder call
        public int revokeChallenge(IBinder token) {
            checkPermission(MANAGE_BIOMETRIC);
            mHandler.post(() -> {
                // TODO(b/137106905): Schedule binder calls in FaceService to avoid deadlocks.
                if (getCurrentClient() == null) {
                    // if we aren't handling any other HIDL calls (mCurrentClient == null), revoke
                    // the challenge right away.
                    startRevokeChallenge(token);
                } else {
                    // postpone revoking the challenge until we finish processing the current HIDL
                    // call.
                    mRevokeChallengePending = true;
                }
            });
            return Status.OK;
        }

        @Override // Binder call
        public void enroll(int userId, final IBinder token, final byte[] cryptoToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures, Surface surface) {
            checkPermission(MANAGE_BIOMETRIC);
            updateActiveGroup(userId, opPackageName);

            mHandler.post(() -> {
                mNotificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID,
                        UserHandle.CURRENT);
            });

            final boolean restricted = isRestricted();
            final EnrollClient client = new FaceEnrollClient(getContext(),
                    mDaemonWrapper, token, new ClientMonitorCallbackConverter(receiver),
                    mCurrentUserId, 0 /* groupId */, cryptoToken, restricted, opPackageName,
                    getBiometricUtils(), disabledFeatures, ENROLL_TIMEOUT_SEC, statsModality(),
                    surface, getSensorId());

            enrollInternal(client, mCurrentUserId);
        }

        @Override // Binder call
        public void enrollRemotely(int userId, final IBinder token, final byte[] cryptoToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            checkPermission(MANAGE_BIOMETRIC);
            // TODO(b/145027036): Implement this.
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_BIOMETRIC);
            cancelEnrollmentInternal(token);
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long opId, int userId,
                final IFaceServiceReceiver receiver, final int flags,
                final String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            updateActiveGroup(userId, opPackageName);
            final boolean restricted = isRestricted();
            final int statsClient = isKeyguard(opPackageName) ? BiometricsProtoEnums.CLIENT_KEYGUARD
                    : BiometricsProtoEnums.CLIENT_UNKNOWN;
            final AuthenticationClient client = new FaceAuthenticationClient(getContext(),
                    mDaemonWrapper, token,
                    new ClientMonitorCallbackConverter(receiver),
                    mCurrentUserId, opId, restricted, opPackageName,
                    0 /* cookie */, false /* requireConfirmation */, getSensorId(),
                    isStrongBiometric(), statsClient,
                    mTaskStackListener, mLockoutTracker, mUsageStats);
            authenticateInternal(client, opPackageName);
        }

        @Override // Binder call
        public void prepareForAuthentication(boolean requireConfirmation, IBinder token, long opId,
                int groupId, IBiometricSensorReceiver sensorReceiver,
                String opPackageName, int cookie, int callingUid, int callingPid,
                int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            updateActiveGroup(groupId, opPackageName);
            final boolean restricted = true; // BiometricPrompt is always restricted
            final AuthenticationClient client = new FaceAuthenticationClient(getContext(),
                    mDaemonWrapper, token,
                    new ClientMonitorCallbackConverter(sensorReceiver),
                    mCurrentUserId, opId, restricted, opPackageName, cookie,
                    requireConfirmation, getSensorId(), isStrongBiometric(),
                    BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT,
                    mTaskStackListener, mLockoutTracker, mUsageStats);
            authenticateInternal(client, opPackageName, callingUid, callingPid,
                    callingUserId);
        }

        @Override // Binder call
        public void startPreparedClient(int cookie) {
            checkPermission(MANAGE_BIOMETRIC);
            startCurrentClient(cookie);
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            cancelAuthenticationInternal(token, opPackageName);
        }

        @Override // Binder call
        public void cancelAuthenticationFromService(final IBinder token, final String opPackageName,
                int callingUid, int callingPid, int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            // Cancellation is from system server in this case.
            cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid,
                    callingUserId, false /* fromClient */);
        }

        @Override // Binder call
        public void remove(final IBinder token, final int faceId, final int userId,
                final IFaceServiceReceiver receiver, final String opPackageName) {
            checkPermission(MANAGE_BIOMETRIC);
            updateActiveGroup(userId, opPackageName);

            if (token == null) {
                Slog.w(TAG, "remove(): token is null");
                return;
            }

            final boolean restricted = isRestricted();
            final RemovalClient client = new RemovalClient(getContext(),
                    mDaemonWrapper, token, new ClientMonitorCallbackConverter(receiver), faceId,
                    0 /* groupId */, userId, restricted, token.toString(), getBiometricUtils(),
                    getSensorId(), statsModality());
            removeInternal(client);
        }

        @Override
        public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback)
                throws RemoteException {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            FaceService.super.addLockoutResetCallback(callback);
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                if (args.length > 1 && "--hal".equals(args[0])) {
                    dumpHal(fd, Arrays.copyOfRange(args, 1, args.length, args.getClass()));
                } else {
                    dumpInternal(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * The following methods don't use any common code from BiometricService
         */

        // TODO: refactor out common code here
        @Override // Binder call
        public boolean isHardwareDetected(String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                IBiometricsFace daemon = getFaceDaemon();
                return daemon != null;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public List<Face> getEnrolledFaces(int userId, String opPackageName) {
            checkPermission(MANAGE_BIOMETRIC);
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return null;
            }

            return FaceService.this.getEnrolledTemplates(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFaces(int userId, String opPackageName) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            if (!canUseBiometric(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            return FaceService.this.hasEnrolledBiometrics(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            return FaceService.this.getAuthenticatorId(callingUserId);
        }

        @Override // Binder call
        public void resetLockout(int userId, byte[] hardwareAuthToken) {
            checkPermission(MANAGE_BIOMETRIC);
            mHandler.post(() -> {
                if (!FaceService.this.hasEnrolledBiometrics(userId)) {
                    Slog.w(TAG, "Ignoring lockout reset, no templates enrolled");
                    return;
                }

                Slog.d(TAG, "Resetting lockout for user: " + userId);

                updateActiveGroup(userId, null /* opPackageName */);
                try {
                    mDaemonWrapper.resetLockout(hardwareAuthToken);
                } catch (RemoteException e) {
                    Slog.e(getTag(), "Unable to reset lockout", e);
                }
            });
        }

        @Override
        public void setFeature(int userId, int feature, boolean enabled, final byte[] token,
                IFaceServiceReceiver receiver, final String opPackageName) {
            checkPermission(MANAGE_BIOMETRIC);

            mHandler.post(() -> {
                if (DEBUG) {
                    Slog.d(TAG, "setFeature for user(" + userId + ")");
                }
                updateActiveGroup(userId, opPackageName);
                if (!FaceService.this.hasEnrolledBiometrics(mCurrentUserId)) {
                    Slog.e(TAG, "No enrolled biometrics while setting feature: " + feature);
                    return;
                }

                final ArrayList<Byte> byteToken = new ArrayList<>();
                for (int i = 0; i < token.length; i++) {
                    byteToken.add(token[i]);
                }

                // TODO: Support multiple faces
                final int faceId = getFirstTemplateForUser(mCurrentUserId);

                if (mDaemon != null) {
                    try {
                        final int result = mDaemon.setFeature(feature, enabled, byteToken, faceId);
                        receiver.onFeatureSet(result == Status.OK, feature);
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Unable to set feature: " + feature
                                        + " to enabled:" + enabled, e);
                    }
                }
            });

        }

        @Override
        public void getFeature(int userId, int feature, IFaceServiceReceiver receiver,
                final String opPackageName) {
            checkPermission(MANAGE_BIOMETRIC);

            mHandler.post(() -> {
                if (DEBUG) {
                    Slog.d(TAG, "getFeature for user(" + userId + ")");
                }
                updateActiveGroup(userId, opPackageName);
                // This should ideally return tri-state, but the user isn't shown settings unless
                // they are enrolled so it's fine for now.
                if (!FaceService.this.hasEnrolledBiometrics(mCurrentUserId)) {
                    Slog.e(TAG, "No enrolled biometrics while getting feature: " + feature);
                    return;
                }

                // TODO: Support multiple faces
                final int faceId = getFirstTemplateForUser(mCurrentUserId);

                if (mDaemon != null) {
                    try {
                        OptionalBool result = mDaemon.getFeature(feature, faceId);
                        receiver.onFeatureGet(result.status == Status.OK, feature, result.value);
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Unable to getRequireAttention", e);
                    }
                }
            });

        }

        @Override
        public void userActivity() {
            checkPermission(MANAGE_BIOMETRIC);

            if (mDaemon != null) {
                try {
                    mDaemon.userActivity();
                } catch (RemoteException e) {
                    Slog.e(getTag(), "Unable to send userActivity", e);
                }
            }
        }

        // TODO: Support multiple faces
        private int getFirstTemplateForUser(int user) {
            final List<Face> faces = FaceService.this.getEnrolledTemplates(user);
            if (!faces.isEmpty()) {
                return faces.get(0).getBiometricId();
            }
            return 0;
        }

        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            initializeConfigurationInternal(sensorId);
        }
    }

    private final LockoutHalImpl mLockoutTracker;

    @GuardedBy("this")
    private IBiometricsFace mDaemon;
    private UsageStats mUsageStats;
    private boolean mRevokeChallengePending = false;

    private NotificationManager mNotificationManager;

    /**
     * Receives callbacks from the HAL.
     */
    private IBiometricsFaceClientCallback mDaemonCallback =
            new IBiometricsFaceClientCallback.Stub() {
        @Override
        public void onEnrollResult(final long deviceId, int faceId, int userId,
                int remaining) {
            mHandler.post(() -> {
                final Face face = new Face(getBiometricUtils()
                        .getUniqueName(getContext(), userId), faceId, deviceId);
                FaceService.super.handleEnrollResult(face, remaining);

                // Enrollment changes the authenticatorId, so update it here.
                IBiometricsFace daemon = getFaceDaemon();
                if (remaining == 0 && daemon != null) {
                    try {
                        mAuthenticatorIds.put(userId,
                                hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId().value
                                        : 0L);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to get authenticatorId", e);
                    }
                }
            });
        }

        @Override
        public void onAcquired(final long deviceId, final int userId, final int acquiredInfo,
                final int vendorCode) {
            mHandler.post(() -> {
                FaceService.super.handleAcquired(acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int faceId, final int userId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                Face face = new Face("", faceId, deviceId);
                FaceService.super.handleAuthenticated(face, token);
            });
        }

        @Override
        public void onError(final long deviceId, final int userId, final int error,
                final int vendorCode) {
            mHandler.post(() -> {
                FaceService.super.handleError(error, vendorCode);

                // TODO: this chunk of code should be common to all biometric services
                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    // If we get HW_UNAVAILABLE, try to connect again later...
                    Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                    synchronized (this) {
                        mDaemon = null;
                        mCurrentUserId = UserHandle.USER_NULL;
                    }
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, ArrayList<Integer> faceIds, final int userId) {
            mHandler.post(() -> {
                if (!faceIds.isEmpty()) {
                    for (int i = 0; i < faceIds.size(); i++) {
                        final Face face = new Face("", faceIds.get(i), deviceId);
                        // Convert to old behavior
                        FaceService.super.handleRemoved(face, faceIds.size() - i - 1);
                    }
                } else {
                    final Face face = new Face("", 0 /* identifier */, deviceId);
                    FaceService.super.handleRemoved(face, 0 /* remaining */);
                }
                Settings.Secure.putIntForUser(getContext().getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_RE_ENROLL, 0, UserHandle.USER_CURRENT);
            });
        }

        @Override
        public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId)
                throws RemoteException {
            mHandler.post(() -> {
                if (!faceIds.isEmpty()) {
                    for (int i = 0; i < faceIds.size(); i++) {
                        final Face face = new Face("", faceIds.get(i), deviceId);
                        // Convert to old old behavior
                        FaceService.super.handleEnumerate(face, faceIds.size() - i - 1);
                    }
                } else {
                    // For face, the HIDL contract is to receive an empty list when there are no
                    // templates enrolled. Send a null identifier since we don't consume them
                    // anywhere, and send remaining == 0 to plumb this with existing common code.
                    FaceService.super.handleEnumerate(null /* identifier */, 0);
                }
            });
        }

        @Override
        public void onLockoutChanged(long duration) {
            Slog.d(TAG, "onLockoutChanged: " + duration);

            final @LockoutTracker.LockoutMode int lockoutMode;
            if (duration == 0) {
                lockoutMode = LockoutTracker.LOCKOUT_NONE;
            } else if (duration == -1 || duration == Long.MAX_VALUE) {
                lockoutMode = LockoutTracker.LOCKOUT_PERMANENT;
            } else {
                lockoutMode = LockoutTracker.LOCKOUT_TIMED;
            }
            mLockoutTracker.setCurrentUserLockoutMode(lockoutMode);

            mHandler.post(() -> {
                if (duration == 0) {
                    notifyLockoutResetMonitors();
                }
            });
        }
    };

    /**
     * Wraps the HAL-specific code and is passed to the ClientMonitor implementations so that they
     * can be shared between the multiple biometric services.
     */
    private final DaemonWrapper mDaemonWrapper = new DaemonWrapper() {
        @Override
        public int authenticate(long operationId, int groupId, Surface surface)
                throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "authenticate(): no face HAL!");
                return ERROR_ESRCH;
            }
            return daemon.authenticate(operationId);
        }

        @Override
        public int cancel() throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "cancel(): no face HAL!");
                return ERROR_ESRCH;
            }
            return daemon.cancel();
        }

        @Override
        public int remove(int groupId, int biometricId) throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "remove(): no face HAL!");
                return ERROR_ESRCH;
            }
            return daemon.remove(biometricId);
        }

        @Override
        public int enumerate() throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "enumerate(): no face HAL!");
                return ERROR_ESRCH;
            }
            return daemon.enumerate();
        }

        @Override
        public int enroll(byte[] cryptoToken, int groupId, int timeout,
                ArrayList<Integer> disabledFeatures, Surface surface) throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "enroll(): no face HAL!");
                return ERROR_ESRCH;
            }
            final ArrayList<Byte> token = new ArrayList<>();
            for (int i = 0; i < cryptoToken.length; i++) {
                token.add(cryptoToken[i]);
            }
            android.hardware.biometrics.face.V1_1.IBiometricsFace daemon11 =
                    android.hardware.biometrics.face.V1_1.IBiometricsFace.castFrom(
                            daemon);
            if (daemon11 != null) {
                return daemon11.enroll_1_1(token, timeout, disabledFeatures,
                        convertSurfaceToNativeHandle(surface));
            } else if (surface == null) {
                return daemon.enroll(token, timeout, disabledFeatures);
            } else {
                Slog.e(TAG, "enroll(): surface is only supported in @1.1 HAL");
                return ERROR_ESRCH;
            }
        }

        @Override
        public void resetLockout(byte[] cryptoToken) throws RemoteException {
            IBiometricsFace daemon = getFaceDaemon();
            if (daemon == null) {
                Slog.w(TAG, "resetLockout(): no face HAL!");
                return;
            }
            final ArrayList<Byte> token = new ArrayList<>();
            for (int i = 0; i < cryptoToken.length; i++) {
                token.add(cryptoToken[i]);
            }
            daemon.resetLockout(token);
        }
    };


    public FaceService(Context context) {
        super(context);
        mLockoutTracker = new LockoutHalImpl();
        mUsageStats = new UsageStats(context);
        mNotificationManager = getContext().getSystemService(NotificationManager.class);
    }

    @Override
    protected void removeClient(ClientMonitor client) {
        super.removeClient(client);
        if (mRevokeChallengePending) {
            startRevokeChallenge(null);
            mRevokeChallengePending = false;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        publishBinderService(Context.FACE_SERVICE, new FaceServiceWrapper());
        // Get the face daemon on FaceService's on thread so SystemServerInitThreadPool isn't
        // blocked
        SystemServerInitThreadPool.submit(() -> mHandler.post(this::getFaceDaemon),
                TAG + ".onStart");
    }

    @Override
    public String getTag() {
        return TAG;
    }

    @Override
    protected DaemonWrapper getDaemonWrapper() {
        return mDaemonWrapper;
    }

    @Override
    protected BiometricUtils getBiometricUtils() {
        return FaceUtils.getInstance();
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_faceMaxTemplatesPerUser);
        final int enrolled = FaceService.this.getEnrolledTemplates(userId).size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many faces registered, user: " + userId);
            return true;
        }
        return false;
    }

    @Override
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        mDaemon = null;

        mCurrentUserId = UserHandle.USER_NULL; // Force updateActiveGroup() to re-evaluate
    }

    @Override
    protected void updateActiveGroup(int userId, String clientPackage) {
        IBiometricsFace daemon = getFaceDaemon();

        if (daemon != null) {
            try {
                userId = getUserOrWorkProfileId(clientPackage, userId);
                if (userId != mCurrentUserId) {
                    final File baseDir = Environment.getDataVendorDeDirectory(userId);
                    final File faceDir = new File(baseDir, FACE_DATA_DIR);
                    if (!faceDir.exists()) {
                        if (!faceDir.mkdir()) {
                            Slog.v(TAG, "Cannot make directory: " + faceDir.getAbsolutePath());
                            return;
                        }
                        // Calling mkdir() from this process will create a directory with our
                        // permissions (inherited from the containing dir). This command fixes
                        // the label.
                        if (!SELinux.restorecon(faceDir)) {
                            Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }

                    daemon.setActiveUser(userId, faceDir.getAbsolutePath());
                    mCurrentUserId = userId;
                    mAuthenticatorIds.put(userId,
                            hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId().value : 0L);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveUser():", e);
            }
        }
    }

    @Override
    protected void handleUserSwitching(int userId) {
        super.handleUserSwitching(userId);
        // Will be updated when we get the callback from HAL
        mLockoutTracker.setCurrentUserLockoutMode(LockoutTracker.LOCKOUT_NONE);
    }

    @Override
    protected boolean hasEnrolledBiometrics(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId).size() > 0;
    }

    @Override
    protected String getManageBiometricPermission() {
        return MANAGE_BIOMETRIC;
    }

    @Override
    protected void checkUseBiometricPermission() {
        // noop for Face. The permission checks are all done on the incoming binder call.
    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
        return mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected List<Face> getEnrolledTemplates(int userId) {
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
    }

    @Override
    protected void notifyClientActiveCallbacks(boolean isActive) {
        // noop for Face.
    }

    @Override
    protected int statsModality() {
        return BiometricsProtoEnums.MODALITY_FACE;
    }

    @Override
    protected @LockoutTracker.LockoutMode int getLockoutMode(int userId) {
        return mLockoutTracker.getLockoutModeForUser(userId);
    }

    /** Gets the face daemon */
    private synchronized IBiometricsFace getFaceDaemon() {
        if (mDaemon == null) {
            Slog.v(TAG, "mDaemon was null, reconnect to face");
            try {
                mDaemon = IBiometricsFace.getService();
            } catch (java.util.NoSuchElementException e) {
                // Service doesn't exist or cannot be opened. Logged below.
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get biometric interface", e);
            }
            if (mDaemon == null) {
                Slog.w(TAG, "face HIDL not available");
                return null;
            }

            mDaemon.asBinder().linkToDeath(this, 0);

            long halId = 0;
            try {
                halId = mDaemon.setCallback(mDaemonCallback).value;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open face HAL", e);
                mDaemon = null; // try again later!
            }

            if (DEBUG) Slog.v(TAG, "Face HAL id: " + halId);
            if (halId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
                doTemplateCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w(TAG, "Failed to open Face HAL!");
                MetricsLogger.count(getContext(), "faced_openhal_error", 1);
                mDaemon = null;
            }
        }
        return mDaemon;
    }

    private long startGenerateChallenge(IBinder token) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startGenerateChallenge: no face HAL!");
            return 0;
        }
        try {
            return daemon.generateChallenge(CHALLENGE_TIMEOUT_SEC).value;
        } catch (RemoteException e) {
            Slog.e(TAG, "startGenerateChallenge failed", e);
        }
        return 0;
    }

    private int startRevokeChallenge(IBinder token) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startRevokeChallenge: no face HAL!");
            return 0;
        }
        try {
            final int res = daemon.revokeChallenge();
            if (res != Status.OK) {
                Slog.e(TAG, "revokeChallenge returned " + res);
            }
            return res;
        } catch (RemoteException e) {
            Slog.e(TAG, "startRevokeChallenge failed", e);
        }
        return 0;
    }

    private native NativeHandle convertSurfaceToNativeHandle(Surface surface);

    private void dumpInternal(PrintWriter pw) {
        PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(getSensorId());

        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = getBiometricUtils().getBiometricsForUser(getContext(), userId).size();
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
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
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + performanceTracker.getHALDeathCount());

        mUsageStats.print(pw);
    }

    private void dumpHal(FileDescriptor fd, String[] args) {
        // WARNING: CDD restricts image data from leaving TEE unencrypted on
        //          production devices:
        // [C-1-10] MUST not allow unencrypted access to identifiable biometric
        //          data or any data derived from it (such as embeddings) to the
        //         Application Processor outside the context of the TEE.
        //  As such, this API should only be enabled for testing purposes on
        //  engineering and userdebug builds.  All modules in the software stack
        //  MUST enforce final build products do NOT have this functionality.
        //  Additionally, the following check MUST NOT be removed.
        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return;
        }

        // Additionally, this flag allows turning off face for a device
        // (either permanently through the build or on an individual device).
        if (SystemProperties.getBoolean("ro.face.disable_debug_data", false)
                || SystemProperties.getBoolean("persist.face.disable_debug_data", false)) {
            return;
        }

        // The debug method takes two file descriptors. The first is for text
        // output, which we will drop.  The second is for binary data, which
        // will be the protobuf data.
        final IBiometricsFace daemon = getFaceDaemon();
        if (daemon != null) {
            FileOutputStream devnull = null;
            try {
                devnull = new FileOutputStream("/dev/null");
                final NativeHandle handle = new NativeHandle(
                        new FileDescriptor[] { devnull.getFD(), fd },
                        new int[0], false);
                daemon.debug(handle, new ArrayList<String>(Arrays.asList(args)));
            } catch (IOException | RemoteException ex) {
                Slog.d(TAG, "error while reading face debugging data", ex);
            } finally {
                if (devnull != null) {
                    try {
                        devnull.close();
                    } catch (IOException ex) {
                    }
                }
            }
        }
    }
}
