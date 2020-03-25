/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.biometrics.face;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.RESET_FACE_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
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

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.AuthenticationClient;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.ClientMonitor;
import com.android.server.biometrics.Constants;
import com.android.server.biometrics.EnumerateClient;
import com.android.server.biometrics.RemovalClient;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final String NOTIFICATION_TAG = "FaceService";
    private static final int NOTIFICATION_ID = 1;

    private static final String SKIP_KEYGUARD_ACQUIRE_IGNORE_LIST =
            "com.android.server.biometrics.face.skip_keyguard_acquire_ignore_list";

    /**
     * Events for bugreports.
     */
    public static final class AuthenticationEvent {
        private long mStartTime;
        private long mLatency;
        // Only valid if mError is 0
        private boolean mAuthenticated;
        private int mError;
        // Only valid if mError is ERROR_VENDOR
        private int mVendorError;
        private int mUser;

        AuthenticationEvent(long startTime, long latency, boolean authenticated, int error,
                int vendorError, int user) {
            mStartTime = startTime;
            mLatency = latency;
            mAuthenticated = authenticated;
            mError = error;
            mVendorError = vendorError;
            mUser = user;
        }

        public String toString(Context context) {
            return "Start: " + mStartTime
                    + "\tLatency: " + mLatency
                    + "\tAuthenticated: " + mAuthenticated
                    + "\tError: " + mError
                    + "\tVendorCode: " + mVendorError
                    + "\tUser: " + mUser
                    + "\t" + FaceManager.getErrorString(context, mError, mVendorError);
        }
    }

    /**
     * Keep a short historical buffer of stats, with an aggregated usage time.
     */
    private class UsageStats {
        static final int EVENT_LOG_SIZE = 100;

        Context mContext;
        List<AuthenticationEvent> mAuthenticationEvents;

        int acceptCount;
        int rejectCount;
        Map<Integer, Integer> mErrorCount;

        long acceptLatency;
        long rejectLatency;
        Map<Integer, Long> mErrorLatency;

        UsageStats(Context context) {
            mAuthenticationEvents = new ArrayList<>();
            mErrorCount = new HashMap<>();
            mErrorLatency = new HashMap<>();
            mContext = context;
        }

        void addEvent(AuthenticationEvent event) {
            if (mAuthenticationEvents.size() >= EVENT_LOG_SIZE) {
                mAuthenticationEvents.remove(0);
            }
            mAuthenticationEvents.add(event);

            if (event.mAuthenticated) {
                acceptCount++;
                acceptLatency += event.mLatency;
            } else if (event.mError == 0) {
                rejectCount++;
                rejectLatency += event.mLatency;
            } else {
                mErrorCount.put(event.mError, mErrorCount.getOrDefault(event.mError, 0) + 1);
                mErrorLatency.put(event.mError,
                        mErrorLatency.getOrDefault(event.mError, 0l) + event.mLatency);
            }
        }

        void print(PrintWriter pw) {
            pw.println("Events since last reboot: " + mAuthenticationEvents.size());
            for (int i = 0; i < mAuthenticationEvents.size(); i++) {
                pw.println(mAuthenticationEvents.get(i).toString(mContext));
            }

            // Dump aggregated usage stats
            // TODO: Remove or combine with json dump in a future release
            pw.println("Accept\tCount: " + acceptCount + "\tLatency: " + acceptLatency
                    + "\tAverage: " + (acceptCount > 0 ? acceptLatency / acceptCount : 0));
            pw.println("Reject\tCount: " + rejectCount + "\tLatency: " + rejectLatency
                    + "\tAverage: " + (rejectCount > 0 ? rejectLatency / rejectCount : 0));

            for (Integer key : mErrorCount.keySet()) {
                final int count = mErrorCount.get(key);
                pw.println("Error" + key + "\tCount: " + count
                        + "\tLatency: " + mErrorLatency.getOrDefault(key, 0l)
                        + "\tAverage: " + (count > 0 ? mErrorLatency.getOrDefault(key, 0l) / count
                        : 0)
                        + "\t" + FaceManager.getErrorString(mContext, key, 0 /* vendorCode */));
            }
        }
    }

    private final class FaceAuthClient extends AuthenticationClientImpl {
        private int mLastAcquire;

        public FaceAuthClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int targetUserId, int groupId, long opId,
                boolean restricted, String owner, int cookie, boolean requireConfirmation,
                Surface surface) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId,
                    restricted, owner, cookie, requireConfirmation, surface);
        }

        @Override
        protected int statsModality() {
            return FaceService.this.statsModality();
        }

        @Override
        public boolean shouldFrameworkHandleLockout() {
            return false;
        }

        @Override
        public boolean wasUserDetected() {
            return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED
                    && mLastAcquire != FaceManager.FACE_ACQUIRED_SENSOR_DIRTY;
        }

        @Override
        public boolean isStrongBiometric() {
            return FaceService.this.isStrongBiometric();
        }

        @Override
        public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
                boolean authenticated, ArrayList<Byte> token) {
            final boolean result = super.onAuthenticated(identifier, authenticated, token);

            mUsageStats.addEvent(new AuthenticationEvent(
                    getStartTimeMs(),
                    System.currentTimeMillis() - getStartTimeMs() /* latency */,
                    authenticated,
                    0 /* error */,
                    0 /* vendorError */,
                    getTargetUserId()));

            // For face, the authentication lifecycle ends either when
            // 1) Authenticated == true
            // 2) Error occurred
            // 3) Authenticated == false
            // Fingerprint currently does not end when the third condition is met which is a bug,
            // but let's leave it as-is for now.
            return result || !authenticated;
        }

        @Override
        public boolean onError(long deviceId, int error, int vendorCode) {
            mUsageStats.addEvent(new AuthenticationEvent(
                    getStartTimeMs(),
                    System.currentTimeMillis() - getStartTimeMs() /* latency */,
                    false /* authenticated */,
                    error,
                    vendorCode,
                    getTargetUserId()));

            return super.onError(deviceId, error, vendorCode);
        }

        @Override
        public int[] getAcquireIgnorelist() {
            if (isBiometricPrompt()) {
                return mBiometricPromptIgnoreList;
            } else {
                // Keyguard
                return mKeyguardIgnoreList;
            }
        }

        @Override
        public int[] getAcquireVendorIgnorelist() {
            if (isBiometricPrompt()) {
                return mBiometricPromptIgnoreListVendor;
            } else {
                // Keyguard
                return mKeyguardIgnoreListVendor;
            }
        }

        @Override
        public boolean onAcquired(int acquireInfo, int vendorCode) {

            mLastAcquire = acquireInfo;

            if (acquireInfo == FaceManager.FACE_ACQUIRED_RECALIBRATE) {
                final String name =
                        getContext().getString(R.string.face_recalibrate_notification_name);
                final String title =
                        getContext().getString(R.string.face_recalibrate_notification_title);
                final String content =
                        getContext().getString(R.string.face_recalibrate_notification_content);

                final Intent intent = new Intent("android.settings.FACE_SETTINGS");
                intent.setPackage("com.android.settings");

                final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(getContext(),
                        0 /* requestCode */, intent, 0 /* flags */, null /* options */,
                        UserHandle.CURRENT);

                final String channelName = "FaceEnrollNotificationChannel";

                NotificationChannel channel = new NotificationChannel(channelName, name,
                        NotificationManager.IMPORTANCE_HIGH);
                Notification notification = new Notification.Builder(getContext(), channelName)
                        .setSmallIcon(R.drawable.ic_lock)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setSubText(name)
                        .setOnlyAlertOnce(true)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setContentIntent(pendingIntent)
                        .setVisibility(Notification.VISIBILITY_SECRET)
                        .build();

                mNotificationManager.createNotificationChannel(channel);
                mNotificationManager.notifyAsUser(NOTIFICATION_TAG, NOTIFICATION_ID, notification,
                        UserHandle.CURRENT);
            }

            return super.onAcquired(acquireInfo, vendorCode);
        }
    }

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

            mNotificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID,
                    UserHandle.CURRENT);

            final boolean restricted = isRestricted();
            final EnrollClientImpl client = new EnrollClientImpl(getContext(), mDaemonWrapper,
                    mHalDeviceId, token, new ServiceListenerImpl(receiver), mCurrentUserId,
                    0 /* groupId */, cryptoToken, restricted, opPackageName, disabledFeatures,
                    ENROLL_TIMEOUT_SEC, surface) {

                @Override
                public int[] getAcquireIgnorelist() {
                    return mEnrollIgnoreList;
                }

                @Override
                public int[] getAcquireVendorIgnorelist() {
                    return mEnrollIgnoreListVendor;
                }

                @Override
                public boolean shouldVibrate() {
                    return false;
                }

                @Override
                protected int statsModality() {
                    return FaceService.this.statsModality();
                }
            };

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
            final AuthenticationClientImpl client = new FaceAuthClient(getContext(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver),
                    mCurrentUserId, 0 /* groupId */, opId, restricted, opPackageName,
                    0 /* cookie */, false /* requireConfirmation */, null /* surface */);
            authenticateInternal(client, opId, opPackageName);
        }

        @Override // Binder call
        public void prepareForAuthentication(boolean requireConfirmation, IBinder token, long opId,
                int groupId, IBiometricServiceReceiverInternal wrapperReceiver,
                String opPackageName, int cookie, int callingUid, int callingPid,
                int callingUserId) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            updateActiveGroup(groupId, opPackageName);
            final boolean restricted = true; // BiometricPrompt is always restricted
            final AuthenticationClientImpl client = new FaceAuthClient(getContext(),
                    mDaemonWrapper, mHalDeviceId, token,
                    new BiometricPromptServiceListenerImpl(wrapperReceiver),
                    mCurrentUserId, 0 /* groupId */, opId, restricted, opPackageName, cookie,
                    requireConfirmation, null /* surface */);
            authenticateInternal(client, opId, opPackageName, callingUid, callingPid,
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
                int callingUid, int callingPid, int callingUserId, boolean fromClient) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid,
                    callingUserId, fromClient);
        }

        @Override // Binder call
        public void setActiveUser(final int userId) {
            checkPermission(MANAGE_BIOMETRIC);
            setActiveUserInternal(userId);
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
            final RemovalClient client = new RemovalClient(getContext(), getConstants(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver), faceId,
                    0 /* groupId */, userId, restricted, token.toString(), getBiometricUtils()) {
                @Override
                protected int statsModality() {
                    return FaceService.this.statsModality();
                }
            };
            removeInternal(client);
        }

        @Override
        public void enumerate(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_BIOMETRIC);

            final boolean restricted = isRestricted();
            final EnumerateClient client = new EnumerateClient(getContext(), getConstants(),
                    mDaemonWrapper, mHalDeviceId, token, new ServiceListenerImpl(receiver), userId,
                    userId, restricted, getContext().getOpPackageName()) {
                @Override
                protected int statsModality() {
                    return FaceService.this.statsModality();
                }
            };
            enumerateInternal(client);
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
                return daemon != null && mHalDeviceId != 0;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void rename(final int faceId, final String name) {
            checkPermission(MANAGE_BIOMETRIC);
            if (!isCurrentUserOrProfile(UserHandle.getCallingUserId())) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    getBiometricUtils().renameBiometricForUser(getContext(), mCurrentUserId,
                            faceId, name);
                }
            });
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
        public long getAuthenticatorId() {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            return FaceService.this.getAuthenticatorId();
        }

        @Override // Binder call
        public void resetLockout(byte[] token) {
            checkPermission(MANAGE_BIOMETRIC);

            mHandler.post(() -> {
                if (!FaceService.this.hasEnrolledBiometrics(mCurrentUserId)) {
                    Slog.w(TAG, "Ignoring lockout reset, no templates enrolled");
                    return;
                }

                Slog.d(TAG, "Resetting lockout for user: " + mCurrentUserId);

                try {
                    mDaemonWrapper.resetLockout(token);
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
        public void initConfiguredStrength(int strength) {
            checkPermission(USE_BIOMETRIC_INTERNAL);
            initConfiguredStrengthInternal(strength);
        }
    }

    /**
     * Receives callbacks from the ClientMonitor implementations. The results are forwarded to
     * BiometricPrompt.
     */
    private class BiometricPromptServiceListenerImpl extends BiometricServiceListener {
        BiometricPromptServiceListenerImpl(IBiometricServiceReceiverInternal wrapperReceiver) {
            super(wrapperReceiver);
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode)
                throws RemoteException {
            /**
             * Map the acquired codes onto existing {@link BiometricConstants} acquired codes.
             */
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAcquired(
                        FaceManager.getMappedAcquiredInfo(acquiredInfo, vendorCode),
                        FaceManager.getAcquiredString(getContext(), acquiredInfo, vendorCode));
            }
        }

        @Override
        public void onError(long deviceId, int error, int vendorCode, int cookie)
                throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onError(cookie, TYPE_FACE, error, vendorCode);
            }
        }
    }

    /**
     * Receives callbacks from the ClientMonitor implementations. The results are forwarded to
     * the FaceManager.
     */
    private class ServiceListenerImpl implements ServiceListener {
        private IFaceServiceReceiver mFaceServiceReceiver;

        public ServiceListenerImpl(IFaceServiceReceiver receiver) {
            mFaceServiceReceiver = receiver;
        }

        @Override
        public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining)
                throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onEnrollResult(identifier.getDeviceId(),
                        identifier.getBiometricId(),
                        remaining);
            }
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode)
                throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onAcquired(deviceId, acquiredInfo, vendorCode);
            }
        }

        @Override
        public void onAuthenticationSucceeded(long deviceId,
                BiometricAuthenticator.Identifier biometric, int userId)
                throws RemoteException {
            if (mFaceServiceReceiver != null) {
                if (biometric == null || biometric instanceof Face) {
                    mFaceServiceReceiver.onAuthenticationSucceeded(deviceId, (Face) biometric,
                            userId, isStrongBiometric());
                } else {
                    Slog.e(TAG, "onAuthenticationSucceeded received non-face biometric");
                }
            }
        }

        @Override
        public void onAuthenticationFailed(long deviceId) throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onAuthenticationFailed(deviceId);
            }
        }

        @Override
        public void onError(long deviceId, int error, int vendorCode, int cookie)
                throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onError(deviceId, error, vendorCode);
            }
        }

        @Override
        public void onRemoved(BiometricAuthenticator.Identifier identifier,
                int remaining) throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onRemoved(identifier.getDeviceId(),
                        identifier.getBiometricId(), remaining);
            }
        }

        @Override
        public void onEnumerated(BiometricAuthenticator.Identifier identifier, int remaining)
                throws RemoteException {
            if (mFaceServiceReceiver != null) {
                mFaceServiceReceiver.onEnumerated(identifier.getDeviceId(),
                        identifier.getBiometricId(), remaining);
            }
        }
    }

    private final FaceConstants mFaceConstants = new FaceConstants();

    @GuardedBy("this")
    private IBiometricsFace mDaemon;
    private UsageStats mUsageStats;
    private boolean mRevokeChallengePending = false;
    // One of the AuthenticationClient constants
    private int mCurrentUserLockoutMode;

    private NotificationManager mNotificationManager;

    private int[] mBiometricPromptIgnoreList;
    private int[] mBiometricPromptIgnoreListVendor;
    private int[] mKeyguardIgnoreList;
    private int[] mKeyguardIgnoreListVendor;
    private int[] mEnrollIgnoreList;
    private int[] mEnrollIgnoreListVendor;

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
        public void onAcquired(final long deviceId, final int userId,
                final int acquiredInfo,
                final int vendorCode) {
            mHandler.post(() -> {
                FaceService.super.handleAcquired(deviceId, acquiredInfo, vendorCode);
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
                FaceService.super.handleError(deviceId, error, vendorCode);

                // TODO: this chunk of code should be common to all biometric services
                if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                    // If we get HW_UNAVAILABLE, try to connect again later...
                    Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                    synchronized (this) {
                        mDaemon = null;
                        mHalDeviceId = 0;
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

            if (duration == 0) {
                mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_NONE;
            } else if (duration == -1 || duration == Long.MAX_VALUE) {
                mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_PERMANENT;
            } else {
                mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_TIMED;
            }

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
            return daemon.enroll(token, timeout, disabledFeatures);
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

        final boolean ignoreKeyguardBlacklist = Settings.Secure.getInt(context.getContentResolver(),
                SKIP_KEYGUARD_ACQUIRE_IGNORE_LIST, 0) != 0;

        mUsageStats = new UsageStats(context);

        mNotificationManager = getContext().getSystemService(NotificationManager.class);

        mBiometricPromptIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = ignoreKeyguardBlacklist ? new int[0] : getContext().getResources()
                .getIntArray(R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor =
                ignoreKeyguardBlacklist ? new int[0] : getContext().getResources()
                        .getIntArray(R.array.config_face_acquire_vendor_keyguard_ignorelist);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
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
    protected Constants getConstants() {
        return mFaceConstants;
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
    protected String getLockoutResetIntent() {
        return ACTION_LOCKOUT_RESET;
    }

    @Override
    protected String getLockoutBroadcastPermission() {
        return RESET_FACE_LOCKOUT;
    }

    @Override
    protected long getHalDeviceId() {
        return mHalDeviceId;
    }

    @Override
    protected void handleUserSwitching(int userId) {
        super.handleUserSwitching(userId);
        // Will be updated when we get the callback from HAL
        mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_NONE;
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
    protected int getLockoutMode() {
        return mCurrentUserLockoutMode;
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

            try {
                mHalDeviceId = mDaemon.setCallback(mDaemonCallback).value;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open face HAL", e);
                mDaemon = null; // try again later!
            }

            if (DEBUG) Slog.v(TAG, "Face HAL id: " + mHalDeviceId);
            if (mHalDeviceId != 0) {
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
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = getBiometricUtils().getBiometricsForUser(getContext(), userId).size();
                PerformanceStats stats = mPerformanceMap.get(userId);
                PerformanceStats cryptoStats = mCryptoPerformanceMap.get(userId);
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
                set.put("accept", (stats != null) ? stats.accept : 0);
                set.put("reject", (stats != null) ? stats.reject : 0);
                set.put("acquire", (stats != null) ? stats.acquire : 0);
                set.put("lockout", (stats != null) ? stats.lockout : 0);
                set.put("permanentLockout", (stats != null) ? stats.permanentLockout : 0);
                // cryptoStats measures statistics about secure face transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", (cryptoStats != null) ? cryptoStats.accept : 0);
                set.put("rejectCrypto", (cryptoStats != null) ? cryptoStats.reject : 0);
                set.put("acquireCrypto", (cryptoStats != null) ? cryptoStats.acquire : 0);
                set.put("lockoutCrypto", (cryptoStats != null) ? cryptoStats.lockout : 0);
                set.put("permanentLockoutCrypto",
                        (cryptoStats != null) ? cryptoStats.permanentLockout : 0);
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + mHALDeathCount);

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
