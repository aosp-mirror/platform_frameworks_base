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
import static android.Manifest.permission.MANAGE_FACE;
import static android.Manifest.permission.RESET_FACE_LOCKOUT;
import static android.Manifest.permission.USE_BIOMETRIC;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceLockoutResetCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
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
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyStore;
import android.service.face.FaceActionStatsProto;
import android.service.face.FaceServiceDumpProto;
import android.service.face.FaceUserStatsProto;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
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

/**
 * A service to manage multiple clients that want to access the face HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * face -related events.
 *
 * @hide
 */
public class FaceService extends SystemService implements IHwBinder.DeathRecipient {
    static final String TAG = "FaceService";
    static final boolean DEBUG = true;
    private static final String FACE_DATA_DIR = "facedata";
    private static final int MSG_USER_SWITCHING = 10;
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.biometrics.face.ACTION_LOCKOUT_RESET";

    private class PerformanceStats {
        int accept; // number of accepted faces
        int reject; // number of rejected faces
        int acquire; // total number of acquisitions. Should be >= accept+reject due to poor image
                     // acquisition in some cases (too high, too low, poor gaze, etc.)
        int lockout; // total number of lockouts
        int permanentLockout; // total number of permanent lockouts
    }

    private final ArrayList<FaceServiceLockoutResetMonitor> mLockoutMonitors =
            new ArrayList<>();
    private final Map<Integer, Long> mAuthenticatorIds =
            Collections.synchronizedMap(new HashMap<>());
    private final AppOpsManager mAppOps;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30*1000;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;

    private static final long CANCEL_TIMEOUT_LIMIT_MS = 3000; // max wait for onCancel() from HAL,in ms
    private final String mKeyguardPackage;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private final FaceUtils mFaceUtils = FaceUtils.getInstance();
    private Context mContext;
    private long mHalDeviceId;
    private boolean mTimedLockoutCleared;
    private int mFailedAttempts;
    @GuardedBy("this")
    private IBiometricsFace mDaemon;
    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final UserManager mUserManager;
    private ClientMonitor mCurrentClient;
    private ClientMonitor mPendingClient;
    private PerformanceStats mPerformanceStats;


    private IBinder mToken = new Binder(); // used for internal FaceService enumeration

    private class UserFace {
        Face f;
        int userId;
        public UserFace(Face f, int userId) {
            this.f = f;
            this.userId = userId;
        }
    }

    // Normal face authentications are tracked by mPerformanceMap.
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
                resetFailedAttempts(false /* clearAttemptCounter */);
            }
        }
    };

    private final Runnable mResetFailedAttemptsRunnable = new Runnable() {
        @Override
        public void run() {
            resetFailedAttempts(true /* clearAttemptCounter */);
        }
    };

    private final Runnable mResetClientState = new Runnable() {
        @Override
        public void run() {
            // Warning: if we get here, the driver never confirmed our call to cancel the current
            // operation (authenticate, enroll, remove, enumerate, etc), which is
            // really bad.  The result will be a 3-second delay in starting each new client.
            // If you see this on a device, make certain the driver notifies with
            // {@link FaceAuthenticationManager#FACE_ERROR_CANCEL} in response to cancel()
            // once it has successfully switched to the IDLE state in the face HAL.
            // Additionally,{@link FaceAuthenticationManager#FACE_ERROR_CANCEL} should only be sent
            // in response to an actual cancel() call.
            Slog.w(TAG, "Client "
                    + (mCurrentClient != null ? mCurrentClient.getOwnerString() : "null")
                    + " failed to respond to cancel, starting client "
                    + (mPendingClient != null ? mPendingClient.getOwnerString() : "null"));

            mCurrentClient = null;
            startClient(mPendingClient, false);
        }
    };

    public FaceService(Context context) {
        super(context);
        mContext = context;
        mKeyguardPackage = ComponentName.unflattenFromString(context.getResources().getString(
                com.android.internal.R.string.config_keyguardComponent)).getPackageName();
        mAppOps = context.getSystemService(AppOpsManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mContext.registerReceiver(mLockoutReceiver, new IntentFilter(ACTION_LOCKOUT_RESET),
                RESET_FACE_LOCKOUT, null /* handler */);
        mUserManager = UserManager.get(mContext);
    }

    @Override
    public void serviceDied(long cookie) {
        Slog.v(TAG, "face HAL died");
        MetricsLogger.count(mContext, "faced_died", 1);
        handleError(mHalDeviceId, FaceManager.FACE_ERROR_HW_UNAVAILABLE,
                0 /*vendorCode */);
    }

    public synchronized IBiometricsFace getFaceDaemon() {
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

            if (DEBUG) Slog.v(TAG, "face HAL id: " + mHalDeviceId);
            if (mHalDeviceId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
            } else {
                Slog.w(TAG, "Failed to open Face HAL!");
                MetricsLogger.count(mContext, "faced_openhal_error", 1);
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

    protected void handleError(long deviceId, int error, int vendorCode) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onError(error, vendorCode)) {
            removeClient(client);
        }

        if (DEBUG) Slog.v(TAG, "handleError(client="
                + (client != null ? client.getOwnerString() : "null") + ", error = " + error + ")");
        // This is the magic code that starts the next client when the old client finishes.
        if (error == FaceManager.FACE_ERROR_CANCELED) {
            mHandler.removeCallbacks(mResetClientState);
            if (mPendingClient != null) {
                if (DEBUG) Slog.v(TAG, "start pending client " + mPendingClient.getOwnerString());
                startClient(mPendingClient, false);
                mPendingClient = null;
            }
        } else if (error == FaceManager.FACE_ERROR_HW_UNAVAILABLE) {
            // If we get HW_UNAVAILABLE, try to connect again later...
            Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
            synchronized (this) {
                mDaemon = null;
                mHalDeviceId = 0;
                mCurrentUserId = UserHandle.USER_NULL;
            }
        }
    }

    protected void handleRemoved(long deviceId, int faceId, int userId, int remaining) {
        if (DEBUG) Slog.w(TAG, "Removed: fid=" + faceId
                + ", uid=" + userId
                + ", dev=" + deviceId
                + ", rem=" + remaining);

        ClientMonitor client = mCurrentClient;
        if (client != null && client.onRemoved(faceId, remaining)) {
            removeClient(client);
        }
    }

    protected void handleAuthenticated(long deviceId, int faceId, int userId,
            ArrayList<Byte> token) {
        ClientMonitor client = mCurrentClient;
        if (faceId != 0) {
            // Ugh...
            final byte[] byteToken = new byte[token.size()];
            for (int i = 0; i < token.size(); i++) {
                byteToken[i] = token.get(i);
            }
            // Send to Keystore
            KeyStore.getInstance().addAuthToken(byteToken);
        }
        if (client != null && client.onAuthenticated(faceId)) {
            removeClient(client);
        }
        if (faceId != 0) {
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

    protected void handleEnrollResult(long deviceId, int faceId, int userId, int remaining) {
        ClientMonitor client = mCurrentClient;
        if (client != null && client.onEnrollResult(faceId, remaining)) {
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

    private int getLockoutMode() {
        if (mFailedAttempts >= MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
            return AuthenticationClient.LOCKOUT_PERMANENT;
        } else if (mFailedAttempts > 0 && mTimedLockoutCleared == false &&
                (mFailedAttempts % MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED == 0)) {
            return AuthenticationClient.LOCKOUT_TIMED;
        }
        return AuthenticationClient.LOCKOUT_NONE;
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
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no face HAL!");
            return 0;
        }
        try {
            return daemon.preEnroll().value;
        } catch (RemoteException e) {
            Slog.e(TAG, "startPreEnroll failed", e);
        }
        return 0;
    }

    public int startPostEnroll(IBinder token) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPostEnroll: no face HAL!");
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
     * Calls face HAL to switch states to the new task. If there's already a current task,
     * it calls cancel() and sets mPendingClient to begin when the current task finishes
     * ({@link FaceManager#FACE_ERROR_CANCELED}).
     * @param newClient the new client that wants to connect
     * @param initiatedByClient true for authenticate, remove and enroll
     */
    private void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = mCurrentClient;
        if (currentClient != null) {
            if (DEBUG) Slog.v(TAG, "request stop current client " + currentClient.getOwnerString());
            if (currentClient instanceof InternalRemovalClient) {
                // This condition means we're currently running internal diagnostics to
                // remove a face in the hardware and/or the software
                // TODO: design an escape hatch in case client never finishes
            }
            else {
                currentClient.stop(initiatedByClient);
            }
            mPendingClient = newClient;
            mHandler.removeCallbacks(mResetClientState);
            mHandler.postDelayed(mResetClientState, CANCEL_TIMEOUT_LIMIT_MS);
        } else if (newClient != null) {
            mCurrentClient = newClient;
            if (DEBUG) Slog.v(TAG, "starting client "
                    + newClient.getClass().getSuperclass().getSimpleName()
                    + "(" + newClient.getOwnerString() + ")"
                    + ", initiatedByClient = " + initiatedByClient + ")");
            newClient.start();
        }
    }

    void startRemove(IBinder token, int userId, IFaceServiceReceiver receiver, boolean restricted,
                     boolean internal) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startRemove: no face HAL!");
            return;
        }

        if (internal) {
            Context context = getContext();
            InternalRemovalClient client = new InternalRemovalClient(context, mHalDeviceId,
                    token, receiver, userId, restricted, context.getOpPackageName()) {
                @Override
                public void notifyUserActivity() {

                }
                @Override
                public IBiometricsFace getFaceDaemon() {
                    return FaceService.this.getFaceDaemon();
                }
            };
            startClient(client, true);
        }
        else {
            RemovalClient client = new RemovalClient(getContext(), mHalDeviceId, token,
                    receiver, userId, restricted, token.toString()) {
                @Override
                public void notifyUserActivity() {
                    FaceService.this.userActivity();
                }

                @Override
                public IBiometricsFace getFaceDaemon() {
                    return FaceService.this.getFaceDaemon();
                }
            };
            startClient(client, true);
        }
    }

    /*
     * @hide
     */
    public Face getEnrolledFace(int userId) {
        return mFaceUtils.getFaceForUser(mContext, userId);
    }

    public boolean hasEnrolledFace(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission(INTERACT_ACROSS_USERS);
        }
        return mFaceUtils.getFaceForUser(mContext, userId) != null;
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
     * @return true if caller can use face API
     */
    private boolean canUseFace(String opPackageName, boolean requireForeground, int uid,
            int pid, int userId) {
        checkPermission(USE_BIOMETRIC);
        if (isKeyguard(opPackageName)) {
            return true; // Keyguard is always allowed
        }
        if (!isCurrentUserOrProfile(userId)) {
            Slog.w(TAG,"Rejecting " + opPackageName + " ; not a current user or profile");
            return false;
        }
        if (mAppOps.noteOp(AppOpsManager.OP_USE_FACE, uid, opPackageName)
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
     * @return true if this is the same client currently using face
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

    private void addLockoutResetMonitor(FaceServiceLockoutResetMonitor monitor) {
        if (!mLockoutMonitors.contains(monitor)) {
            mLockoutMonitors.add(monitor);
        }
    }

    private void removeLockoutResetCallback(
            FaceServiceLockoutResetMonitor monitor) {
        mLockoutMonitors.remove(monitor);
    }

    private void notifyLockoutResetMonitors() {
        for (int i = 0; i < mLockoutMonitors.size(); i++) {
            mLockoutMonitors.get(i).sendLockoutReset();
        }
    }

    private void startAuthentication(IBinder token, long opId, int callingUserId,
                IFaceServiceReceiver receiver, int flags, boolean restricted,
                String opPackageName) {
        updateActiveGroup(callingUserId, opPackageName);

        if (DEBUG) Slog.v(TAG, "startAuthentication(" + opPackageName + ")");

        AuthenticationClient client = new AuthenticationClient(getContext(), mHalDeviceId, token,
                receiver, mCurrentUserId, opId, restricted, opPackageName) {
            @Override
            public int handleFailedAttempt() {
                mFailedAttempts++;
                mTimedLockoutCleared = false;
                final int lockoutMode = getLockoutMode();
                if (lockoutMode == AuthenticationClient.LOCKOUT_PERMANENT) {
                    mPerformanceStats.permanentLockout++;
                } else if (lockoutMode == AuthenticationClient.LOCKOUT_TIMED) {
                    mPerformanceStats.lockout++;
                }

                // Failing multiple times will continue to push out the lockout time
                if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
                    scheduleLockoutReset();
                    return lockoutMode;
                }
                return AuthenticationClient.LOCKOUT_NONE;
            }

            @Override
            public void resetFailedAttempts() {
                FaceService.this.resetFailedAttempts(true /* clearAttemptCounter */);
            }

            @Override
            public void notifyUserActivity() {
                FaceService.this.userActivity();
            }

            @Override
            public IBiometricsFace getFaceDaemon() {
                return FaceService.this.getFaceDaemon();
            }
        };

        int lockoutMode = getLockoutMode();
        if (lockoutMode != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(TAG, "In lockout mode(" + lockoutMode +
                    ") ; disallowing authentication");
            int errorCode = lockoutMode == AuthenticationClient.LOCKOUT_TIMED ?
                    FaceManager.FACE_ERROR_LOCKOUT :
                    FaceManager.FACE_ERROR_LOCKOUT_PERMANENT;
            if (!client.onError(errorCode, 0 /* vendorCode */)) {
                Slog.w(TAG, "Cannot send permanent lockout message to client");
            }
            return;
        }
        startClient(client, true /* initiatedByClient */);
    }

    private void startEnrollment(IBinder token, byte [] cryptoToken, int userId,
            IFaceServiceReceiver receiver, int flags, boolean restricted,
            String opPackageName) {
        updateActiveGroup(userId, opPackageName);

        EnrollClient client = new EnrollClient(getContext(), mHalDeviceId, token, receiver,
                userId, cryptoToken, restricted, opPackageName) {

            @Override
            public IBiometricsFace getFaceDaemon() {
                return FaceService.this.getFaceDaemon();
            }

            @Override
            public void notifyUserActivity() {
                FaceService.this.userActivity();
            }
        };
        startClient(client, true /* initiatedByClient */);
    }

    // attempt counter should only be cleared when Keyguard goes away or when
    // a face is successfully authenticated
    protected void resetFailedAttempts(boolean clearAttemptCounter) {
        if (DEBUG && getLockoutMode() != AuthenticationClient.LOCKOUT_NONE) {
            Slog.v(TAG, "Reset face lockout, clearAttemptCounter=" + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            mFailedAttempts = 0;
        }
        mTimedLockoutCleared = true;
        // If we're asked to reset failed attempts externally (i.e. from Keyguard),
        // the alarm might still be pending; remove it.
        cancelLockoutReset();
        notifyLockoutResetMonitors();
    }

    private class FaceServiceLockoutResetMonitor {

        private static final long WAKELOCK_TIMEOUT_MS = 2000;
        private final IFaceServiceLockoutResetCallback mCallback;
        private final WakeLock mWakeLock;

        public FaceServiceLockoutResetMonitor(
                IFaceServiceLockoutResetCallback callback) {
            mCallback = callback;
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "lockout reset callback");
        }

        public void sendLockoutReset() {
            if (mCallback != null) {
                try {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    mCallback.onLockoutReset(mHalDeviceId, new IRemoteCallback.Stub() {

                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            if (mWakeLock.isHeld()) {
                                mWakeLock.release();
                            }
                        }
                    });
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
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                removeLockoutResetCallback(FaceServiceLockoutResetMonitor.this);
            }
        };
    }

    private IBiometricsFaceClientCallback mDaemonCallback =
            new IBiometricsFaceClientCallback.Stub() {

        @Override
        public void onEnrollResult(final long deviceId, int faceId, int userId, int remaining) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleEnrollResult(deviceId, faceId, userId, remaining);
                }
            });
        }

        @Override
        public void onAcquired(final long deviceId, final int userId, final int acquiredInfo,
                final int vendorCode) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAcquired(deviceId, acquiredInfo, vendorCode);
                }
            });
        }

        @Override
        public void onAuthenticated(final long deviceId, final int faceId, final int userId,
                ArrayList<Byte> token) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAuthenticated(deviceId, faceId, userId, token);
                }
            });
        }

        @Override
        public void onError(final long deviceId, final int userId, final int error,
                final int vendorCode) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleError(deviceId, error, vendorCode);
                }
            });
        }

        @Override
        public void onRemoved(final long deviceId, final int faceId, final int userId,
                final int remaining) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleRemoved(deviceId, faceId, userId, remaining);
                }
            });
        }

        @Override
        public void onEnumerate(long deviceId, ArrayList<Integer> faceIds, int userId)
                throws RemoteException {

        }
    };

    private final class FaceServiceWrapper extends IFaceService.Stub {
        @Override // Binder call
        public long preEnroll(IBinder token) {
            checkPermission(MANAGE_FACE);
            return startPreEnroll(token);
        }

        @Override // Binder call
        public int postEnroll(IBinder token) {
            checkPermission(MANAGE_FACE);
            return startPostEnroll(token);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int userId,
                final IFaceServiceReceiver receiver, final int flags,
                final String opPackageName) {
            checkPermission(MANAGE_FACE);

            Face enrolledFace = FaceService.this.getEnrolledFace(userId);

            if (enrolledFace != null) {
                Slog.w(TAG, "Multiple faces enrollment is not supported");
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
            // Only give privileged apps (like Settings) access to faces info
            final boolean restricted = !hasPermission(MANAGE_FACE);
            return restricted;
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FACE);
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
        public void authenticate(final IBinder token, final long opId,
                                 final IFaceServiceReceiver receiver, final int flags,
                                 final String opPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getCallingUserId();
            final int pid = Binder.getCallingPid();
            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!canUseFace(opPackageName, true /* foregroundOnly */,
                            callingUid, pid, callingUserId)) {
                        if (DEBUG) Slog.v(TAG, "authenticate(): reject " + opPackageName);
                        return;
                    }

                    MetricsLogger.histogram(mContext, "faces_token", opId != 0L ? 1 : 0);

                    // Get performance stats object for this user.
                    HashMap<Integer, PerformanceStats> pmap
                            = (opId == 0) ? mPerformanceMap : mCryptoPerformanceMap;
                    PerformanceStats stats = pmap.get(mCurrentUserId);
                    if (stats == null) {
                        stats = new PerformanceStats();
                        pmap.put(mCurrentUserId, stats);
                    }
                    mPerformanceStats = stats;

                    startAuthentication(token, opId, callingUserId, receiver,
                            flags, restricted, opPackageName);
                }
            });
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, final String opPackageName) {
            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!canUseFace(opPackageName, true /* foregroundOnly */, uid, pid,
                            callingUserId)) {
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
            checkPermission(MANAGE_FACE);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateActiveGroup(userId, null);
                }
            });
        }

        @Override // Binder call
        public void remove(final IBinder token, final int userId,
                           final IFaceServiceReceiver receiver) {
            checkPermission(MANAGE_FACE); // TODO: Maybe have another permission
            final boolean restricted = isRestricted();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRemove(token, userId, receiver, restricted, false /* internal */);
                }
            });
        }

        @Override // Binder call
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            if (!canUseFace(opPackageName, false /* foregroundOnly */,
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
        public Face getEnrolledFace(int userId, String opPackageName) {
            if (!canUseFace(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return null;
            }

            return FaceService.this.getEnrolledFace(userId);
        }

        @Override // Binder call
        public boolean hasEnrolledFace(int userId, String opPackageName) {
            if (!canUseFace(opPackageName, false /* foregroundOnly */,
                    Binder.getCallingUid(), Binder.getCallingPid(),
                    UserHandle.getCallingUserId())) {
                return false;
            }

            return FaceService.this.hasEnrolledFace(userId);
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
            checkPermission(RESET_FACE_LOCKOUT);
            // TODO: confirm security token when we move timeout management into the HAL layer.
            mHandler.post(mResetFailedAttemptsRunnable);
        }

        @Override
        public void addLockoutResetCallback(final IFaceServiceLockoutResetCallback callback)
                throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    addLockoutResetMonitor(
                            new FaceServiceLockoutResetMonitor(callback));
                }
            });
        }
    }

    private void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");

            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                PerformanceStats stats = mPerformanceMap.get(userId);
                PerformanceStats cryptoStats = mCryptoPerformanceMap.get(userId);
                JSONObject set = new JSONObject();
                set.put("id", userId);
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

            final long userToken = proto.start(FaceServiceDumpProto.USERS);

            proto.write(FaceUserStatsProto.USER_ID, userId);

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
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FACE_SERVICE, new FaceServiceWrapper());
        SystemServerInitThreadPool.get().submit(this::getFaceDaemon, TAG + ".onStart");
        listenForUserSwitches();
    }

    private void updateActiveGroup(int userId, String clientPackage) {
        IBiometricsFace daemon = getFaceDaemon();

        if (daemon != null) {
            try {
                userId = getUserOrWorkProfileId(clientPackage, userId);
                if (userId != mCurrentUserId) {
                    final File systemDir = Environment.getUserSystemDirectory(userId);
                    final File faceDir = new File(systemDir, FACE_DATA_DIR);
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
                        hasEnrolledFace(userId) ? daemon.getAuthenticatorId().value : 0L);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveUser():", e);
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
