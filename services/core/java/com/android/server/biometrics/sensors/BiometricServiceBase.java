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

package com.android.server.biometrics.sensors;

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
import android.hardware.biometrics.IBiometricService;
import android.hardware.fingerprint.Fingerprint;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;

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
public abstract class BiometricServiceBase<T> extends SystemService
        implements IHwBinder.DeathRecipient {

    protected static final boolean DEBUG = true;

    private static final int MSG_USER_SWITCHING = 10;
    private static final long CANCEL_TIMEOUT_LIMIT = 3000; // max wait for onCancel() from HAL,in ms

    private final Context mContext;
    private final String mKeyguardPackage;
    protected final IActivityTaskManager mActivityTaskManager;
    protected final BiometricTaskStackListener mTaskStackListener =
            new BiometricTaskStackListener();
    private final ResetClientStateRunnable mResetClientState = new ResetClientStateRunnable();

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

    protected final ClientMonitor.FinishCallback mClientFinishCallback =
            (clientMonitor, success) -> {
        if (clientMonitor instanceof RemovalConsumer) {
            // When the last biometric of a group is removed, update the authenticator id.
            // Note that 1) multiple ClientMonitors may be cause onRemoved (e.g. internal cleanup),
            // and 2) updateActiveGroup updates/relies on global state, so there's no good way to
            // compartmentalize this yet.
            final int userId = clientMonitor.getTargetUserId();
            if (!hasEnrolledBiometrics(userId)) {
                Slog.d(getTag(), "Last biometric removed for user: " + userId
                        + ", updating active group");
                updateActiveGroup(userId);
            }
        }

        removeClient(clientMonitor);
    };

    private IBiometricService mBiometricService;
    private ClientMonitor<T> mCurrentClient;
    private ClientMonitor<T> mPendingClient;
    private PerformanceTracker mPerformanceTracker;
    private int mSensorId;
    protected int mCurrentUserId = UserHandle.USER_NULL;

    /**
     * @return the log tag.
     */
    protected abstract String getTag();

    /**
     * @return a fresh reference to the biometric HAL
     */
    protected abstract T getDaemon();

    /**
     * @return the biometric utilities for a specific implementation.
     */
    protected abstract BiometricUtils getBiometricUtils();

    /**
     * @param userId
     * @return true if the enrollment limit has been reached.
     */
    protected abstract boolean hasReachedEnrollmentLimit(int userId);

    /**
     * Notifies the HAL that the user has changed.
     * @param userId
     */
    protected abstract void updateActiveGroup(int userId);

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
    protected abstract @LockoutTracker.LockoutMode int getLockoutMode(int userId);

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
                        ((AuthenticationClient) mCurrentClient).cancel();
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

            ClientMonitor<T> newClient = mPendingClient;
            mCurrentClient = null;
            mPendingClient = null;
            startClient(newClient, false);
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
        mActivityTaskManager = ActivityTaskManager.getService();
        mPerformanceTracker = PerformanceTracker.getInstanceForSensorId(getSensorId());
    }

    @Override
    public void onStart() {
        listenForUserSwitches();
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.e(getTag(), "HAL died");
        mPerformanceTracker.incrementHALDeathCount();
        mCurrentUserId = UserHandle.USER_NULL;

        // All client lifecycle must be managed on the handler.
        mHandler.post(() -> {
            Slog.e(getTag(), "Sending BIOMETRIC_ERROR_HW_UNAVAILABLE after HAL crash");
            handleError(BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
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

    protected void handleAcquired(int acquiredInfo, int vendorCode) {
        final ClientMonitor client = mCurrentClient;
        if (!(client instanceof AcquisitionClient)) {
            final String clientName = client != null ? client.getClass().getSimpleName() : "null";
            Slog.e(getTag(), "handleAcquired for non-acquire consumer: " + clientName);
            return;
        }

        final AcquisitionClient acquisitionClient = (AcquisitionClient) client;
        acquisitionClient.onAcquired(acquiredInfo, vendorCode);

        if (client instanceof AuthenticationClient) {
            final int userId = client.getTargetUserId();
            if (getLockoutMode(userId) == LockoutTracker.LOCKOUT_NONE) {
                mPerformanceTracker.incrementAcquireForUser(userId, client.isCryptoOperation());
            }
        }
    }

    protected void handleAuthenticated(BiometricAuthenticator.Identifier identifier,
            ArrayList<Byte> token) {
        final ClientMonitor client = mCurrentClient;
        if (!(client instanceof AuthenticationClient)) {
            final String clientName = client != null ? client.getClass().getSimpleName() : "null";
            Slog.e(getTag(), "handleAuthenticated for non-authentication client: " + clientName);
            return;
        }

        final AuthenticationClient authenticationClient = (AuthenticationClient) client;
        final boolean authenticated = identifier.getBiometricId() != 0;

        final int userId = authenticationClient.getTargetUserId();
        if (authenticationClient.isCryptoOperation()) {
            mPerformanceTracker.incrementCryptoAuthForUser(userId, authenticated);
        } else {
            mPerformanceTracker.incrementAuthForUser(userId, authenticated);
        }

        authenticationClient.onAuthenticated(identifier, authenticated, token);
    }

    protected void handleEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        final ClientMonitor client = mCurrentClient;
        if (!(client instanceof EnrollClient)) {
            final String clientName = client != null ? client.getClass().getSimpleName() : "null";
            Slog.e(getTag(), "handleEnrollResult for non-enroll client: " + clientName);
            return;
        }

        final EnrollClient enrollClient = (EnrollClient) client;

        if (enrollClient.onEnrollResult(identifier, remaining)) {
            removeClient(enrollClient);
            // When enrollment finishes, update this group's authenticator id, as the HAL has
            // already generated a new authenticator id when the new biometric is enrolled.
            if (identifier instanceof Fingerprint) {
                updateActiveGroup(((Fingerprint)identifier).getGroupId());
            }
        }
    }

    protected void handleError(int error, int vendorCode) {
        final ClientMonitor client = mCurrentClient;

        if (DEBUG) Slog.v(getTag(), "handleError(client="
                + (client != null ? client.getOwnerString() : "null") + ", error = " + error + ")");

        if (!(client instanceof ErrorConsumer)) {
            Slog.e(getTag(), "error received for non-ErrorConsumer");
            return;
        }

        final ErrorConsumer errorConsumer = (ErrorConsumer) client;
        errorConsumer.onError(error, vendorCode);

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

        final ClientMonitor client = mCurrentClient;
        if (!(client instanceof RemovalConsumer)) {
            final String clientName = client != null ? client.getClass().getSimpleName() : "null";
            Slog.e(getTag(), "handleRemoved for non-removal consumer: " + clientName);
            return;
        }

        final RemovalConsumer removalConsumer = (RemovalConsumer) client;
        removalConsumer.onRemoved(identifier, remaining);
    }

    protected void handleEnumerate(BiometricAuthenticator.Identifier identifier, int remaining) {
        final ClientMonitor client = mCurrentClient;
        if (!(client instanceof EnumerateConsumer)) {
            final String clientName = client != null ? client.getClass().getSimpleName() : "null";
            Slog.e(getTag(), "handleEnumerate for non-enumerate consumer: "
                    + clientName);
            return;
        }

        final EnumerateConsumer enumerateConsumer = (EnumerateConsumer) client;
        enumerateConsumer.onEnumerationResult(identifier, remaining);
    }

    /**
     * Calls from the Manager. These are still on the calling binder's thread.
     */

    protected void enrollInternal(EnrollClient<T> client, int userId) {
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
                ((EnrollClient) client).cancel();
            }
        });
    }

    protected void generateChallengeInternal(GenerateChallengeClient<T> client) {
        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    protected void revokeChallengeInternal(RevokeChallengeClient<T> client) {
        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    protected void authenticateInternal(AuthenticationClient<T> client, String opPackageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final int callingUserId = UserHandle.getCallingUserId();
        authenticateInternal(client, opPackageName, callingUid, callingPid, callingUserId);
    }

    protected void authenticateInternal(AuthenticationClient<T> client,
            String opPackageName, int callingUid, int callingPid, int callingUserId) {
        if (!canUseBiometric(opPackageName, true /* foregroundOnly */, callingUid, callingPid,
                callingUserId)) {
            if (DEBUG) Slog.v(getTag(), "authenticate(): reject " + opPackageName);
            return;
        }

        mHandler.post(() -> {
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
                    ((AuthenticationClient) client).cancel();
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

    protected void removeInternal(RemovalClient<T> client) {
        mHandler.post(() -> {
            startClient(client, true /* initiatedByClient */);
        });
    }

    protected void cleanupInternal(
            InternalCleanupClient<? extends BiometricAuthenticator.Identifier, T> client) {
        mHandler.post(() -> {
            if (DEBUG) {
                Slog.v(getTag(), "Cleaning up templates for user("
                        + client.getTargetUserId() + ")");
            }
            startClient(client, true /* initiatedByClient */);
        });
    }

    // Should be done on a handler thread - not on the Binder's thread.
    private void startAuthentication(AuthenticationClient<T> client, String opPackageName) {
        if (DEBUG) Slog.v(getTag(), "startAuthentication(" + opPackageName + ")");

        startClient(client, true /* initiatedByClient */);
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
    public boolean isKeyguard(String clientPackage) {
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
    protected void startClient(ClientMonitor<T> newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = mCurrentClient;
        if (currentClient != null) {
            if (DEBUG) Slog.v(getTag(), "request stop current client " +
                    currentClient.getOwnerString());
            if (currentClient instanceof InternalCleanupClient) {
                // This condition means we're currently running internal diagnostics to
                // remove extra templates in the hardware and/or the software
                // TODO: design an escape hatch in case client never finishes
                if (newClient != null) {
                    Slog.w(getTag(), "Internal cleanup in progress but trying to start client "
                            + newClient.getClass().getSuperclass().getSimpleName()
                            + "(" + newClient.getOwnerString() + ")"
                            + ", initiatedByClient = " + initiatedByClient);
                }
            } else if (currentClient instanceof Cancellable) {
                ((Cancellable) currentClient).cancel();

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
                        mBiometricService.onReadyForAuthentication(client.getCookie());
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

        if (DEBUG) Slog.v(getTag(), "Starting client "
                + mCurrentClient.getClass().getSimpleName()
                + "(" + mCurrentClient.getOwnerString() + ")"
                + " targetUserId: " + mCurrentClient.getTargetUserId()
                + " currentUserId: " + mCurrentUserId
                + " cookie: " + cookie + "/" + mCurrentClient.getCookie());

        if (cookie != mCurrentClient.getCookie()) {
            Slog.e(getTag(), "Mismatched cookie");
            return;
        }

        final T daemon = mCurrentClient.getFreshDaemon();
        if (daemon == null) {
            Slog.e(getTag(), "Daemon null, unable to start: "
                    + mCurrentClient.getClass().getSimpleName());
            mCurrentClient.unableToStart();
            mCurrentClient = null;
            return;
        }

        mCurrentClient.start(mClientFinishCallback);
        notifyClientActiveCallbacks(true);
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
            if (DEBUG) Slog.v(getTag(), "Done with client: "
                    + mCurrentClient.getClass().getSimpleName()
                    + "(" + mCurrentClient.getOwnerString() + ")");
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
            int userId = user.id;
            if (!mAuthenticatorIds.containsKey(userId)) {
                updateActiveGroup(userId);
            }
        }

        t = System.currentTimeMillis() - t;
        if (t > 1000) {
            Slog.w(getTag(), "loadAuthenticatorIds() taking too long: " + t + "ms");
        }
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

    /**
     * @return authenticator id for the calling user
     */
    protected long getAuthenticatorId(int callingUserId) {
        return mAuthenticatorIds.getOrDefault(callingUserId, 0L);
    }

    /**
     * This method should be called upon connection to the daemon, and when user switches.
     */
    protected abstract void doTemplateCleanupForUser(int userId);

    /**
     * This method is called when the user switches. Implementations should probably notify the
     * HAL.
     */
    protected void handleUserSwitching(int userId) {
        if (getCurrentClient() instanceof InternalCleanupClient) {
            Slog.w(getTag(), "User switched while performing cleanup");
        }
        updateActiveGroup(userId);
        doTemplateCleanupForUser(userId);
    }

    private void listenForUserSwitches() {
        try {
            ActivityManager.getService().registerUserSwitchObserver(
                    new SynchronousUserSwitchObserver() {
                        @Override
                        public void onUserSwitching(int newUserId) {
                            mHandler.obtainMessage(MSG_USER_SWITCHING, newUserId, 0 /* unused */)
                                    .sendToTarget();
                        }
                    }, getTag());
        } catch (RemoteException e) {
            Slog.w(getTag(), "Failed to listen for user switching event" ,e);
        }
    }
}
