/*
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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_FINGERPRINT;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceLockoutResetCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyStore;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint-related events.
 *
 * @hide
 */
public class FingerprintService extends SystemService implements IHwBinder.DeathRecipient {
    static final String TAG = "FingerprintService";
    static final boolean DEBUG = true;
    private static final boolean CLEANUP_UNUSED_FP = true;
    private static final String FP_DATA_DIR = "fpdata";
    private static final int MSG_USER_SWITCHING = 10;
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.fingerprint.ACTION_LOCKOUT_RESET";
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";

    private class PerformanceStats {
        int accept; // number of accepted fingerprints
        int reject; // number of rejected fingerprints
        int acquire; // total number of acquisitions. Should be >= accept+reject due to poor image
                     // acquisition in some cases (too fast, too slow, dirty sensor, etc.)
        int lockout; // total number of lockouts
        int permanentLockout; // total number of permanent lockouts
    }

    private final ArrayList<FingerprintServiceLockoutResetMonitor> mLockoutMonitors =
            new ArrayList<>();
    private final CopyOnWriteArrayList<IFingerprintClientActiveCallback> mClientActiveCallbacks =
            new CopyOnWriteArrayList<>();
    private final Map<Integer, Long> mAuthenticatorIds =
            Collections.synchronizedMap(new HashMap<>());
    private final AppOpsManager mAppOps;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30*1000;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;

    private static final long CANCEL_TIMEOUT_LIMIT = 3000; // max wait for onCancel() from HAL,in ms
    private final String mKeyguardPackage;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final FingerprintUtils mFingerprintUtils = FingerprintUtils.getInstance();
    private Context mContext;
    private long mHalDeviceId;
    private SparseBooleanArray mTimedLockoutCleared;
    private SparseIntArray mFailedAttempts;
    @GuardedBy("this")
    private IBiometricsFingerprint mDaemon;
    private IStatusBarService mStatusBarService;
    private final IActivityManager mActivityManager;
    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final UserManager mUserManager;
    private ClientMonitor mCurrentClient;
    private ClientMonitor mPendingClient;
    private PerformanceStats mPerformanceStats;

    private IBinder mToken = new Binder(); // used for internal FingerprintService enumeration
    private ArrayList<UserFingerprint> mUnknownFingerprints = new ArrayList<>(); // hw fingerprints

    private class UserFingerprint {
        Fingerprint f;
        int userId;
        public UserFingerprint(Fingerprint f, int userId) {
            this.f = f;
            this.userId = userId;
        }
    }

    // Normal fingerprint authentications are tracked by mPerformanceMap.
    private HashMap<Integer, PerformanceStats> mPerformanceMap = new HashMap<>();

    // Transactions that make use of CryptoObjects are tracked by mCryptoPerformaceMap.
    private HashMap<Integer, PerformanceStats> mCryptoPerformanceMap = new HashMap<>();

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
                final int user = intent.getIntExtra(KEY_LOCKOUT_RESET_USER, 0);
                resetFailedAttemptsForUser(false /* clearAttemptCounter */, user);
            }
        }
    };

    private final Runnable mResetFailedAttemptsForCurrentUserRunnable = new Runnable() {
        @Override
        public void run() {
            resetFailedAttemptsForUser(true /* clearAttemptCounter */,
                    ActivityManager.getCurrentUser());
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

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            try {
                if (!(mCurrentClient instanceof AuthenticationClient)) {
                    return;
                }
                if (isKeyguard(mCurrentClient.getOwnerString())) {
                    return; // Keyguard is always allowed
                }
                List<ActivityManager.RunningTaskInfo> runningTasks = mActivityManager.getTasks(1);
                if (!runningTasks.isEmpty()) {
                    if (runningTasks.get(0).topActivity.getPackageName()
                            != mCurrentClient.getOwnerString()) {
                        mCurrentClient.stop(false /* initiatedByClient */);
                        Slog.e(TAG, "Stopping background authentication");
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get running tasks", e);
            }
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
        mTimedLockoutCleared = new SparseBooleanArray();
        mFailedAttempts = new SparseIntArray();
        mStatusBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mActivityManager = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                .getService();
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.v(TAG, "fingerprint HAL died");
        MetricsLogger.count(mContext, "fingerprintd_died", 1);
        handleError(mHalDeviceId, FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                0 /*vendorCode */);
    }

    public synchronized IBiometricsFingerprint getFingerprintDaemon() {
        if (mDaemon == null) {
            Slog.v(TAG, "mDaemon was null, reconnect to fingerprint");
            try {
                mDaemon = IBiometricsFingerprint.getService();
            } catch (java.util.NoSuchElementException e) {
                // Service doesn't exist or cannot be opened. Logged below.
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get biometric interface", e);
            }
            if (mDaemon == null) {
                Slog.w(TAG, "fingerprint HIDL not available");
                return null;
            }

            mDaemon.asBinder().linkToDeath(this, 0);

            try {
                mHalDeviceId = mDaemon.setNotify(mDaemonCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open fingerprint HAL", e);
                mDaemon = null; // try again later!
            }

            if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
            if (mHalDeviceId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
                doFingerprintCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w(TAG, "Failed to open Fingerprint HAL!");
                MetricsLogger.count(mContext, "fingerprintd_openhal_error", 1);
                mDaemon = null;
            }
        }
        return mDaemon;
    }

    /** Populates existing authenticator ids. To be used only during the start of the service. */
    private void loadAuthenticatorIds() {
        // This operation can be expensive, so keep track of the elapsed time. Might need to move to
        // background if it takes too long.
        long t = System.currentTimeMillis();
        mAuthenticatorIds.clear();
        for (UserInfo user : UserManager.get(mContext).getUsers(true /* excludeDying */)) {
            int userId = getUserOrWorkProfileId(null, user.id);
            if (!mAuthenticatorIds.containsKey(userId)) {
                updateActiveGroup(userId, null);
            }
        }

        t = System.currentTimeMillis() - t;
        if (t > 1000) {
            Slog.w(TAG, "loadAuthenticatorIds() taking too long: " + t + "ms");
        }
    }

    /**
     * This method should be called upon connection to the daemon, and when user switches.
     * @param userId
     */
    private void doFingerprintCleanupForUser(int userId) {
        if (CLEANUP_UNUSED_FP) {
            enumerateUser(userId);
        }
    }

    private void clearEnumerateState() {
        if (DEBUG) Slog.v(TAG, "clearEnumerateState()");
        mUnknownFingerprints.clear();
    }

    private void enumerateUser(int userId) {
        if (DEBUG) Slog.v(TAG, "Enumerating user(" + userId + ")");
        boolean restricted = !hasPermission(MANAGE_FINGERPRINT);
        startEnumerate(mToken, userId, null, restricted, true /* internal */);
    }

    // Remove unknown fingerprints from hardware
    private void cleanupUnknownFingerprints() {
        if (!mUnknownFingerprints.isEmpty()) {
            UserFingerprint uf = mUnknownFingerprints.get(0);
            mUnknownFingerprints.remove(uf);
            boolean restricted = !hasPermission(MANAGE_FINGERPRINT);
            startRemove(mToken, uf.f.getFingerId(), uf.f.getGroupId(), uf.userId, null,
                    restricted, true /* internal */);
        } else {
            clearEnumerateState();
        }
    }

    protected void handleEnumerate(long deviceId, int fingerId, int groupId, int remaining) {
        ClientMonitor client = mCurrentClient;

        if ( !(client instanceof InternalRemovalClient) && !(client instanceof EnumerateClient) ) {
            return;
        }
        client.onEnumerationResult(fingerId, groupId, remaining);

        // All fingerprints in hardware for this user were enumerated
        if (remaining == 0) {
            if (client instanceof InternalEnumerateClient) {
                List<Fingerprint> unknownFingerprints =
                        ((InternalEnumerateClient) client).getUnknownFingerprints();

                if (!unknownFingerprints.isEmpty()) {
                    Slog.w(TAG, "Adding " + unknownFingerprints.size() +
                            " fingerprints for deletion");
                }
                for (Fingerprint f : unknownFingerprints) {
                    mUnknownFingerprints.add(new UserFingerprint(f, client.getTargetUserId()));
                }
                removeClient(client);
                cleanupUnknownFingerprints();
            } else {
                removeClient(client);
            }
        }
    }

    protected void handleError(long deviceId, int error, int vendorCode) {
        ClientMonitor client = mCurrentClient;
        if (client instanceof InternalRemovalClient || client instanceof InternalEnumerateClient) {
            clearEnumerateState();
        }
        if (client != null && client.onError(error, vendorCode)) {
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
        } else if (error == FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE) {
            // If we get HW_UNAVAILABLE, try to connect again later...
            Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
            synchronized (this) {
                mDaemon = null;
                mHalDeviceId = 0;
                mCurrentUserId = UserHandle.USER_NULL;
            }
        }
    }

    protected void handleRemoved(long deviceId, int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "Removed: fid=" + fingerId
                + ", gid=" + groupId
                + ", dev=" + deviceId
                + ", rem=" + remaining);

        ClientMonitor client = mCurrentClient;
        if (client != null && client.onRemoved(fingerId, groupId, remaining)) {
            removeClient(client);
            // When the last fingerprint of a group is removed, update the authenticator id
            if (!hasEnrolledFingerprints(groupId)) {
                updateActiveGroup(groupId, null);
            }
        }
        if (client instanceof InternalRemovalClient && !mUnknownFingerprints.isEmpty()) {
            cleanupUnknownFingerprints();
        } else if (client instanceof InternalRemovalClient){
            clearEnumerateState();
        }
    }

    protected void handleAuthenticated(long deviceId, int fingerId, int groupId,
            ArrayList<Byte> token) {
        ClientMonitor client = mCurrentClient;
        if (fingerId != 0) {
            // Ugh...
            final byte[] byteToken = new byte[token.size()];
            for (int i = 0; i < token.size(); i++) {
                byteToken[i] = token.get(i);
            }
            // Send to Keystore
            KeyStore.getInstance().addAuthToken(byteToken);
        }
        if (client != null && client.onAuthenticated(fingerId, groupId)) {
            removeClient(client);
        }
        if (fingerId != 0) {
            mPerformanceStats.accept++;
        } else {
            mPerformanceStats.reject++;
        }
    }

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

    protected void handleEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onEnrollResult(fingerId, groupId, remaining)) {
            removeClient(client);
            // When enrollment finishes, update this group's authenticator id, as the HAL has
            // already generated a new authenticator id when the new fingerprint is enrolled.
            updateActiveGroup(groupId, null);
        }
    }

    private void userActivity() {
        long now = SystemClock.uptimeMillis();
        mPowerManager.userActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
    }

    void handleUserSwitching(int userId) {
        if (mCurrentClient instanceof InternalRemovalClient
                || mCurrentClient instanceof InternalEnumerateClient) {
            Slog.w(TAG, "User switched while performing cleanup");
            removeClient(mCurrentClient);
            clearEnumerateState();
        }
        updateActiveGroup(userId, null);
        doFingerprintCleanupForUser(userId);
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
        if (mPendingClient == null) {
            notifyClientActiveCallbacks(false);
        }
    }

    private int getLockoutMode() {
        final int currentUser = ActivityManager.getCurrentUser();
        final int failedAttempts = mFailedAttempts.get(currentUser, 0);
        if (failedAttempts >= MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
            return AuthenticationClient.LOCKOUT_PERMANENT;
        } else if (failedAttempts > 0 &&
                mTimedLockoutCleared.get(currentUser, false) == false
                && (failedAttempts % MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED == 0)) {
            return AuthenticationClient.LOCKOUT_TIMED;
        }
        return AuthenticationClient.LOCKOUT_NONE;
    }

    private void scheduleLockoutResetForUser(int userId) {
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS,
                getLockoutResetIntentForUser(userId));
    }

    private void cancelLockoutResetForUser(int userId) {
        mAlarmManager.cancel(getLockoutResetIntentForUser(userId));
    }

    private PendingIntent getLockoutResetIntentForUser(int userId) {
        return PendingIntent.getBroadcast(mContext, userId,
                new Intent(ACTION_LOCKOUT_RESET).putExtra(KEY_LOCKOUT_RESET_USER, userId),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public long startPreEnroll(IBinder token) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no fingerprint HAL!");
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
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPostEnroll: no fingerprint HAL!");
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
     * Calls fingerprint HAL to switch states to the new task. If there's already a current task,
     * it calls cancel() and sets mPendingClient to begin when the current task finishes
     * ({@link FingerprintManager#FINGERPRINT_ERROR_CANCELED}).
     * @param newClient the new client that wants to connect
     * @param initiatedByClient true for authenticate, remove and enroll
     */
    private void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = mCurrentClient;
        if (currentClient != null) {
            if (DEBUG) Slog.v(TAG, "request stop current client " + currentClient.getOwnerString());
            if (currentClient instanceof InternalEnumerateClient ||
                    currentClient instanceof InternalRemovalClient) {
                // This condition means we're currently running internal diagnostics to
                // remove extra fingerprints in the hardware and/or the software
                // TODO: design an escape hatch in case client never finishes
                if (newClient != null) {
                    Slog.w(TAG, "Internal cleanup in progress but trying to start client "
                            + newClient.getClass().getSuperclass().getSimpleName()
                            + "(" + newClient.getOwnerString() + ")"
                            + ", initiatedByClient = " + initiatedByClient);
                }
            }
            else {
                currentClient.stop(initiatedByClient);
            }
            mPendingClient = newClient;
            mHandler.removeCallbacks(mResetClientState);
            mHandler.postDelayed(mResetClientState, CANCEL_TIMEOUT_LIMIT);
        } else if (newClient != null) {
            mCurrentClient = newClient;
            if (DEBUG) Slog.v(TAG, "starting client "
                    + newClient.getClass().getSuperclass().getSimpleName()
                    + "(" + newClient.getOwnerString() + ")"
                    + ", initiatedByClient = " + initiatedByClient);
            notifyClientActiveCallbacks(true);

            newClient.start();
        }
    }

    void startRemove(IBinder token, int fingerId, int groupId, int userId,
            IFingerprintServiceReceiver receiver, boolean restricted, boolean internal) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startRemove: no fingerprint HAL!");
            return;
        }

        if (internal) {
            Context context = getContext();
            InternalRemovalClient client = new InternalRemovalClient(context, mHalDeviceId,
                    token, receiver, fingerId, groupId, userId, restricted,
                    context.getOpPackageName()) {
                @Override
                public void notifyUserActivity() {

                }
                @Override
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client, true);
        }
        else {
            RemovalClient client = new RemovalClient(getContext(), mHalDeviceId, token,
                    receiver, fingerId, groupId, userId, restricted, token.toString()) {
                @Override
                public void notifyUserActivity() {
                    FingerprintService.this.userActivity();
                }

                @Override
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client, true);
        }
    }

    void startEnumerate(IBinder token, int userId,
        IFingerprintServiceReceiver receiver, boolean restricted, boolean internal) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startEnumerate: no fingerprint HAL!");
            return;
        }
        if (internal) {
            List<Fingerprint> enrolledList = getEnrolledFingerprints(userId);
            Context context = getContext();
            InternalEnumerateClient client = new InternalEnumerateClient(context, mHalDeviceId,
                    token, receiver, userId, userId, restricted, context.getOpPackageName(),
                    enrolledList) {
                @Override
                public void notifyUserActivity() {

                }

                @Override
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client, true);
        }
        else {
            EnumerateClient client = new EnumerateClient(getContext(), mHalDeviceId, token,
                    receiver, userId, userId, restricted, token.toString()) {
                @Override
                public void notifyUserActivity() {
                    FingerprintService.this.userActivity();
                }

                @Override
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client, true);
        }
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
        if (um == null) {
            Slog.e(TAG, "Unable to acquire UserManager");
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

    private boolean isForegroundActivity(int uid, int pid) {
        try {
            List<RunningAppProcessInfo> procs =
                    ActivityManager.getService().getRunningAppProcesses();
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
            int pid, int userId) {
        if (getContext().checkCallingPermission(USE_FINGERPRINT)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermission(USE_BIOMETRIC);
        }

        if (isKeyguard(opPackageName)) {
            return true; // Keyguard is always allowed
        }
        if (!isCurrentUserOrProfile(userId)) {
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

    private void notifyClientActiveCallbacks(boolean isActive) {
        List<IFingerprintClientActiveCallback> callbacks = mClientActiveCallbacks;
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onClientActiveChanged(isActive);
            } catch (RemoteException re) {
                // If the remote is dead, stop notifying it
                mClientActiveCallbacks.remove(callbacks.get(i));
            }
        }
    }

    private void startAuthentication(IBinder token, long opId, int callingUserId, int groupId,
                IFingerprintServiceReceiver receiver, int flags, boolean restricted,
                String opPackageName, Bundle bundle, IBiometricPromptReceiver dialogReceiver) {
        updateActiveGroup(groupId, opPackageName);

        if (DEBUG) Slog.v(TAG, "startAuthentication(" + opPackageName + ")");

        AuthenticationClient client = new AuthenticationClient(getContext(), mHalDeviceId, token,
                receiver, mCurrentUserId, groupId, opId, restricted, opPackageName, bundle,
                dialogReceiver, mStatusBarService) {
            @Override
            public void onStart() {
                try {
                    mActivityManager.registerTaskStackListener(mTaskStackListener);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Could not register task stack listener", e);
                }
            }

            @Override
            public void onStop() {
                try {
                    mActivityManager.unregisterTaskStackListener(mTaskStackListener);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Could not unregister task stack listener", e);
                }
            }

            @Override
            public int handleFailedAttempt() {
                final int currentUser = ActivityManager.getCurrentUser();
                mFailedAttempts.put(currentUser, mFailedAttempts.get(currentUser, 0) + 1);
                mTimedLockoutCleared.put(ActivityManager.getCurrentUser(), false);
                final int lockoutMode = getLockoutMode();
                if (lockoutMode == AuthenticationClient.LOCKOUT_PERMANENT) {
                    mPerformanceStats.permanentLockout++;
                } else if (lockoutMode == AuthenticationClient.LOCKOUT_TIMED) {
                    mPerformanceStats.lockout++;
                }

                // Failing multiple times will continue to push out the lockout time
                if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
                    scheduleLockoutResetForUser(currentUser);
                    return lockoutMode;
                }
                return AuthenticationClient.LOCKOUT_NONE;
            }

            @Override
            public void resetFailedAttempts() {
                FingerprintService.this.resetFailedAttemptsForUser(true /* clearAttemptCounter */,
                        ActivityManager.getCurrentUser());
            }

            @Override
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }

            @Override
            public IBiometricsFingerprint getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }
        };

        int lockoutMode = getLockoutMode();
        if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(TAG, "In lockout mode(" + lockoutMode +
                    ") ; disallowing authentication");
            int errorCode = lockoutMode == AuthenticationClient.LOCKOUT_TIMED ?
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT :
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
            if (!client.onError(errorCode, 0 /* vendorCode */)) {
                Slog.w(TAG, "Cannot send permanent lockout message to client");
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
            public IBiometricsFingerprint getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }

            @Override
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }
        };
        startClient(client, true /* initiatedByClient */);
    }

    // attempt counter should only be cleared when Keyguard goes away or when
    // a fingerprint is successfully authenticated
    protected void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {
        if (DEBUG && getLockoutMode() != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(TAG, "Reset fingerprint lockout, clearAttemptCounter=" + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            mFailedAttempts.put(userId, 0);
        }
        mTimedLockoutCleared.put(userId, true);
        // If we're asked to reset failed attempts externally (i.e. from Keyguard),
        // the alarm might still be pending; remove it.
        cancelLockoutResetForUser(userId);
        notifyLockoutResetMonitors();
    }

    private class FingerprintServiceLockoutResetMonitor implements IBinder.DeathRecipient {

        private static final long WAKELOCK_TIMEOUT_MS = 2000;
        private final IFingerprintServiceLockoutResetCallback mCallback;
        private final WakeLock mWakeLock;

        public FingerprintServiceLockoutResetMonitor(
                IFingerprintServiceLockoutResetCallback callback) {
            mCallback = callback;
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "lockout reset callback");
            try {
                mCallback.asBinder().linkToDeath(FingerprintServiceLockoutResetMonitor.this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath", e);
            }
        }

        public void sendLockoutReset() {
            if (mCallback != null) {
                try {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    mCallback.onLockoutReset(mHalDeviceId, new IRemoteCallback.Stub() {

                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            releaseWakelock();
                        }
                    });
                } catch (DeadObjectException e) {
                    Slog.w(TAG, "Death object while invoking onLockoutReset: ", e);
                    mHandler.post(mRemoveCallbackRunnable);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to invoke onLockoutReset: ", e);
                    releaseWakelock();
                }
            }
        }

        private final Runnable mRemoveCallbackRunnable = new Runnable() {
            @Override
            public void run() {
                releaseWakelock();
                removeLockoutResetCallback(FingerprintServiceLockoutResetMonitor.this);
            }
        };

        @Override
        public void binderDied() {
            Slog.e(TAG, "Lockout reset callback binder died");
            mHandler.post(mRemoveCallbackRunnable);
        }

        private void releaseWakelock() {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    private IBiometricsFingerprintClientCallback mDaemonCallback =
            new IBiometricsFingerprintClientCallback.Stub() {

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
        public void onAcquired(final long deviceId, final int acquiredInfo, final int vendorCode) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAcquired(deviceId, acquiredInfo, vendorCode);
                }
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int fingerId, final int groupId,
                ArrayList<Byte> token) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAuthenticated(deviceId, fingerId, groupId, token);
                }
            });
        }

        @Override
        public void onError(final long deviceId, final int error, final int vendorCode) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleError(deviceId, error, vendorCode);
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, final int fingerId, final int groupId, final int remaining) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleRemoved(deviceId, fingerId, groupId, remaining);
                }
            });
        }

        @Override
        public void onEnumerate(final long deviceId, final int fingerId, final int groupId,
                final int remaining) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleEnumerate(deviceId, fingerId, groupId, remaining);
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
            final int limit = mContext.getResources().getInteger(
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
                final String opPackageName, final Bundle bundle,
                final IBiometricPromptReceiver dialogReceiver) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();
            final boolean restricted = isRestricted();

            if (!canUseFingerprint(opPackageName, true /* foregroundOnly */, callingUid, callingPid,
                    callingUserId)) {
                if (DEBUG) Slog.v(TAG, "authenticate(): reject " + opPackageName);
                return;
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
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
                            flags, restricted, opPackageName, bundle, dialogReceiver);
                }
            });
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();

            if (!canUseFingerprint(opPackageName, true /* foregroundOnly */, callingUid, callingPid,
                    callingUserId)) {
                if (DEBUG) Slog.v(TAG, "cancelAuthentication(): reject " + opPackageName);
                return;
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
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
                    startRemove(token, fingerId, groupId, userId, receiver,
                            restricted, false /* internal */);
                }
            });
        }

        @Override // Binder call
        public void enumerate(final IBinder token, final int userId,
            final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT); // TODO: Maybe have another permission
            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startEnumerate(token, userId, receiver, restricted, false /* internal */);
                }
            });
        }

        @Override // Binder call
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                IBiometricsFingerprint daemon = getFingerprintDaemon();
                return daemon != null && mHalDeviceId != 0;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
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
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }

            return FingerprintService.this.getEnrolledFingerprints(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!canUseFingerprint(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
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
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

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

        @Override // Binder call
        public void resetTimeout(byte [] token) {
            checkPermission(RESET_FINGERPRINT_LOCKOUT);
            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(mResetFailedAttemptsForCurrentUserRunnable);
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

        @Override
        public boolean isClientActive() {
            checkPermission(MANAGE_FINGERPRINT);
            synchronized(FingerprintService.this) {
                return (mCurrentClient != null) || (mPendingClient != null);
            }
        }

        @Override
        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.add(callback);
        }

        @Override
        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            checkPermission(MANAGE_FINGERPRINT);
            mClientActiveCallbacks.remove(callback);
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
                set.put("permanentLockout", (stats != null) ? stats.permanentLockout : 0);
                // cryptoStats measures statistics about secure fingerprint transactions
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
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();

            final long userToken = proto.start(FingerprintServiceDumpProto.USERS);

            proto.write(FingerprintUserStatsProto.USER_ID, userId);
            proto.write(FingerprintUserStatsProto.NUM_FINGERPRINTS,
                    mFingerprintUtils.getFingerprintsForUser(mContext, userId).size());

            // Normal fingerprint authentications (e.g. lockscreen)
            final PerformanceStats normal = mPerformanceMap.get(userId);
            if (normal != null) {
                final long countsToken = proto.start(FingerprintUserStatsProto.NORMAL);
                proto.write(PerformanceStatsProto.ACCEPT, normal.accept);
                proto.write(PerformanceStatsProto.REJECT, normal.reject);
                proto.write(PerformanceStatsProto.ACQUIRE, normal.acquire);
                proto.write(PerformanceStatsProto.LOCKOUT, normal.lockout);
                proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, normal.permanentLockout);
                proto.end(countsToken);
            }

            // Statistics about secure fingerprint transactions (e.g. to unlock password
            // storage, make secure purchases, etc.)
            final PerformanceStats crypto = mCryptoPerformanceMap.get(userId);
            if (crypto != null) {
                final long countsToken = proto.start(FingerprintUserStatsProto.CRYPTO);
                proto.write(PerformanceStatsProto.ACCEPT, crypto.accept);
                proto.write(PerformanceStatsProto.REJECT, crypto.reject);
                proto.write(PerformanceStatsProto.ACQUIRE, crypto.acquire);
                proto.write(PerformanceStatsProto.LOCKOUT, crypto.lockout);
                proto.write(PerformanceStatsProto.PERMANENT_LOCKOUT, crypto.permanentLockout);
                proto.end(countsToken);
            }

            proto.end(userToken);
        }
        proto.flush();
        mPerformanceMap.clear();
        mCryptoPerformanceMap.clear();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        SystemServerInitThreadPool.get().submit(this::getFingerprintDaemon, TAG + ".onStart");
        listenForUserSwitches();
    }

    private void updateActiveGroup(int userId, String clientPackage) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();

        if (daemon != null) {
            try {
                userId = getUserOrWorkProfileId(clientPackage, userId);
                if (userId != mCurrentUserId) {
                    File baseDir;
                    if (Build.VERSION.FIRST_SDK_INT <= Build.VERSION_CODES.O_MR1) {
                        baseDir = Environment.getUserSystemDirectory(userId);
                    } else {
                        baseDir = Environment.getDataVendorDeDirectory(userId);
                    }

                    File fpDir = new File(baseDir, FP_DATA_DIR);
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

                    daemon.setActiveGroup(userId, fpDir.getAbsolutePath());
                    mCurrentUserId = userId;
                }
                mAuthenticatorIds.put(userId,
                        hasEnrolledFingerprints(userId) ? daemon.getAuthenticatorId() : 0L);
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
        UserInfo userInfo = null;
        final long token = Binder.clearCallingIdentity();
        try {
            userInfo = mUserManager.getUserInfo(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return userInfo != null && userInfo.isManagedProfile();
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
                }, TAG);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to listen for user switching event" ,e);
        }
    }

    /***
     * @param opPackageName the name of the calling package
     * @return authenticator id for the calling user
     */
    public long getAuthenticatorId(String opPackageName) {
        final int userId = getUserOrWorkProfileId(opPackageName, UserHandle.getCallingUserId());
        return mAuthenticatorIds.getOrDefault(userId, 0L);
    }
}
