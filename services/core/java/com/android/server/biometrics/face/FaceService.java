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
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.Metrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
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
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 3;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 12;
    private static final int CHALLENGE_TIMEOUT_SEC = 600; // 10 minutes

    private final class FaceAuthClient extends AuthenticationClientImpl {
        public FaceAuthClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int targetUserId, int groupId, long opId,
                boolean restricted, String owner, int cookie, boolean requireConfirmation) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId,
                    restricted, owner, cookie, requireConfirmation);
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
                    0 /* groupId */, cryptoToken, restricted, opPackageName, disabledFeatures);

            enrollInternal(client, UserHandle.getCallingUserId());
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
            final RemovalClientImpl client = new RemovalClientImpl(getContext(), mDaemonWrapper,
                    mHalDeviceId, token, new ServiceListenerImpl(receiver), faceId, 0 /* groupId */,
                    userId, restricted, token.toString());
            client.setShouldNotifyUserActivity(true);
            removeInternal(client);
        }

        @Override
        public void enumerate(final IBinder token, final int userId,
                final IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_BIOMETRIC);

            final boolean restricted = isRestricted();
            final EnumerateClientImpl client = new EnumerateClientImpl(getContext(), mDaemonWrapper,
                    mHalDeviceId, token, new ServiceListenerImpl(receiver), userId, userId,
                    restricted, getContext().getOpPackageName());
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
                if (args.length > 0 && "--proto".equals(args[0])) {
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

            return FaceService.this.getEnrolledFaces(userId);
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
        public void resetTimeout(byte[] token) {
            checkPermission(MANAGE_BIOMETRIC);
            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(mResetFailedAttemptsForCurrentUserRunnable);
        }

        @Override
        public int setFeature(int feature, boolean enabled, final byte[] token) {
            checkPermission(MANAGE_BIOMETRIC);

            final ArrayList<Byte> byteToken = new ArrayList<>();
            for (int i = 0; i < token.length; i++) {
                byteToken.add(token[i]);
            }

            int result;
            try {
                result = mDaemon != null ? mDaemon.setFeature(feature, enabled, byteToken)
                        : Status.INTERNAL_ERROR;
            } catch (RemoteException e) {
                Slog.e(getTag(), "Unable to set feature: " + feature + " to enabled:" + enabled,
                        e);
                result = Status.INTERNAL_ERROR;
            }

            return result;
        }

        @Override
        public boolean getFeature(int feature) {
            checkPermission(MANAGE_BIOMETRIC);

            boolean result = true;
            try {
                result = mDaemon != null ? mDaemon.getFeature(feature) : true;
            } catch (RemoteException e) {
                Slog.e(getTag(), "Unable to getRequireAttention", e);
            }
            return result;
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
                    mFaceServiceReceiver.onAuthenticationSucceeded(deviceId, (Face)biometric);
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

            }
        }
    }

    private final FaceMetrics mFaceMetrics = new FaceMetrics();

    @GuardedBy("this")
    private IBiometricsFace mDaemon;
    private long mHalDeviceId;

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
                public void onRemoved(final long deviceId, final int faceId, final int userId,
                        final int remaining) {
                    mHandler.post(() -> {
                        final Face face = new Face("", faceId, deviceId);
                        FaceService.super.handleRemoved(face, remaining);
                    });
                }

                @Override
                public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId)
                        throws RemoteException {
                    // TODO
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
            // TODO: plumb requireAttention down from framework
            return daemon.enroll(token, timeout, disabledFeatures);
        }
    };


    public FaceService(Context context) {
        super(context);
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
    protected BiometricUtils getBiometricUtils() {
        return FaceUtils.getInstance();
    }

    @Override
    protected int getFailedAttemptsLockoutTimed() {
        return MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED;
    }

    @Override
    protected int getFailedAttemptsLockoutPermanent() {
        return MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT;
    }

    @Override
    protected Metrics getMetrics() {
        return mFaceMetrics;
    }

    @Override
    protected boolean hasReachedEnrollmentLimit(int userId) {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_faceMaxTemplatesPerUser);
        final int enrolled = FaceService.this.getEnrolledFaces(userId).size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many faces registered");
            return true;
        }
        return false;
    }

    @Override
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        mDaemon = null;
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
        updateActiveGroup(userId, null);
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
        // TODO: Perhaps do the same in FingerprintService
    }

    @Override
    protected boolean checkAppOps(int uid, String opPackageName) {
        return mAppOps.noteOp(AppOpsManager.OP_USE_BIOMETRIC, uid, opPackageName)
                == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    protected void notifyClientActiveCallbacks(boolean isActive) {
        // noop for Face.
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

    private List<Face> getEnrolledFaces(int userId) {
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
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
}
