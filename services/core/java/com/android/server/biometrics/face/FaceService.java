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
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.AuthenticationClient;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
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

    private final class FaceAuthClient extends AuthenticationClientImpl {
        private int mLastAcquire;

        public FaceAuthClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int targetUserId, int groupId, long opId,
                boolean restricted, String owner, int cookie, boolean requireConfirmation) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId,
                    restricted, owner, cookie, requireConfirmation);
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
            return mLastAcquire != FaceManager.FACE_ACQUIRED_NOT_DETECTED;
        }

        @Override
        public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
                boolean authenticated, ArrayList<Byte> token) {
            final boolean result = super.onAuthenticated(identifier, authenticated, token);

            // For face, the authentication lifecycle ends either when
            // 1) Authenticated == true
            // 2) Error occurred
            // 3) Authenticated == false
            // Fingerprint currently does not end when the third condition is met which is a bug,
            // but let's leave it as-is for now.
            return result || !authenticated;
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

                final String id = "FaceService";

                NotificationManager nm =
                        getContext().getSystemService(NotificationManager.class);
                NotificationChannel channel = new NotificationChannel(id, name,
                        NotificationManager.IMPORTANCE_HIGH);
                Notification notification = new Notification.Builder(getContext(), id)
                        .setSmallIcon(R.drawable.ic_lock)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setSubText(name)
                        .setOnlyAlertOnce(true)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setContentIntent(pendingIntent)
                        .build();

                nm.createNotificationChannel(channel);
                nm.notifyAsUser(null /* tag */, 0 /* id */, notification, UserHandle.CURRENT);
            }

            return super.onAcquired(acquireInfo, vendorCode);
        }
    }

    /**
     * Receives the incoming binder calls from FaceManager.
     */
    private final class FaceServiceWrapper extends IFaceService.Stub {

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
            return startRevokeChallenge(token);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken,
                final IFaceServiceReceiver receiver, final String opPackageName,
                final int[] disabledFeatures) {
            checkPermission(MANAGE_BIOMETRIC);

            final boolean restricted = isRestricted();
            final EnrollClientImpl client = new EnrollClientImpl(getContext(), mDaemonWrapper,
                    mHalDeviceId, token, new ServiceListenerImpl(receiver), mCurrentUserId,
                    0 /* groupId */, cryptoToken, restricted, opPackageName, disabledFeatures) {

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
                    0 /* cookie */, false /* requireConfirmation */);
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
                    requireConfirmation);
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
                final IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_BIOMETRIC);

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
                } else if (args.length > 0 && "--proto".equals(args[0])) {
                    dumpProto(fd);
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
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
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
        public long getAuthenticatorId(String opPackageName) {
            // In this method, we're not checking whether the caller is permitted to use face
            // API because current authenticator ID is leaked (in a more contrived way) via Android
            // Keystore (android.security.keystore package): the user of that API can create a key
            // which requires face authentication for its use, and then query the key's
            // characteristics (hidden API) which returns, among other things, face
            // authenticator ID which was active at key creation time.
            //
            // Reason: The part of Android Keystore which runs inside an app's process invokes this
            // method in certain cases. Those cases are not always where the developer demonstrates
            // explicit intent to use face functionality. Thus, to avoiding throwing an
            // unexpected SecurityException this method does not check whether its caller is
            // permitted to use face API.
            //
            // The permission check should be restored once Android Keystore no longer invokes this
            // method from inside app processes.

            return FaceService.this.getAuthenticatorId(opPackageName);
        }

        @Override // Binder call
        public void resetLockout(byte[] token) {
            checkPermission(MANAGE_BIOMETRIC);

            if (!FaceService.this.hasEnrolledBiometrics(mCurrentUserId)) {
                Slog.w(TAG, "Ignoring lockout reset, no templates enrolled");
                return;
            }

            try {
                mDaemonWrapper.resetLockout(token);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Unable to reset lockout", e);
            }
        }

        @Override
        public void setFeature(int feature, boolean enabled, final byte[] token,
                IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_BIOMETRIC);

            mHandler.post(() -> {
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
        public void getFeature(int feature, IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_BIOMETRIC);

            mHandler.post(() -> {
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
                getWrapperReceiver().onError(cookie, error,
                        FaceManager.getErrorString(getContext(), error, vendorCode));
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
                            userId);
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
    // One of the AuthenticationClient constants
    private int mCurrentUserLockoutMode;

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
            } else if (duration == Long.MAX_VALUE) {
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
        public int authenticate(long operationId, int groupId) throws RemoteException {
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
                ArrayList<Integer> disabledFeatures) throws RemoteException {
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

        mBiometricPromptIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_keyguard_ignorelist);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
    }

    @Override
    public void onStart() {
        super.onStart();
        publishBinderService(Context.FACE_SERVICE, new FaceServiceWrapper());
        SystemServerInitThreadPool.get().submit(this::getFaceDaemon, TAG + ".onStart");
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
                }
                mAuthenticatorIds.put(userId,
                        hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId().value : 0L);
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
            return daemon.revokeChallenge();
        } catch (RemoteException e) {
            Slog.e(TAG, "startRevokeChallenge failed", e);
        }
        return 0;
    }

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
        pw.println("HAL Deaths: " + mHALDeathCount);
        mHALDeathCount = 0;
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FaceServiceDumpProto.USERS);

            proto.write(FaceUserStatsProto.USER_ID, userId);
            proto.write(FaceUserStatsProto.NUM_FACES,
                    getBiometricUtils().getBiometricsForUser(getContext(), userId).size());

            // Normal face authentications (e.g. lockscreen)
            final PerformanceStats normal = mPerformanceMap.get(userId);
            if (normal != null) {
                final long countsToken = proto.start(FaceUserStatsProto.NORMAL);
                proto.write(FaceActionStatsProto.ACCEPT, normal.accept);
                proto.write(FaceActionStatsProto.REJECT, normal.reject);
                proto.write(FaceActionStatsProto.ACQUIRE, normal.acquire);
                proto.write(FaceActionStatsProto.LOCKOUT, normal.lockout);
                proto.write(FaceActionStatsProto.LOCKOUT_PERMANENT, normal.lockout);
                proto.end(countsToken);
            }

            // Statistics about secure face transactions (e.g. to unlock password
            // storage, make secure purchases, etc.)
            final PerformanceStats crypto = mCryptoPerformanceMap.get(userId);
            if (crypto != null) {
                final long countsToken = proto.start(FaceUserStatsProto.CRYPTO);
                proto.write(FaceActionStatsProto.ACCEPT, crypto.accept);
                proto.write(FaceActionStatsProto.REJECT, crypto.reject);
                proto.write(FaceActionStatsProto.ACQUIRE, crypto.acquire);
                proto.write(FaceActionStatsProto.LOCKOUT, crypto.lockout);
                proto.write(FaceActionStatsProto.LOCKOUT_PERMANENT, crypto.lockout);
                proto.end(countsToken);
            }

            proto.end(userToken);
        }
        proto.flush();
        mPerformanceMap.clear();
        mCryptoPerformanceMap.clear();
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
