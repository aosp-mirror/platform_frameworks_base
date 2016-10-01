/**
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.fingerprint;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.fingerprint.IFingerprintServiceLockoutResetCallback;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintDaemon;
import android.hardware.fingerprint.IFingerprintDaemonCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.USE_FINGERPRINT;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint -related events.
 *
 * @hide
 */
public class FingerprintService extends SystemService implements IBinder.DeathRecipient {
    static final String TAG = "FingerprintService";
    static final boolean DEBUG = true;
    private static final String FP_DATA_DIR = "fpdata";
    private static final String FINGERPRINTD = "android.hardware.fingerprint.IFingerprintDaemon";
    private static final int MSG_USER_SWITCHING = 10;
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.fingerprint.ACTION_LOCKOUT_RESET";

    private class PerformanceStats {
        int accept; // number of accepted fingerprints
        int reject; // number of rejected fingerprints
        int acquire; // total number of acquisitions. Should be >= accept+reject due to poor image
                     // acquisition in some cases (too fast, too slow, dirty sensor, etc.)
        int lockout; // total number of lockouts
    }

    private final ArrayList<FingerprintServiceLockoutResetMonitor> mLockoutMonitors =
            new ArrayList<>();
    private final AppOpsManager mAppOps;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30*1000;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long CANCEL_TIMEOUT_LIMIT = 3000; // max wait for onCancel() from HAL,in ms
    private final String mKeyguardPackage;
    private int mCurrentUserId = UserHandle.USER_CURRENT;
    private final FingerprintUtils mFingerprintUtils = FingerprintUtils.getInstance();
    private Context mContext;
    private long mHalDeviceId;
    private int mFailedAttempts;
    private IFingerprintDaemon mDaemon;
    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final UserManager mUserManager;
    private ClientMonitor mCurrentClient;
    private ClientMonitor mPendingClient;
    private long mCurrentAuthenticatorId;
    private PerformanceStats mPerformanceStats;

    // Normal fingerprint authentications are tracked by mPerformanceMap.
    private HashMap<Integer, PerformanceStats> mPerformanceMap
            = new HashMap<Integer, PerformanceStats>();

    // Transactions that make use of CryptoObjects are tracked by mCryptoPerformaceMap.
    private HashMap<Integer, PerformanceStats> mCryptoPerformanceMap
            = new HashMap<Integer, PerformanceStats>();

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHING:
                    handleUserSwitching(msg.arg1);
                    break;

                default:
                    Slog.w(TAG, "Unknown message:" + msg.what);
            }
        }
    };

    private final BroadcastReceiver mLockoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_LOCKOUT_RESET.equals(intent.getAction())) {
                resetFailedAttempts();
            }
        }
    };

    private final Runnable mResetFailedAttemptsRunnable = new Runnable() {
        @Override
        public void run() {
            resetFailedAttempts();
        }
    };

    private final Runnable mResetClientState = new Runnable() {
        @Override
        public void run() {
            // Warning: if we get here, the driver never confirmed our call to cancel the current
            // operation (authenticate, enroll, remove, enumerate, etc), which is
            // really bad.  The result will be a 3-second delay in starting each new client.
            // If you see this on a device, make certain the driver notifies with
            // {@link FingerprintManager#FINGERPRINT_ERROR_CANCEL} in response to cancel()
            // once it has successfully switched to the IDLE state in the fingerprint HAL.
            // Additionally,{@link FingerprintManager#FINGERPRINT_ERROR_CANCEL} should only be sent
            // in response to an actual cancel() call.
            Slog.w(TAG, "Client "
                    + (mCurrentClient != null ? mCurrentClient.getOwnerString() : "null")
                    + " failed to respond to cancel, starting client "
                    + (mPendingClient != null ? mPendingClient.getOwnerString() : "null"));
            mCurrentClient = null;
            startClient(mPendingClient, false);
        }
    };

    public FingerprintService(Context context) {
        super(context);
        mContext = context;
        mKeyguardPackage = ComponentName.unflattenFromString(context.getResources().getString(
                com.android.internal.R.string.config_keyguardComponent)).getPackageName();
        mAppOps = context.getSystemService(AppOpsManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mContext.registerReceiver(mLockoutReceiver, new IntentFilter(ACTION_LOCKOUT_RESET),
                RESET_FINGERPRINT_LOCKOUT, null /* handler */);
        mUserManager = UserManager.get(mContext);
    }

    @Override
    public void binderDied() {
        Slog.v(TAG, "fingerprintd died");
        MetricsLogger.count(mContext, "fingerprintd_died", 1);
        mDaemon = null;
        mCurrentUserId = UserHandle.USER_CURRENT;
        handleError(mHalDeviceId, FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE);
    }

    public IFingerprintDaemon getFingerprintDaemon() {
        if (mDaemon == null) {
            mDaemon = IFingerprintDaemon.Stub.asInterface(ServiceManager.getService(FINGERPRINTD));
            if (mDaemon != null) {
                try {
                    mDaemon.asBinder().linkToDeath(this, 0);
                    mDaemon.init(mDaemonCallback);
                    mHalDeviceId = mDaemon.openHal();
                    if (mHalDeviceId != 0) {
                        updateActiveGroup(ActivityManager.getCurrentUser(), null);
                    } else {
                        Slog.w(TAG, "Failed to open Fingerprint HAL!");
                        MetricsLogger.count(mContext, "fingerprintd_openhal_error", 1);
                        mDaemon = null;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to open fingeprintd HAL", e);
                    mDaemon = null; // try again later!
                }
            } else {
                Slog.w(TAG, "fingerprint service not available");
            }
        }
        return mDaemon;
    }

    protected void handleEnumerate(long deviceId, int[] fingerIds, int[] groupIds) {
        if (fingerIds.length != groupIds.length) {
            Slog.w(TAG, "fingerIds and groupIds differ in length: f[]="
                    + Arrays.toString(fingerIds) + ", g[]=" + Arrays.toString(groupIds));
            return;
        }
        if (DEBUG) Slog.w(TAG, "Enumerate: f[]=" + fingerIds + ", g[]=" + groupIds);
        // TODO: update fingerprint/name pairs
    }

    protected void handleError(long deviceId, int error) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onError(error)) {
            removeClient(client);
        }
        if (DEBUG) Slog.v(TAG, "handleError(client="
                + (client != null ? client.getOwnerString() : "null") + ", error = " + error + ")");
        // This is the magic code that starts the next client when the old client finishes.
        if (error == FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
            mHandler.removeCallbacks(mResetClientState);
            if (mPendingClient != null) {
                if (DEBUG) Slog.v(TAG, "start pending client " + mPendingClient.getOwnerString());
                startClient(mPendingClient, false);
                mPendingClient = null;
            }
        }
    }

    protected void handleRemoved(long deviceId, int fingerId, int groupId) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onRemoved(fingerId, groupId)) {
            removeClient(client);
        }
    }

    protected void handleAuthenticated(long deviceId, int fingerId, int groupId) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onAuthenticated(fingerId, groupId)) {
            removeClient(client);
        }
        if (fingerId != 0) {
            mPerformanceStats.accept++;
        } else {
            mPerformanceStats.reject++;
        }
    }

    protected void handleAcquired(long deviceId, int acquiredInfo) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onAcquired(acquiredInfo)) {
            removeClient(client);
        }
        if (mPerformanceStats != null && !inLockoutMode()
                && client instanceof AuthenticationClient) {
            // ignore enrollment acquisitions or acquisitions when we're locked out
            mPerformanceStats.acquire++;
        }
    }

    protected void handleEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onEnrollResult(fingerId, groupId, remaining)) {
            removeClient(client);
        }
    }

    private void userActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
    }

    void handleUserSwitching(int userId) {
        updateActiveGroup(userId, null);
    }

    private void removeClient(ClientMonitor client) {
        if (client != null) {
            client.destroy();
            if (client != mCurrentClient && mCurrentClient != null) {
                Slog.w(TAG, "Unexpected client: " + client.getOwnerString() + "expected: "
                        + mCurrentClient != null ? mCurrentClient.getOwnerString() : "null");
            }
        }
        if (mCurrentClient != null) {
            if (DEBUG) Slog.v(TAG, "Done with client: " + client.getOwnerString());
            mCurrentClient = null;
        }
    }

    private boolean inLockoutMode() {
        return mFailedAttempts >= MAX_FAILED_ATTEMPTS;
    }

    private void scheduleLockoutReset() {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS, getLockoutResetIntent());
    }

    private void cancelLockoutReset() {
        mAlarmManager.cancel(getLockoutResetIntent());
    }

    private PendingIntent getLockoutResetIntent() {
        return PendingIntent.getBroadcast(mContext, 0,
                new Intent(ACTION_LOCKOUT_RESET), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public long startPreEnroll(IBinder token) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no fingeprintd!");
            return 0;
        }
        try {
            return daemon.preEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPreEnroll failed", e);
        }
        return 0;
    }

    public int startPostEnroll(IBinder token) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPostEnroll: no fingeprintd!");
            return 0;
        }
        try {
            return daemon.postEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPostEnroll failed", e);
        }
        return 0;
    }

    /**
     * Calls fingerprintd to switch states to the new task. If there's already a current task,
     * it calls cancel() and sets mPendingClient to begin when the current task finishes
     * ({@link FingerprintManager#FINGERPRINT_ERROR_CANCELED}).
     * @param newClient the new client that wants to connect
     * @param initiatedByClient true for authenticate, remove and enroll
     */
    private void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = mCurrentClient;
        if (currentClient != null) {
            if (DEBUG) Slog.v(TAG, "request stop current client " + currentClient.getOwnerString());
            currentClient.stop(initiatedByClient);
            mPendingClient = newClient;
            mHandler.removeCallbacks(mResetClientState);
            mHandler.postDelayed(mResetClientState, CANCEL_TIMEOUT_LIMIT);
        } else if (newClient != null) {
            mCurrentClient = newClient;
            if (DEBUG) Slog.v(TAG, "starting client "
                    + newClient.getClass().getSuperclass().getSimpleName()
                    + "(" + newClient.getOwnerString() + ")"
                    + ", initiatedByClient = " + initiatedByClient + ")");
            newClient.start();
        }
    }

    void startRemove(IBinder token, int fingerId, int groupId, int userId,
            IFingerprintServiceReceiver receiver, boolean restricted) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startRemove: no fingeprintd!");
            return;
        }
        RemovalClient client = new RemovalClient(getContext(), mHalDeviceId, token,
                receiver, fingerId, groupId, userId, restricted, token.toString()) {
            @Override
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }

            @Override
            public IFingerprintDaemon getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }
        };
        startClient(client, true);
    }

    public List<Fingerprint> getEnrolledFingerprints(int userId) {
        return mFingerprintUtils.getFingerprintsForUser(mContext, userId);
    }

    public boolean hasEnrolledFingerprints(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return mFingerprintUtils.getFingerprintsForUser(mContext, userId).size() > 0;
    }

    boolean hasPermission(String permission) {
        return getContext().checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    void checkPermission(String permission) {
        getContext().enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    int getEffectiveUserId(int userId) {
        UserManager um = UserManager.get(mContext);
        if (um != null) {
            final long callingIdentity = Binder.clearCallingIdentity();
            userId = um.getCredentialOwnerProfile(userId);
            Binder.restoreCallingIdentity(callingIdentity);
        } else {
            Slog.e(TAG, "Unable to acquire UserManager");
        }
        return userId;
    }

    boolean isCurrentUserOrProfile(int userId) {
        UserManager um = UserManager.get(mContext);

        // Allow current user or profiles of the current user...
        for (int profileId : um.getEnabledProfileIds(userId)) {
            if (profileId == userId) {
                return true;
            }
        }
        return false;
    }

    private boolean isForegroundActivity(int uid, int pid) {
        try {
            List<RunningAppProcessInfo> procs =
                    ActivityManagerNative.getDefault().getRunningAppProcesses();
            int N = procs.size();
            for (int i = 0; i < N; i++) {
                RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == pid && proc.uid == uid
                        && proc.importance == IMPORTANCE_FOREGROUND) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    /**
     * @param opPackageName name of package for caller
     * @param requireForeground only allow this call while app is in the foreground
     * @return true if caller can use fingerprint API
     */
    private boolean canUseFingerprint(String opPackageName, boolean requireForeground, int uid,
            int pid) {
        checkPermission(USE_FINGERPRINT);
        if (isKeyguard(opPackageName)) {
            return true; // Keyguard is always allowed
        }
        if (!isCurrentUserOrProfile(UserHandle.getCallingUserId())) {
            Slog.w(TAG,"Rejecting " + opPackageName + " ; not a current user or profile");
            return false;
        }
        if (mAppOps.noteOp(AppOpsManager.OP_USE_FINGERPRINT, uid, opPackageName)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Rejecting " + opPackageName + " ; permission denied");
            return false;
        }
        if (requireForeground && !(isForegroundActivity(uid, pid) || currentClient(opPackageName))){
            Slog.w(TAG, "Rejecting " + opPackageName + " ; not in foreground");
            return false;
        }
        return true;
    }

    /**
     * @param opPackageName package of the caller
     * @return true if this is the same client currently using fingerprint
     */
    private boolean currentClient(String opPackageName) {
        return mCurrentClient != null && mCurrentClient.getOwnerString().equals(opPackageName);
    }

    /**
     * @param clientPackage
     * @return true if this is keyguard package
     */
    private boolean isKeyguard(String clientPackage) {
        return mKeyguardPackage.equals(clientPackage);
    }

    private void addLockoutResetMonitor(FingerprintServiceLockoutResetMonitor monitor) {
        if (!mLockoutMonitors.contains(monitor)) {
            mLockoutMonitors.add(monitor);
        }
    }

    private void removeLockoutResetCallback(
            FingerprintServiceLockoutResetMonitor monitor) {
        mLockoutMonitors.remove(monitor);
    }

    private void notifyLockoutResetMonitors() {
        for (int i = 0; i < mLockoutMonitors.size(); i++) {
            mLockoutMonitors.get(i).sendLockoutReset();
        }
    }

    private void startAuthentication(IBinder token, long opId, int callingUserId, int groupId,
                IFingerprintServiceReceiver receiver, int flags, boolean restricted,
                String opPackageName) {
        updateActiveGroup(groupId, opPackageName);

        if (DEBUG) Slog.v(TAG, "startAuthentication(" + opPackageName + ")");

        AuthenticationClient client = new AuthenticationClient(getContext(), mHalDeviceId, token,
                receiver, mCurrentUserId, groupId, opId, restricted, opPackageName) {
            @Override
            public boolean handleFailedAttempt() {
                mFailedAttempts++;
                if (mFailedAttempts == MAX_FAILED_ATTEMPTS) {
                    mPerformanceStats.lockout++;
                }
                if (inLockoutMode()) {
                    // Failing multiple times will continue to push out the lockout time.
                    scheduleLockoutReset();
                    return true;
                }
                return false;
            }

            @Override
            public void resetFailedAttempts() {
                FingerprintService.this.resetFailedAttempts();
            }

            @Override
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }

            @Override
            public IFingerprintDaemon getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }
        };

        if (inLockoutMode()) {
            Slog.v(TAG, "In lockout mode; disallowing authentication");
            // Don't bother starting the client. Just send the error message.
            if (!client.onError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT)) {
                Slog.w(TAG, "Cannot send timeout message to client");
            }
            return;
        }
        startClient(client, true /* initiatedByClient */);
    }

    private void startEnrollment(IBinder token, byte [] cryptoToken, int userId,
            IFingerprintServiceReceiver receiver, int flags, boolean restricted,
            String opPackageName) {
        updateActiveGroup(userId, opPackageName);

        final int groupId = userId; // default group for fingerprint enrollment

        EnrollClient client = new EnrollClient(getContext(), mHalDeviceId, token, receiver,
                userId, groupId, cryptoToken, restricted, opPackageName) {

            @Override
            public IFingerprintDaemon getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }

            @Override
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }
        };
        startClient(client, true /* initiatedByClient */);
    }

    protected void resetFailedAttempts() {
        if (DEBUG && inLockoutMode()) {
            Slog.v(TAG, "Reset fingerprint lockout");
        }
        mFailedAttempts = 0;
        // If we're asked to reset failed attempts externally (i.e. from Keyguard),
        // the alarm might still be pending; remove it.
        cancelLockoutReset();
        notifyLockoutResetMonitors();
    }

    private class FingerprintServiceLockoutResetMonitor {

        private final IFingerprintServiceLockoutResetCallback mCallback;

        public FingerprintServiceLockoutResetMonitor(
                IFingerprintServiceLockoutResetCallback callback) {
            mCallback = callback;
        }

        public void sendLockoutReset() {
            if (mCallback != null) {
                try {
                    mCallback.onLockoutReset(mHalDeviceId);
                } catch (DeadObjectException e) {
                    Slog.w(TAG, "Death object while invoking onLockoutReset: ", e);
                    mHandler.post(mRemoveCallbackRunnable);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to invoke onLockoutReset: ", e);
                }
            }
        }

        private final Runnable mRemoveCallbackRunnable = new Runnable() {
            @Override
            public void run() {
                removeLockoutResetCallback(FingerprintServiceLockoutResetMonitor.this);
            }
        };
    }

    private IFingerprintDaemonCallback mDaemonCallback = new IFingerprintDaemonCallback.Stub() {

        @Override
        public void onEnrollResult(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleEnrollResult(deviceId, fingerId, groupId, remaining);
                }
            });
        }

        @Override
        public void onAcquired(final long deviceId, final int acquiredInfo) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAcquired(deviceId, acquiredInfo);
                }
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int fingerId, final int groupId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAuthenticated(deviceId, fingerId, groupId);
                }
            });
        }

        @Override
        public void onError(final long deviceId, final int error) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleError(deviceId, error);
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, final int fingerId, final int groupId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleRemoved(deviceId, fingerId, groupId);
                }
            });
        }

        @Override
        public void onEnumerate(final long deviceId, final int[] fingerIds, final int[] groupIds) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleEnumerate(deviceId, fingerIds, groupIds);
                }
            });
        }
    };

    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override // Binder call
        public long preEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPreEnroll(token);
        }

        @Override // Binder call
        public int postEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPostEnroll(token);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int userId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName) {
            checkPermission(MANAGE_FINGERPRINT);
            final int limit =  mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_fingerprintMaxTemplatesPerUser);

            final int enrolled = FingerprintService.this.getEnrolledFingerprints(userId).size();
            if (enrolled >= limit) {
                Slog.w(TAG, "Too many fingerprints registered");
                return;
            }

            // Group ID is arbitrarily set to parent profile user ID. It just represents
            // the default fingerprints for the user.
            if (!isCurrentUserOrProfile(userId)) {
                return;
            }

            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startEnrollment(token, cryptoToken, userId, receiver, flags,
                            restricted, opPackageName);
                }
            });
        }

        private boolean isRestricted() {
            // Only give privileged apps (like Settings) access to fingerprint info
            final boolean restricted = !hasPermission(MANAGE_FINGERPRINT);
            return restricted;
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientMonitor client = mCurrentClient;
                    if (client instanceof EnrollClient && client.getToken() == token) {
                        client.stop(client.getToken() == token);
                    }
                }
            });
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long opId, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags,
                final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getCallingUserId();
            final int pid = Binder.getCallingPid();
            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!canUseFingerprint(opPackageName, true /* foregroundOnly */,
                            callingUid, pid)) {
                        if (DEBUG) Slog.v(TAG, "authenticate(): reject " + opPackageName);
                        return;
                    }

                    MetricsLogger.histogram(mContext, "fingerprint_token", opId != 0L ? 1 : 0);

                    // Get performance stats object for this user.
                    HashMap<Integer, PerformanceStats> pmap
                            = (opId == 0) ? mPerformanceMap : mCryptoPerformanceMap;
                    PerformanceStats stats = pmap.get(mCurrentUserId);
                    if (stats == null) {
                        stats = new PerformanceStats();
                        pmap.put(mCurrentUserId, stats);
                    }
                    mPerformanceStats = stats;

                    startAuthentication(token, opId, callingUserId, groupId, receiver,
                            flags, restricted, opPackageName);
                }
            });
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!canUseFingerprint(opPackageName, true /* foregroundOnly */, uid, pid)) {
                        if (DEBUG) Slog.v(TAG, "cancelAuthentication(): reject " + opPackageName);
                    } else {
                        ClientMonitor client = mCurrentClient;
                        if (client instanceof AuthenticationClient) {
                            if (client.getToken() == token) {
                                if (DEBUG) Slog.v(TAG, "stop client " + client.getOwnerString());
                                client.stop(client.getToken() == token);
                            } else {
                                if (DEBUG) Slog.v(TAG, "can't stop client "
                                        + client.getOwnerString() + " since tokens don't match");
                            }
                        } else if (client != null) {
                            if (DEBUG) Slog.v(TAG, "can't cancel non-authenticating client "
                                    + client.getOwnerString());
                        }
                    }
                }
            });
        }

        @Override // Binder call
        public void setActiveUser(final int userId) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateActiveGroup(userId, null);
                }
            });
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int groupId,
                final int userId, final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT); // TODO: Maybe have another permission
            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRemove(token, fingerId, groupId, userId, receiver, restricted);
                }
            });

        }

        @Override // Binder call
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid())) {
                return false;
            }
            return mHalDeviceId != 0;
        }

        @Override // Binder call
        public void rename(final int fingerId, final int groupId, final String name) {
            checkPermission(MANAGE_FINGERPRINT);
            if (!isCurrentUserOrProfile(groupId)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFingerprintUtils.renameFingerprintForUser(mContext, fingerId,
                            groupId, name);
                }
            });
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid())) {
                return Collections.emptyList();
            }
            if (!isCurrentUserOrProfile(userId)) {
                return Collections.emptyList();
            }

            return FingerprintService.this.getEnrolledFingerprints(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid())) {
                return false;
            }

            if (!isCurrentUserOrProfile(userId)) {
                return false;
            }
            return FingerprintService.this.hasEnrolledFingerprints(userId);
        }

        @Override // Binder call
        public long getAuthenticatorId(String opPackageName) {
            // In this method, we're not checking whether the caller is permitted to use fingerprint
            // API because current authenticator ID is leaked (in a more contrived way) via Android
            // Keystore (android.security.keystore package): the user of that API can create a key
            // which requires fingerprint authentication for its use, and then query the key's
            // characteristics (hidden API) which returns, among other things, fingerprint
            // authenticator ID which was active at key creation time.
            //
            // Reason: The part of Android Keystore which runs inside an app's process invokes this
            // method in certain cases. Those cases are not always where the developer demonstrates
            // explicit intent to use fingerprint functionality. Thus, to avoiding throwing an
            // unexpected SecurityException this method does not check whether its caller is
            // permitted to use fingerprint API.
            //
            // The permission check should be restored once Android Keystore no longer invokes this
            // method from inside app processes.

            return FingerprintService.this.getAuthenticatorId(opPackageName);
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump Fingerprint from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        @Override // Binder call
        public void resetTimeout(byte [] token) {
            checkPermission(RESET_FINGERPRINT_LOCKOUT);
            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(mResetFailedAttemptsRunnable);
        }

        @Override
        public void addLockoutResetCallback(final IFingerprintServiceLockoutResetCallback callback)
                throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    addLockoutResetMonitor(
                            new FingerprintServiceLockoutResetMonitor(callback));
                }
            });
        }
    }

    private void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                final int N = mFingerprintUtils.getFingerprintsForUser(mContext, userId).size();
                PerformanceStats stats = mPerformanceMap.get(userId);
                PerformanceStats cryptoStats = mCryptoPerformanceMap.get(userId);
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put("count", N);
                set.put("accept", (stats != null) ? stats.accept : 0);
                set.put("reject", (stats != null) ? stats.reject : 0);
                set.put("acquire", (stats != null) ? stats.acquire : 0);
                set.put("lockout", (stats != null) ? stats.lockout : 0);
                // cryptoStats measures statistics about secure fingerprint transactions
                // (e.g. to unlock password storage, make secure purchases, etc.)
                set.put("acceptCrypto", (cryptoStats != null) ? cryptoStats.accept : 0);
                set.put("rejectCrypto", (cryptoStats != null) ? cryptoStats.reject : 0);
                set.put("acquireCrypto", (cryptoStats != null) ? cryptoStats.acquire : 0);
                set.put("lockoutCrypto", (cryptoStats != null) ? cryptoStats.lockout : 0);
                sets.put(set);
            }

            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
        listenForUserSwitches();
    }

    private void updateActiveGroup(int userId, String clientPackage) {
        IFingerprintDaemon daemon = getFingerprintDaemon();

        if (daemon != null) {
            try {
                userId = getUserOrWorkProfileId(clientPackage, userId);
                if (userId != mCurrentUserId) {
                    final File systemDir = Environment.getUserSystemDirectory(userId);
                    final File fpDir = new File(systemDir, FP_DATA_DIR);
                    if (!fpDir.exists()) {
                        if (!fpDir.mkdir()) {
                            Slog.v(TAG, "Cannot make directory: " + fpDir.getAbsolutePath());
                            return;
                        }
                        // Calling mkdir() from this process will create a directory with our
                        // permissions (inherited from the containing dir). This command fixes
                        // the label.
                        if (!SELinux.restorecon(fpDir)) {
                            Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }
                    daemon.setActiveGroup(userId, fpDir.getAbsolutePath().getBytes());
                    mCurrentUserId = userId;
                }
                mCurrentAuthenticatorId = daemon.getAuthenticatorId();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    /**
     * @param clientPackage the package of the caller
     * @return the profile id
     */
    private int getUserOrWorkProfileId(String clientPackage, int userId) {
        if (!isKeyguard(clientPackage) && isWorkProfile(userId)) {
            return userId;
        }
        return getEffectiveUserId(userId);
    }

    /**
     * @param userId
     * @return true if this is a work profile
     */
    private boolean isWorkProfile(int userId) {
        UserInfo info = mUserManager.getUserInfo(userId);
        return info != null && info.isManagedProfile();
    }

    private void listenForUserSwitches() {
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                new SynchronousUserSwitchObserver() {
                    @Override
                    public void onUserSwitching(int newUserId) throws RemoteException {
                        mHandler.obtainMessage(MSG_USER_SWITCHING, newUserId, 0 /* unused */)
                                .sendToTarget();
                    }
                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        // Ignore.
                    }
                    @Override
                    public void onForegroundProfileSwitch(int newProfileId) {
                        // Ignore.
                    }
                }, TAG);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to listen for user switching event" ,e);
        }
    }

    /***
     * @param opPackageName the name of the calling package
     * @return authenticator id for the current user
     */
    public long getAuthenticatorId(String opPackageName) {
        return mCurrentAuthenticatorId;
    }

}
