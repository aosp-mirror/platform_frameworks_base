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

package com.android.server.biometrics;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityTaskManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.fingerprint.Fingerprint;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class containing all of the business logic for biometric services, e.g.
 * Fingerprint, Face, Iris.
 *
 * @hide
 */
public abstract class BiometricServiceBase extends SystemService
        implements IHwBinder.DeathRecipient {

    protected static final boolean DEBUG = true;

    private static final boolean CLEANUP_UNKNOWN_TEMPLATES = true;
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";
    private static final int MSG_USER_SWITCHING = 10;
    private static final long CANCEL_TIMEOUT_LIMIT = 3000; // max wait for onCancel() from HAL,in ms

    private final Context mContext;
    private final String mKeyguardPackage;
    private final IActivityTaskManager mActivityTaskManager;
    private final PowerManager mPowerManager;
    private final UserManager mUserManager;
    private final MetricsLogger mMetricsLogger;
    private final BiometricTaskStackListener mTaskStackListener = new BiometricTaskStackListener();
    private final ResetClientStateRunnable mResetClientState = new ResetClientStateRunnable();
    private final ArrayList<LockoutResetMonitor> mLockoutMonitors = new ArrayList<>();

    protected final IStatusBarService mStatusBarService;
    protected final Map<Integer, Long> mAuthenticatorIds =
            Collections.synchronizedMap(new HashMap<>());
    protected final AppOpsManager mAppOps;

    /**
     * Handler which all subclasses should post events to.
     */
    protected final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHING:
                    handleUserSwitching(msg.arg1);
                    break;
                default:
                    Slog.w(getTag(), "Unknown message:" + msg.what);
            }
        }
    };

    private final IBinder mToken = new Binder(); // Used for internal enumeration
    private final ArrayList<UserTemplate> mUnknownHALTemplates = new ArrayList<>();

    private IBiometricService mBiometricService;
    private ClientMonitor mCurrentClient;
    private ClientMonitor mPendingClient;
    private PerformanceStats mPerformanceStats;
    private int mSensorId;
    protected int mCurrentUserId = UserHandle.USER_NULL;
    protected long mHalDeviceId;
    // Tracks if the current authentication makes use of CryptoObjects.
    protected boolean mIsCrypto;
    // Normal authentications are tracked by mPerformanceMap.
    protected HashMap<Integer, PerformanceStats> mPerformanceMap = new HashMap<>();
    // Transactions that make use of CryptoObjects are tracked by mCryptoPerformaceMap.
    protected HashMap<Integer, PerformanceStats> mCryptoPerformanceMap = new HashMap<>();
    protected int mHALDeathCount;

    protected class PerformanceStats {
        public int accept; // number of accepted biometrics
        public int reject; // number of rejected biometrics
        public int acquire; // total number of acquisitions. Should be >= accept+reject due to poor
        // image acquisition in some cases (too fast, too slow, dirty sensor, etc.)
        public int lockout; // total number of lockouts
        public int permanentLockout; // total number of permanent lockouts
    }

    /**
     * @return the log tag.
     */
    protected abstract String getTag();

    /**
     * @return wrapper for the HAL
     */
    protected abstract DaemonWrapper getDaemonWrapper();

    /**
     * @return the biometric utilities for a specific implementation.
     */
    protected abstract BiometricUtils getBiometricUtils();

    /**
     * @return the metrics constants for a biometric implementation.
     */
    protected abstract Constants getConstants();

    /**
     * @param userId
     * @return true if the enrollment limit has been reached.
     */
    protected abstract boolean hasReachedEnrollmentLimit(int userId);

    /**
     * Notifies the HAL that the user has changed.
     * @param userId
     * @param clientPackage
     */
    protected abstract void updateActiveGroup(int userId, String clientPackage);

    /**
     * @return The protected intent to reset lockout for a specific biometric.
     */
    protected abstract String getLockoutResetIntent();

    /**
     * @return The permission the sender is required to have in order for the lockout reset intent
     *         to be received by the BiometricService implementation.
     */
    protected abstract String getLockoutBroadcastPermission();

    /**
     * @return The HAL ID.
     */
    protected abstract long getHalDeviceId();

    /**
     * @param userId
     * @return Returns true if the user has any enrolled biometrics.
     */
    protected abstract boolean hasEnrolledBiometrics(int userId);

    /**
     * @return Returns the MANAGE_* permission string, which is required for enrollment, removal
     * etc.
     */
    protected abstract String getManageBiometricPermission();

    /**
     * Checks if the caller has permission to use the biometric service - throws a SecurityException
     * if not.
     */
    protected abstract void checkUseBiometricPermission();

    /**
     * Checks if the caller passes the app ops check
     */
    protected abstract boolean checkAppOps(int uid, String opPackageName);

    protected abstract List<? extends BiometricAuthenticator.Identifier> getEnrolledTemplates(
            int userId);

    /**
     * Notifies clients of any change in the biometric state (active / idle). This is mainly for
     * Fingerprint navigation gestures.
     * @param isActive
     */
    protected void notifyClientActiveCallbacks(boolean isActive) {}

    protected abstract int statsModality();

    /**
     * @return one of the AuthenticationClient LOCKOUT constants
     */
    protected abstract int getLockoutMode();

    protected abstract class AuthenticationClientImpl extends AuthenticationClient {

        // Used to check if the public API that was invoked was from FingerprintManager. Only
        // to be overridden by FingerprintService.
        protected boolean isFingerprint() {
            return false;
        }

        public AuthenticationClientImpl(Context context, DaemonWrapper daemon, long halDeviceId,
                IBinder token, ServiceListener listener, int targetUserId, int groupId, long opId,
                boolean restricted, String owner, int cookie, boolean requireConfirmation,
                Surface surface) {
            super(context, getConstants(), daemon, halDeviceId, token, listener, targetUserId,
                    groupId, opId, restricted, owner, cookie, requireConfirmation, surface);
        }

        @Override
        protected int statsClient() {
            if (isKeyguard(getOwnerString())) {
                return BiometricsProtoEnums.CLIENT_KEYGUARD;
            } else if (isBiometricPrompt()) {
                return BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT;
            } else if (isFingerprint()) {
                return BiometricsProtoEnums.CLIENT_FINGERPRINT_MANAGER;
            } else {
                return BiometricsProtoEnums.CLIENT_UNKNOWN;
            }
        }

        @Override
        public void onStart() {
            try {
                mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Could not register task stack listener", e);
            }
        }

        @Override
        public void onStop() {
            try {
                mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(getTag(), "Could not unregister task stack listener", e);
            }
        }

        @Override
        public void notifyUserActivity() {
            userActivity();
        }

        @Override
        public int handleFailedAttempt() {
            final int lockoutMode = getLockoutMode();
            if (lockoutMode == AuthenticationClient.LOCKOUT_PERMANENT) {
                mPerformanceStats.permanentLockout++;
            } else if (lockoutMode == AuthenticationClient.LOCKOUT_TIMED) {
                mPerformanceStats.lockout++;
            }

            // Failing multiple times will continue to push out the lockout time
            if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
                return lockoutMode;
            }
            return AuthenticationClient.LOCKOUT_NONE;
        }
    }

    protected abstract class EnrollClientImpl extends EnrollClient {

        public EnrollClientImpl(Context context, DaemonWrapper daemon, long halDeviceId,
                IBinder token, ServiceListener listener, int userId, int groupId,
                byte[] cryptoToken, boolean restricted, String owner,
                final int[] disabledFeatures, int timeoutSec, Surface surface) {
            super(context, getConstants(), daemon, halDeviceId, token, listener,
                    userId, groupId, cryptoToken, restricted, owner, getBiometricUtils(),
                    disabledFeatures, timeoutSec, surface);
        }

        @Override
        public void notifyUserActivity() {
            userActivity();
        }
    }

    /**
     * An internal class to help clean up unknown templates in HAL and Framework
     */
    private final class InternalRemovalClient extends RemovalClient {
        InternalRemovalClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int templateId, int groupId, int userId,
                boolean restricted, String owner) {
            super(context, getConstants(), daemon, halDeviceId, token, listener, templateId, groupId,
                    userId, restricted, owner, getBiometricUtils());
        }

        @Override
        protected int statsModality() {
            return BiometricServiceBase.this.statsModality();
        }
    }

    /**
     * Internal class to help clean up unknown templates in the HAL and Framework
     */
    private final class InternalEnumerateClient extends EnumerateClient {

        private BiometricUtils mUtils;
        // List of templates that are known to the Framework. Remove from this list when enumerate
        // returns a template that contains a match.
        private List<? extends BiometricAuthenticator.Identifier> mEnrolledList;
        // List of templates to remove from the HAL
        private List<BiometricAuthenticator.Identifier> mUnknownHALTemplates = new ArrayList<>();

        InternalEnumerateClient(Context context,
                DaemonWrapper daemon, long halDeviceId, IBinder token,
                ServiceListener listener, int groupId, int userId, boolean restricted,
                String owner, List<? extends BiometricAuthenticator.Identifier> enrolledList,
                BiometricUtils utils) {
            super(context, getConstants(), daemon, halDeviceId, token, listener, groupId, userId,
                    restricted, owner);
            mEnrolledList = enrolledList;
            mUtils = utils;
        }

        private void handleEnumeratedTemplate(BiometricAuthenticator.Identifier identifier) {
            if (identifier == null) {
                return;
            }
            Slog.v(getTag(), "handleEnumeratedTemplate: " + identifier.getBiometricId());
            boolean matched = false;
            for (int i = 0; i < mEnrolledList.size(); i++) {
                if (mEnrolledList.get(i).getBiometricId() == identifier.getBiometricId()) {
                    mEnrolledList.remove(i);
                    matched = true;
                    break;
                }
            }

            // TemplateId 0 means no templates in HAL
            if (!matched && identifier.getBiometricId() != 0) {
                mUnknownHALTemplates.add(identifier);
            }
            Slog.v(getTag(), "Matched: " + matched);
        }

        private void doTemplateCleanup() {
            if (mEnrolledList == null) {
                return;
            }

            // At this point, mEnrolledList only contains templates known to the framework and
            // not the HAL.
            for (int i = 0; i < mEnrolledList.size(); i++) {
                BiometricAuthenticator.Identifier identifier = mEnrolledList.get(i);
                Slog.e(getTag(), "doTemplateCleanup(): Removing dangling template from framework: "
                        + identifier.getBiometricId() + " "
                        + identifier.getName());
                mUtils.removeBiometricForUser(getContext(),
                        getTargetUserId(), identifier.getBiometricId());
                FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                        statsModality(),
                        BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_FRAMEWORK);
            }
            mEnrolledList.clear();
        }

        public List<BiometricAuthenticator.Identifier> getUnknownHALTemplates() {
            return mUnknownHALTemplates;
        }

        @Override
        public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
                int remaining) {
            handleEnumeratedTemplate(identifier);
            if (remaining == 0) {
                doTemplateCleanup();
            }
            return remaining == 0;
        }

        @Override
        protected int statsModality() {
            return BiometricServiceBase.this.statsModality();
        }
    }

    /**
     * Wraps the callback interface from Service -> Manager
     */
    protected interface ServiceListener {
        default void onEnrollResult(BiometricAuthenticator.Identifier identifier,
                int remaining) throws RemoteException {};

        void onAcquired(long deviceId, int acquiredInfo, int vendorCode) throws RemoteException;

        default void onAuthenticationSucceeded(long deviceId,
                BiometricAuthenticator.Identifier biometric, int userId) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationSucceededInternal(int sensorId, boolean requireConfirmation,
                byte[] token) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationFailed(long deviceId) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationFailedInternal()
                throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        void onError(long deviceId, int error, int vendorCode, int cookie) throws RemoteException;

        default void onRemoved(BiometricAuthenticator.Identifier identifier,
                int remaining) throws RemoteException {};

        default void onEnumerated(BiometricAuthenticator.Identifier identifier,
                int remaining) throws RemoteException {};
    }

    /**
     * Wraps the callback interface from Service -> BiometricPrompt
     */
    protected abstract class BiometricServiceListener implements ServiceListener {
        private IBiometricSensorReceiver mWrapperReceiver;

        public BiometricServiceListener(IBiometricSensorReceiver wrapperReceiver) {
            mWrapperReceiver = wrapperReceiver;
        }

        public IBiometricSensorReceiver getWrapperReceiver() {
            return mWrapperReceiver;
        }

        @Override
        public void onAuthenticationSucceededInternal(int sensorId, boolean requireConfirmation,
                byte[] token) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAuthenticationSucceeded(sensorId, requireConfirmation,
                        token);
            }
        }

        @Override
        public void onAuthenticationFailedInternal()
                throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAuthenticationFailed();
            }
        }
    }

    /**
     * Wraps a portion of the interface from Service -> Daemon that is used by the ClientMonitor
     * subclasses.
     */
    protected interface DaemonWrapper {
        int ERROR_ESRCH = 3; // Likely HAL is dead. see errno.h.
        int authenticate(long operationId, int groupId, Surface surface)
                throws RemoteException;
        int cancel() throws RemoteException;
        int remove(int groupId, int biometricId) throws RemoteException;
        int enumerate() throws RemoteException;
        int enroll(byte[] token, int groupId, int timeout,
                ArrayList<Integer> disabledFeatures, Surface surface) throws RemoteException;
        void resetLockout(byte[] token) throws RemoteException;
    }

    private final Runnable mOnTaskStackChangedRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!(mCurrentClient instanceof AuthenticationClient)) {
                    return;
                }
                final String currentClient = mCurrentClient.getOwnerString();
                if (isKeyguard(currentClient)) {
                    return; // Keyguard is always allowed
                }
                List<ActivityManager.RunningTaskInfo> runningTasks =
                        mActivityTaskManager.getTasks(1);
                if (!runningTasks.isEmpty()) {
                    final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                    if (!topPackage.contentEquals(currentClient)
                            && !mCurrentClient.isAlreadyDone()) {
                        Slog.e(getTag(), "Stopping background authentication, top: "
                                + topPackage + " currentClient: " + currentClient);
                        mCurrentClient.stop(false /* initiatedByClient */);
                    }
                }
            } catch (RemoteException e) {
                Slog.e(getTag(), "Unable to get running tasks", e);
            }
        }
    };

    private final class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(mOnTaskStackChangedRunnable);
        }
    }

    private final class ResetClientStateRunnable implements Runnable {
        @Override
        public void run() {
            /**
             * Warning: if we get here, the driver never confirmed our call to cancel the current
             * operation (authenticate, enroll, remove, enumerate, etc), which is
             * really bad.  The result will be a 3-second delay in starting each new client.
             * If you see this on a device, make certain the driver notifies with
             * {@link BiometricConstants#BIOMETRIC_ERROR_CANCELED} in response to cancel()
             * once it has successfully switched to the IDLE state in the HAL.
             * Additionally,{@link BiometricConstants#BIOMETRIC_ERROR_CANCELED} should only be sent
             * in response to an actual cancel() call.
             */
            Slog.w(getTag(), "Client "
                    + (mCurrentClient != null ? mCurrentClient.getOwnerString() : "null")
                    + " failed to respond to cancel, starting client "
                    + (mPendingClient != null ? mPendingClient.getOwnerString() : "null"));

            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    statsModality(), BiometricsProtoEnums.ISSUE_CANCEL_TIMED_OUT);

            ClientMonitor newClient = mPendingClient;
            mCurrentClient = null;
            mPendingClient = null;
            startClient(newClient, false);
        }
    }



    private final class LockoutResetMonitor implements IBinder.DeathRecipient {
        private static final long WAKELOCK_TIMEOUT_MS = 2000;
        private final IBiometricServiceLockoutResetCallback mCallback;
        private final PowerManager.WakeLock mWakeLock;

        public LockoutResetMonitor(IBiometricServiceLockoutResetCallback callback) {
            mCallback = callback;
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "lockout reset callback");
            try {
                mCallback.asBinder().linkToDeath(LockoutResetMonitor.this, 0);
            } catch (RemoteException e) {
                Slog.w(getTag(), "caught remote exception in linkToDeath", e);
            }
        }

        public void sendLockoutReset() {
            if (mCallback != null) {
                try {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    mCallback.onLockoutReset(getHalDeviceId(), new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            releaseWakelock();
                        }
                    });
                } catch (DeadObjectException e) {
                    Slog.w(getTag(), "Death object while invoking onLockoutReset: ", e);
                    mHandler.post(mRemoveCallbackRunnable);
                } catch (RemoteException e) {
                    Slog.w(getTag(), "Failed to invoke onLockoutReset: ", e);
                    releaseWakelock();
                }
            }
        }

        private final Runnable mRemoveCallbackRunnable = new Runnable() {
            @Override
            public void run() {
                releaseWakelock();
                removeLockoutResetCallback(LockoutResetMonitor.this);
            }
        };

        @Override
        public void binderDied() {
            Slog.e(getTag(), "Lockout reset callback binder died");
            mHandler.post(mRemoveCallbackRunnable);
        }

        private void releaseWakelock() {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    /**
     * Container for enumerated templates. Used to keep track when cleaning up unknown
     * templates.
     */
    private final class UserTemplate {
        final BiometricAuthenticator.Identifier mIdentifier;
        final int mUserId;
        UserTemplate(BiometricAuthenticator.Identifier identifier, int userId) {
            this.mIdentifier = identifier;
            this.mUserId = userId;
        }
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public BiometricServiceBase(Context context) {
        super(context);
        mContext = context;
        mStatusBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        final ComponentName keyguardComponent = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.config_keyguardComponent));
        mKeyguardPackage = keyguardComponent != null ? keyguardComponent.getPackageName() : null;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mActivityTaskManager = ((ActivityTaskManager) context.getSystemService(
                Context.ACTIVITY_TASK_SERVICE)).getService();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mUserManager = UserManager.get(mContext);
        mMetricsLogger = new MetricsLogger();
    }

    @Override
    public void onStart() {
        listenForUserSwitches();
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(getTag(), "HAL died");
        mMetricsLogger.count(getConstants().tagHalDied(), 1);
        mHALDeathCount++;
        mCurrentUserId = UserHandle.USER_NULL;

        // All client lifecycle must be managed on the handler.
        mHandler.post(() -> {
            Slog.e(getTag(), "Sending BIOMETRIC_ERROR_HW_UNAVAILABLE after HAL crash");
            handleError(getHalDeviceId(), BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
        });

        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                statsModality(), BiometricsProtoEnums.ISSUE_HAL_DEATH);
    }

    protected void initializeConfigurationInternal(int sensorId) {
        if (DEBUG) {
            Slog.d(getTag(), "initializeConfigurationInternal(" + sensorId + ")");
        }
        mSensorId = sensorId;
    }

    protected ClientMonitor getCurrentClient() {
        return mCurrentClient;
    }

    protected ClientMonitor getPendingClient() {
        return mPendingClient;
    }

    protected boolean isStrongBiometric() {
        IBiometricService service = IBiometricService.Stub.asInterface(
                ServiceManager.getService(Context.BIOMETRIC_SERVICE));
        try {
            return Utils.isAtLeastStrength(service.getCurrentStrength(mSensorId),
                    Authenticators.BIOMETRIC_STRONG);
        } catch (RemoteException e) {
            Slog.e(getTag(), "RemoteException", e);
            return false;
        }
    }

    protected int getSensorId() {
        return mSensorId;
    }

    /**
     * Callback handlers from the daemon. The caller must put this on a handler.
     */

    protected void handleAcquired(long deviceId, int acquiredInfo, int vendorCode) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onAcquired(acquiredInfo, vendorCode)) {
            removeClient(client);
        }
        if (mPerformanceStats != null && getLockoutMode() == AuthenticationClient.LOCKOUT_NONE
                && client instanceof AuthenticationClient) {
            // ignore enrollment acquisitions or acquisitions when we're locked out
            mPerformanceStats.acquire++;
        }
    }

    protected void handleAuthenticated(BiometricAuthenticator.Identifier identifier,
            ArrayList<Byte> token) {
        ClientMonitor client = mCurrentClient;
        final boolean authenticated = identifier.getBiometricId() != 0;

        if (client != null && client.onAuthenticated(identifier, authenticated, token)) {
            removeClient(client);
        }
        if (authenticated) {
            mPerformanceStats.accept++;
        } else {
            mPerformanceStats.reject++;
        }
    }

    protected void handleEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onEnrollResult(identifier, remaining)) {
            removeClient(client);
            // When enrollment finishes, update this group's authenticator id, as the HAL has
            // already generated a new authenticator id when the new biometric is enrolled.
            if (identifier instanceof Fingerprint) {
                updateActiveGroup(((Fingerprint)identifier).getGroupId(), null);
            }
        }
    }

    protected void handleError(long deviceId, int error, int vendorCode) {
        final ClientMonitor client = mCurrentClient;

        if (DEBUG) Slog.v(getTag(), "handleError(client="
                + (client != null ? client.getOwnerString() : "null") + ", error = " + error + ")");

        if (client instanceof InternalRemovalClient
                || client instanceof InternalEnumerateClient) {
            clearEnumerateState();
        }

        if (client != null && client.onError(deviceId, error, vendorCode)) {
            removeClient(client);
        }

        if (error == BiometricConstants.BIOMETRIC_ERROR_CANCELED) {
            mHandler.removeCallbacks(mResetClientState);
            if (mPendingClient != null) {
                if (DEBUG) Slog.v(getTag(), "start pending client " +
                        mPendingClient.getOwnerString());
                startClient(mPendingClient, false);
                mPendingClient = null;
            }
        }
    }

    protected void handleRemoved(BiometricAuthenticator.Identifier identifier,
            final int remaining) {
        if (DEBUG) Slog.w(getTag(), "Removed: fid=" + identifier.getBiometricId()
                + ", dev=" + identifier.getDeviceId()
                + ", rem=" + remaining);

        ClientMonitor client = mCurrentClient;
        if (client != null && client.onRemoved(identifier, remaining)) {
            removeClient(client);
            // When the last biometric of a group is removed, update the authenticator id
            int userId = mCurrentUserId;
            if (identifier instanceof Fingerprint) {
                userId = ((Fingerprint) identifier).getGroupId();
            }
            if (!hasEnrolledBiometrics(userId)) {
                updateActiveGroup(userId, null);
            }
        }

        if (client instanceof InternalRemovalClient && !mUnknownHALTemplates.isEmpty()) {
            startCleanupUnknownHALTemplates();
        } else if (client instanceof InternalRemovalClient) {
            clearEnumerateState();
        }
    }

    protected void handleEnumerate(BiometricAuthenticator.Identifier identifier, int remaining) {
        ClientMonitor client = mCurrentClient;
        if (client != null) {
            client.onEnumerationResult(identifier, remaining);
        }

        // All templates in the HAL for this user were enumerated
        if (remaining == 0) {
            if (client instanceof InternalEnumerateClient) {
                List<BiometricAuthenticator.Identifier> unknownHALTemplates =
                        ((InternalEnumerateClient) client).getUnknownHALTemplates();

                if (!unknownHALTemplates.isEmpty()) {
                    Slog.w(getTag(), "Adding " + unknownHALTemplates.size()
                            + " templates for deletion");
                }
                for (int i = 0; i < unknownHALTemplates.size(); i++) {
                    mUnknownHALTemplates.add(new UserTemplate(unknownHALTemplates.get(i),
                            client.getTargetUserId()));
                }
                removeClient(client);
                startCleanupUnknownHALTemplates();
            } else if (client != null) {
                removeClient(client);
            }
        }
    }

    /**
     * Calls from the Manager. These are still on the calling binder's thread.
     */

    protected void enrollInternal(EnrollClientImpl client, int userId) {
        if (hasReachedEnrollmentLimit(userId)) {
            return;
        }

        // Group ID is arbitrarily set to parent profile user ID. It just represents
        // the default biometrics for the user.
        if (!isCurrentUserOrProfile(userId)) {
            return;
        }

        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    protected void cancelEnrollmentInternal(IBinder token) {
        mHandler.post(() -> {
            ClientMonitor client = mCurrentClient;
            if (client instanceof EnrollClient && client.getToken() == token) {
                if (DEBUG) Slog.v(getTag(), "Cancelling enrollment");
                client.stop(client.getToken() == token);
            }
        });
    }

    protected void authenticateInternal(AuthenticationClientImpl client, long opId,
            String opPackageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int callingUserId = UserHandle.getCallingUserId();
        authenticateInternal(client, opId, opPackageName, callingUid, callingPid, callingUserId);
    }

    protected void authenticateInternal(AuthenticationClientImpl client, long opId,
            String opPackageName, int callingUid, int callingPid, int callingUserId) {
        if (!canUseBiometric(opPackageName, true /* foregroundOnly */, callingUid, callingPid,
                callingUserId)) {
            if (DEBUG) Slog.v(getTag(), "authenticate(): reject " + opPackageName);
            return;
        }

        mHandler.post(() -> {
            mMetricsLogger.histogram(getConstants().tagAuthToken(), opId != 0L ? 1 : 0);

            // Get performance stats object for this user.
            HashMap<Integer, PerformanceStats> pmap
                    = (opId == 0) ? mPerformanceMap : mCryptoPerformanceMap;
            PerformanceStats stats = pmap.get(mCurrentUserId);
            if (stats == null) {
                stats = new PerformanceStats();
                pmap.put(mCurrentUserId, stats);
            }
            mPerformanceStats = stats;
            mIsCrypto = (opId != 0);

            startAuthentication(client, opPackageName);
        });
    }

    protected void cancelAuthenticationInternal(final IBinder token, final String opPackageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int callingUserId = UserHandle.getCallingUserId();
        cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid, callingUserId,
                true /* fromClient */);
    }

    protected void cancelAuthenticationInternal(final IBinder token, final String opPackageName,
            int callingUid, int callingPid, int callingUserId, boolean fromClient) {

        if (DEBUG) Slog.v(getTag(), "cancelAuthentication(" + opPackageName + ")");
        if (fromClient) {
            // Only check this if cancel was called from the client (app). If cancel was called
            // from BiometricService, it means the dialog was dismissed due to user interaction.
            if (!canUseBiometric(opPackageName, true /* foregroundOnly */, callingUid, callingPid,
                    callingUserId)) {
                if (DEBUG) {
                    Slog.v(getTag(), "cancelAuthentication(): reject " + opPackageName);
                }
                return;
            }
        }

        mHandler.post(() -> {
            ClientMonitor client = mCurrentClient;
            if (client instanceof AuthenticationClient) {
                if (client.getToken() == token || !fromClient) {
                    if (DEBUG) Slog.v(getTag(), "Stopping client " + client.getOwnerString()
                            + ", fromClient: " + fromClient);
                    // If cancel was from BiometricService, it means the dialog was dismissed
                    // and authentication should be canceled.
                    client.stop(client.getToken() == token);
                } else {
                    if (DEBUG) Slog.v(getTag(), "Can't stop client " + client.getOwnerString()
                            + " since tokens don't match. fromClient: " + fromClient);
                }
            } else if (client != null) {
                if (DEBUG) Slog.v(getTag(), "Can't cancel non-authenticating client "
                        + client.getOwnerString());
            }
        });
    }

    protected void setActiveUserInternal(int userId) {
        mHandler.post(() -> {
            if (DEBUG) {
                Slog.d(getTag(), "setActiveUser(" + userId + ")");
            }
            updateActiveGroup(userId, null /* clientPackage */);
        });
    }

    protected void removeInternal(RemovalClient client) {
        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    protected void enumerateInternal(EnumerateClient client) {
        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    // Should be done on a handler thread - not on the Binder's thread.
    private void startAuthentication(AuthenticationClientImpl client, String opPackageName) {
        if (DEBUG) Slog.v(getTag(), "startAuthentication(" + opPackageName + ")");

        int lockoutMode = getLockoutMode();
        if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(getTag(), "In lockout mode(" + lockoutMode + ") ; disallowing authentication");
            int errorCode = lockoutMode == AuthenticationClient.LOCKOUT_TIMED ?
                    BiometricConstants.BIOMETRIC_ERROR_LOCKOUT :
                    BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
            if (!client.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */)) {
                Slog.w(getTag(), "Cannot send permanent lockout message to client");
            }
            return;
        }
        startClient(client, true /* initiatedByClient */);
    }

    protected void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback) {
        mHandler.post(() -> {
           final LockoutResetMonitor monitor = new LockoutResetMonitor(callback);
           if (!mLockoutMonitors.contains(monitor)) {
               mLockoutMonitors.add(monitor);
           }
        });
    }

    /**
     * Helper methods.
     */

    /**
     * @param opPackageName name of package for caller
     * @param requireForeground only allow this call while app is in the foreground
     * @return true if caller can use the biometric API
     */
    protected boolean canUseBiometric(String opPackageName, boolean requireForeground, int uid,
            int pid, int userId) {
        checkUseBiometricPermission();


        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            return true; // System process (BiometricService, etc) is always allowed
        }
        if (isKeyguard(opPackageName)) {
            return true; // Keyguard is always allowed
        }
        if (!isCurrentUserOrProfile(userId)) {
            Slog.w(getTag(), "Rejecting " + opPackageName + "; not a current user or profile");
            return false;
        }
        if (!checkAppOps(uid, opPackageName)) {
            Slog.w(getTag(), "Rejecting " + opPackageName + "; permission denied");
            return false;
        }

        if (requireForeground && !(isForegroundActivity(uid, pid) || isCurrentClient(
                opPackageName))) {
            Slog.w(getTag(), "Rejecting " + opPackageName + "; not in foreground");
            return false;
        }
        return true;
    }

    /**
     * @param opPackageName package of the caller
     * @return true if this is the same client currently using the biometric
     */
    private boolean isCurrentClient(String opPackageName) {
        return mCurrentClient != null && mCurrentClient.getOwnerString().equals(opPackageName);
    }

    /**
     * @return true if this is keyguard package
     */
    private boolean isKeyguard(String clientPackage) {
        return mKeyguardPackage.equals(clientPackage);
    }

    private boolean isForegroundActivity(int uid, int pid) {
        try {
            final List<ActivityManager.RunningAppProcessInfo> procs =
                    ActivityManager.getService().getRunningAppProcesses();
            if (procs == null) {
                Slog.e(getTag(), "Processes null, defaulting to true");
                return true;
            }

            int N = procs.size();
            for (int i = 0; i < N; i++) {
                ActivityManager.RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == pid && proc.uid == uid
                        && proc.importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(getTag(), "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    /**
     * Calls the HAL to switch states to the new task. If there's already a current task,
     * it calls cancel() and sets mPendingClient to begin when the current task finishes
     * ({@link BiometricConstants#BIOMETRIC_ERROR_CANCELED}).
     *
     * @param newClient the new client that wants to connect
     * @param initiatedByClient true for authenticate, remove and enroll
     */
    @VisibleForTesting
    void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = mCurrentClient;
        if (currentClient != null) {
            if (DEBUG) Slog.v(getTag(), "request stop current client " +
                    currentClient.getOwnerString());
            // This check only matters for FingerprintService, since enumerate may call back
            // multiple times.
            if (currentClient instanceof InternalEnumerateClient
                    || currentClient instanceof InternalRemovalClient) {
                // This condition means we're currently running internal diagnostics to
                // remove extra templates in the hardware and/or the software
                // TODO: design an escape hatch in case client never finishes
                if (newClient != null) {
                    Slog.w(getTag(), "Internal cleanup in progress but trying to start client "
                            + newClient.getClass().getSuperclass().getSimpleName()
                            + "(" + newClient.getOwnerString() + ")"
                            + ", initiatedByClient = " + initiatedByClient);
                }
            } else {
                currentClient.stop(initiatedByClient);

                // Only post the reset runnable for non-cleanup clients. Cleanup clients should
                // never be forcibly stopped since they ensure synchronization between HAL and
                // framework. Thus, we should instead just start the pending client once cleanup
                // finishes instead of using the reset runnable.
                mHandler.removeCallbacks(mResetClientState);
                mHandler.postDelayed(mResetClientState, CANCEL_TIMEOUT_LIMIT);
            }
            mPendingClient = newClient;
        } else if (newClient != null) {
            // For BiometricPrompt clients, do not start until
            // <Biometric>Service#startPreparedClient is called. BiometricService waits until all
            // modalities are ready before initiating authentication.
            if (newClient instanceof AuthenticationClient) {
                AuthenticationClient client = (AuthenticationClient) newClient;
                if (client.isBiometricPrompt()) {
                    if (DEBUG) Slog.v(getTag(), "Returning cookie: " + client.getCookie());
                    mCurrentClient = newClient;
                    if (mBiometricService == null) {
                        mBiometricService = IBiometricService.Stub.asInterface(
                                ServiceManager.getService(Context.BIOMETRIC_SERVICE));
                    }
                    try {
                        mBiometricService.onReadyForAuthentication(client.getCookie(),
                                client.getRequireConfirmation(), client.getTargetUserId());
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Remote exception", e);
                    }
                    return;
                }
            }

            // We are not a BiometricPrompt client, start the client immediately
            mCurrentClient = newClient;
            startCurrentClient(mCurrentClient.getCookie());
        }
    }

    protected void startCurrentClient(int cookie) {
        if (mCurrentClient == null) {
            Slog.e(getTag(), "Trying to start null client!");
            return;
        }

        if (DEBUG) Slog.v(getTag(), "starting client "
                + mCurrentClient.getClass().getSuperclass().getSimpleName()
                + "(" + mCurrentClient.getOwnerString() + ")"
                + " targetUserId: " + mCurrentClient.getTargetUserId()
                + " currentUserId: " + mCurrentUserId
                + " cookie: " + cookie + "/" + mCurrentClient.getCookie());

        if (cookie != mCurrentClient.getCookie()) {
            Slog.e(getTag(), "Mismatched cookie");
            return;
        }

        int status = mCurrentClient.start();
        if (status == 0) {
            notifyClientActiveCallbacks(true);
        } else {
            mCurrentClient.onError(getHalDeviceId(), BIOMETRIC_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            removeClient(mCurrentClient);
        }
    }

    protected void removeClient(ClientMonitor client) {
        if (client != null) {
            client.destroy();
            if (client != mCurrentClient && mCurrentClient != null) {
                Slog.w(getTag(), "Unexpected client: " + client.getOwnerString() + "expected: "
                        + mCurrentClient.getOwnerString());
            }
        }
        if (mCurrentClient != null) {
            if (DEBUG) Slog.v(getTag(), "Done with client: " + mCurrentClient.getOwnerString());
            mCurrentClient = null;
        }
        if (mPendingClient == null) {
            notifyClientActiveCallbacks(false);
        }
    }

    /**
     * Populates existing authenticator ids. To be used only during the start of the service.
     */
    protected void loadAuthenticatorIds() {
        // This operation can be expensive, so keep track of the elapsed time. Might need to move to
        // background if it takes too long.
        long t = System.currentTimeMillis();
        mAuthenticatorIds.clear();
        for (UserInfo user : UserManager.get(getContext()).getUsers(true /* excludeDying */)) {
            int userId = getUserOrWorkProfileId(null, user.id);
            if (!mAuthenticatorIds.containsKey(userId)) {
                updateActiveGroup(userId, null);
            }
        }

        t = System.currentTimeMillis() - t;
        if (t > 1000) {
            Slog.w(getTag(), "loadAuthenticatorIds() taking too long: " + t + "ms");
        }
    }

    /**
     * @param clientPackage the package of the caller
     * @return the profile id
     */
    protected int getUserOrWorkProfileId(String clientPackage, int userId) {
        if (!isKeyguard(clientPackage) && isWorkProfile(userId)) {
            return userId;
        }
        return getEffectiveUserId(userId);
    }

    protected boolean isRestricted() {
        // Only give privileged apps (like Settings) access to biometric info
        final boolean restricted = !hasPermission(getManageBiometricPermission());
        return restricted;
    }

    protected boolean hasPermission(String permission) {
        return getContext().checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    protected void checkPermission(String permission) {
        getContext().enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    protected boolean isCurrentUserOrProfile(int userId) {
        UserManager um = UserManager.get(mContext);
        if (um == null) {
            Slog.e(getTag(), "Unable to acquire UserManager");
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // Allow current user or profiles of the current user...
            for (int profileId : um.getEnabledProfileIds(ActivityManager.getCurrentUser())) {
                if (profileId == userId) {
                    return true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return false;
    }

    /***
     * @return authenticator id for the calling user
     */
    protected long getAuthenticatorId() {
        final int userId = getUserOrWorkProfileId(null /* clientPackage */,
                UserHandle.getCallingUserId());
        return mAuthenticatorIds.getOrDefault(userId, 0L);
    }

    /**
     * This method should be called upon connection to the daemon, and when user switches.
     * @param userId
     */
    protected void doTemplateCleanupForUser(int userId) {
        if (CLEANUP_UNKNOWN_TEMPLATES) {
            enumerateUser(userId);
        }
    }

    private void clearEnumerateState() {
        if (DEBUG) Slog.v(getTag(), "clearEnumerateState()");
        mUnknownHALTemplates.clear();
    }

    /**
     * Remove unknown templates from HAL
     */
    private void startCleanupUnknownHALTemplates() {
        if (!mUnknownHALTemplates.isEmpty()) {
            UserTemplate template = mUnknownHALTemplates.get(0);
            mUnknownHALTemplates.remove(template);
            boolean restricted = !hasPermission(getManageBiometricPermission());
            InternalRemovalClient client = new InternalRemovalClient(getContext(),
                    getDaemonWrapper(), mHalDeviceId, mToken, null /* listener */,
                    template.mIdentifier.getBiometricId(), 0 /* groupId */, template.mUserId,
                    restricted, getContext().getPackageName());
            removeInternal(client);
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    statsModality(),
                    BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_HAL);
        } else {
            clearEnumerateState();
            if (mPendingClient != null) {
                Slog.d(getTag(), "Enumerate finished, starting pending client");
                startClient(mPendingClient, false /* initiatedByClient */);
                mPendingClient = null;
            }
        }
    }

    private void enumerateUser(int userId) {
        if (DEBUG) Slog.v(getTag(), "Enumerating user(" + userId + ")");

        final boolean restricted = !hasPermission(getManageBiometricPermission());
        final List<? extends BiometricAuthenticator.Identifier> enrolledList =
                getEnrolledTemplates(userId);

        InternalEnumerateClient client = new InternalEnumerateClient(getContext(),
                getDaemonWrapper(), mHalDeviceId, mToken, null /* serviceListener */, userId,
                userId, restricted, getContext().getOpPackageName(), enrolledList,
                getBiometricUtils());
        enumerateInternal(client);
    }

    /**
     * This method is called when the user switches. Implementations should probably notify the
     * HAL.
     */
    protected void handleUserSwitching(int userId) {
        if (getCurrentClient() instanceof InternalRemovalClient
                || getCurrentClient() instanceof InternalEnumerateClient) {
            Slog.w(getTag(), "User switched while performing cleanup");
        }
        updateActiveGroup(userId, null);
        doTemplateCleanupForUser(userId);
    }

    protected void notifyLockoutResetMonitors() {
        for (int i = 0; i < mLockoutMonitors.size(); i++) {
            mLockoutMonitors.get(i).sendLockoutReset();
        }
    }

    private void userActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
    }

    /**
     * @param userId
     * @return true if this is a work profile
     */
    private boolean isWorkProfile(int userId) {
        UserInfo userInfo = null;
        final long token = Binder.clearCallingIdentity();
        try {
            userInfo = mUserManager.getUserInfo(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return userInfo != null && userInfo.isManagedProfile();
    }


    private int getEffectiveUserId(int userId) {
        UserManager um = UserManager.get(mContext);
        if (um != null) {
            final long callingIdentity = Binder.clearCallingIdentity();
            userId = um.getCredentialOwnerProfile(userId);
            Binder.restoreCallingIdentity(callingIdentity);
        } else {
            Slog.e(getTag(), "Unable to acquire UserManager");
        }
        return userId;
    }


    private void listenForUserSwitches() {
        try {
            ActivityManager.getService().registerUserSwitchObserver(
                    new SynchronousUserSwitchObserver() {
                        @Override
                        public void onUserSwitching(int newUserId) throws RemoteException {
                            mHandler.obtainMessage(MSG_USER_SWITCHING, newUserId, 0 /* unused */)
                                    .sendToTarget();
                        }
                    }, getTag());
        } catch (RemoteException e) {
            Slog.w(getTag(), "Failed to listen for user switching event" ,e);
        }
    }

    private void removeLockoutResetCallback(
            LockoutResetMonitor monitor) {
        mLockoutMonitors.remove(monitor);
    }
}
