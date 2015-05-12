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

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.SystemService;

import android.hardware.fingerprint.FingerprintUtils;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;

import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.USE_FINGERPRINT;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint -related events.
 *
 * @hide
 */
public class FingerprintService extends SystemService {
    private static final String TAG = "FingerprintService";
    private static final boolean DEBUG = true;
    private ClientMonitor mAuthClient = null;
    private ClientMonitor mEnrollClient = null;
    private ClientMonitor mRemoveClient = null;

    private final AppOpsManager mAppOps;

    private static final int MSG_NOTIFY = 10;

    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute

    // Message types. Used internally to dispatch messages to the correct callback.
    // Must agree with the list in fingerprint.h
    private static final int FINGERPRINT_ERROR = -1;
    private static final int FINGERPRINT_ACQUIRED = 1;
    private static final int FINGERPRINT_TEMPLATE_ENROLLING = 3;
    private static final int FINGERPRINT_TEMPLATE_REMOVED = 4;
    private static final int FINGERPRINT_AUTHENTICATED = 5;
    private static final long MS_PER_SEC = 1000;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30*1000;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_NOTIFY:
                    FpHalMsg m = (FpHalMsg) msg.obj;
                    handleNotify(m.type, m.arg1, m.arg2, m.arg3);
                    break;

                default:
                    Slog.w(TAG, "Unknown message:" + msg.what);
            }
        }
    };
    private Context mContext;
    private int mHalDeviceId;
    private int mFailedAttempts;
    private final Runnable mLockoutReset = new Runnable() {
        @Override
        public void run() {
            resetFailedAttempts();
        }
    };

    public FingerprintService(Context context) {
        super(context);
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        nativeInit(Looper.getMainLooper().getQueue(), this);
    }

    // TODO: Move these into separate process
    // JNI methods to communicate from FingerprintService to HAL
    static native int nativeEnroll(byte [] token, int groupId, int timeout);
    static native long nativePreEnroll();
    static native int nativeStopEnrollment();
    static native int nativeAuthenticate(long sessionId, int groupId);
    static native int nativeStopAuthentication();
    static native int nativeRemove(int fingerId, int groupId);
    static native int nativeOpenHal();
    static native int nativeCloseHal();
    static native void nativeInit(MessageQueue queue, FingerprintService service);
    static native long nativeGetAuthenticatorId();
    static native int nativeSetActiveGroup(int gid, byte[] storePath);

    static final class FpHalMsg {
        int type; // Type of the message. One of the constants in fingerprint.h
        int arg1; // optional arguments
        int arg2;
        int arg3;

        FpHalMsg(int type, int arg1, int arg2, int arg3) {
            this.type = type;
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.arg3 = arg3;
        }
    }

    /**
     * Called from JNI to communicate messages from fingerprint HAL.
     */
    void notify(int type, int arg1, int arg2, int arg3) {
        mHandler.obtainMessage(MSG_NOTIFY, new FpHalMsg(type, arg1, arg2, arg3)).sendToTarget();
    }

    void handleNotify(int type, int arg1, int arg2, int arg3) {
        Slog.v(TAG, "handleNotify(type=" + type + ", arg1=" + arg1 + ", arg2=" + arg2 + ")"
                    + ", mAuthClients = " + mAuthClient + ", mEnrollClient = " + mEnrollClient);
        if (mEnrollClient != null) {
            final IBinder token = mEnrollClient.token;
            if (dispatchNotify(mEnrollClient, type, arg1, arg2, arg3)) {
                stopEnrollment(token, false);
                removeClient(mEnrollClient);
            }
        }
        if (mAuthClient != null) {
            final IBinder token = mAuthClient.token;
            if (dispatchNotify(mAuthClient, type, arg1, arg2, arg3)) {
                stopAuthentication(token, false);
                removeClient(mAuthClient);
            }
        }
        if (mRemoveClient != null) {
            if (dispatchNotify(mRemoveClient, type, arg1, arg2, arg3)) {
                removeClient(mRemoveClient);
            }
        }
    }

    /*
     * Dispatch notify events to clients.
     *
     * @return true if the operation is done, i.e. authentication completed
     */
    boolean dispatchNotify(ClientMonitor clientMonitor, int type, int arg1, int arg2, int arg3) {
        ContentResolver contentResolver = mContext.getContentResolver();
        boolean operationCompleted = false;
        int fpId;
        int groupId;
        int remaining;
        int acquireInfo;
        switch (type) {
            case FINGERPRINT_ERROR:
                fpId = arg1;
                operationCompleted = clientMonitor.sendError(fpId);
                break;
            case FINGERPRINT_ACQUIRED:
                acquireInfo = arg1;
                operationCompleted = clientMonitor.sendAcquired(acquireInfo);
                break;
            case FINGERPRINT_AUTHENTICATED:
                fpId = arg1;
                groupId = arg2;
                operationCompleted = clientMonitor.sendAuthenticated(fpId, groupId);
                break;
            case FINGERPRINT_TEMPLATE_ENROLLING:
                fpId = arg1;
                groupId = arg2;
                remaining = arg3;
                operationCompleted = clientMonitor.sendEnrollResult(fpId, groupId, remaining);
                if (remaining == 0) {
                    addTemplateForUser(clientMonitor, contentResolver, fpId);
                    operationCompleted = true; // enroll completed
                }
                break;
            case FINGERPRINT_TEMPLATE_REMOVED:
                fpId = arg1;
                groupId = arg2;
                operationCompleted = clientMonitor.sendRemoved(fpId, groupId);
                if (fpId != 0) {
                    removeTemplateForUser(clientMonitor, contentResolver, fpId);
                }
                break;
        }
        return operationCompleted;
    }

    private void removeClient(ClientMonitor clientMonitor) {
        if (clientMonitor == null) return;
        clientMonitor.destroy();
        if (clientMonitor == mAuthClient) {
            mAuthClient = null;
        } else if (clientMonitor == mEnrollClient) {
            mEnrollClient = null;
        } else if (clientMonitor == mRemoveClient) {
            mRemoveClient = null;
        }
    }

    private boolean inLockoutMode() {
        return mFailedAttempts > MAX_FAILED_ATTEMPTS;
    }

    private void resetFailedAttempts() {
        if (DEBUG && inLockoutMode()) {
            Slog.v(TAG, "Reset fingerprint lockout");
        }
        mFailedAttempts = 0;
    }

    private boolean handleFailedAttempt(ClientMonitor clientMonitor) {
        mFailedAttempts++;
        if (mFailedAttempts > MAX_FAILED_ATTEMPTS) {
            // Failing multiple times will continue to push out the lockout time.
            mHandler.removeCallbacks(mLockoutReset);
            mHandler.postDelayed(mLockoutReset, FAIL_LOCKOUT_TIMEOUT_MS);
            if (clientMonitor != null
                    && !clientMonitor.sendError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT)) {
                Slog.w(TAG, "Cannot send lockout message to client");
            }
            return true;
        }
        return false;
    }

    private void removeTemplateForUser(ClientMonitor clientMonitor, ContentResolver contentResolver,
            final int fingerId) {
        FingerprintUtils.removeFingerprintIdForUser(fingerId, contentResolver,
                clientMonitor.userId);
    }

    private void addTemplateForUser(ClientMonitor clientMonitor, ContentResolver contentResolver,
            final int fingerId) {
        FingerprintUtils.addFingerprintIdForUser(contentResolver, fingerId,
                clientMonitor.userId);
    }

    void startEnrollment(IBinder token, byte[] cryptoToken, int groupId,
            IFingerprintServiceReceiver receiver, int flags) {
        stopPendingOperations();
        mEnrollClient = new ClientMonitor(token, receiver, groupId);
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        final int result = nativeEnroll(cryptoToken, groupId, timeout);
        if (result != 0) {
            Slog.w(TAG, "startEnroll failed, result=" + result);
        }
    }

    public long startPreEnroll(IBinder token) {
        return nativePreEnroll();
    }

    private void stopPendingOperations() {
        if (mEnrollClient != null) {
            stopEnrollment(mEnrollClient.token, true);
        }
        if (mAuthClient != null) {
            stopAuthentication(mAuthClient.token, true);
        }
        // mRemoveClient is allowed to continue
    }

    void stopEnrollment(IBinder token, boolean notify) {
        final ClientMonitor client = mEnrollClient;
        if (client == null || client.token != token) return;
        int result = nativeStopEnrollment();
        if (notify) {
            client.sendError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        removeClient(mEnrollClient);
        if (result != 0) {
            Slog.w(TAG, "startEnrollCancel failed, result=" + result);
        }
    }

    void startAuthentication(IBinder token, long opId, int groupId,
            IFingerprintServiceReceiver receiver, int flags) {
        stopPendingOperations();
        mAuthClient = new ClientMonitor(token, receiver, groupId);
        if (inLockoutMode()) {
            Slog.v(TAG, "In lockout mode; disallowing authentication");
            if (!mAuthClient.sendError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT)) {
                Slog.w(TAG, "Cannot send timeout message to client");
            }
            mAuthClient = null;
            return;
        }
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        final int result = nativeAuthenticate(opId, groupId);
        if (result != 0) {
            Slog.w(TAG, "startAuthentication failed, result=" + result);
        }
    }

    void stopAuthentication(IBinder token, boolean notify) {
        final ClientMonitor client = mAuthClient;
        if (client == null || client.token != token) return;
        int result = nativeStopAuthentication();
        if (notify) {
            client.sendError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        removeClient(mAuthClient);
        if (result != 0) {
            Slog.w(TAG, "stopAuthentication failed, result=" + result);
        }
    }

    void startRemove(IBinder token, int fingerId, int userId,
            IFingerprintServiceReceiver receiver) {
        mRemoveClient = new ClientMonitor(token, receiver, userId);
        // The fingerprint template ids will be removed when we get confirmation from the HAL
        final int result = nativeRemove(fingerId, userId);
        if (result != 0) {
            Slog.w(TAG, "startRemove with id = " + fingerId + " failed with result=" + result);
        }
    }

    public List<Fingerprint> getEnrolledFingerprints(int groupId) {
        ContentResolver resolver = mContext.getContentResolver();
        int[] ids = FingerprintUtils.getFingerprintIdsForUser(resolver, groupId);
        List<Fingerprint> result = new ArrayList<Fingerprint>();
        for (int i = 0; i < ids.length; i++) {
            // TODO: persist names in Settings
            CharSequence name = "Finger" + ids[i];
            final int group = 0; // TODO
            final int fingerId = ids[i];
            final long deviceId = 0; // TODO
            Fingerprint item = new Fingerprint(name, 0, ids[i], 0);
            result.add(item);
        }
        return result;
    }

    public boolean hasEnrolledFingerprints(int groupId) {
        ContentResolver resolver = mContext.getContentResolver();
        return FingerprintUtils.getFingerprintIdsForUser(resolver, groupId).length > 0;
    }

    void checkPermission(String permission) {
        getContext().enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    private boolean canUserFingerPrint(String opPackageName) {
        checkPermission(USE_FINGERPRINT);

        return mAppOps.noteOp(AppOpsManager.OP_USE_FINGERPRINT, Binder.getCallingUid(),
                opPackageName) == AppOpsManager.MODE_ALLOWED;
    }

    private class ClientMonitor implements IBinder.DeathRecipient {
        IBinder token;
        IFingerprintServiceReceiver receiver;
        int userId;

        public ClientMonitor(IBinder token, IFingerprintServiceReceiver receiver, int userId) {
            this.token = token;
            this.receiver = receiver;
            this.userId = userId;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
            }
        }

        public void destroy() {
            if (token != null) {
                token.unlinkToDeath(this, 0);
                token = null;
            }
            receiver = null;
        }

        public void binderDied() {
            token = null;
            removeClient(this);
            receiver = null;
        }

        protected void finalize() throws Throwable {
            try {
                if (token != null) {
                    if (DEBUG) Slog.w(TAG, "removing leaked reference: " + token);
                    removeClient(this);
                }
            } finally {
                super.finalize();
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendRemoved(int fingerId, int groupId) {
            if (receiver == null) return true; // client not listening
            try {
                receiver.onRemoved(mHalDeviceId, fingerId, groupId);
                return fingerId == 0;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify Removed:", e);
            }
            return false;
        }

        /*
         * @return true if we're done.
         */
        private boolean sendEnrollResult(int fpId, int groupId, int remaining) {
            if (receiver == null) return true; // client not listening
            FingerprintUtils.vibrateFingerprintSuccess(getContext());
            try {
                receiver.onEnrollResult(mHalDeviceId, fpId, groupId, remaining);
                return remaining == 0;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify EnrollResult:", e);
                return true;
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendAuthenticated(int fpId, int groupId) {
            boolean result = false;
            if (receiver != null) {
                try {
                    receiver.onAuthenticated(mHalDeviceId, fpId, groupId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify Authenticated:", e);
                    result = true; // client failed
                }
            } else {
                result = true; // client not listening
            }
            if (fpId <= 0) {
                FingerprintUtils.vibrateFingerprintError(getContext());
                result |= handleFailedAttempt(this);
            } else {
                FingerprintUtils.vibrateFingerprintSuccess(getContext());
                result |= true; // we have a valid fingerprint
                mLockoutReset.run();
            }
            return result;
        }

        /*
         * @return true if we're done.
         */
        private boolean sendAcquired(int acquiredInfo) {
            if (receiver == null) return true; // client not listening
            try {
                receiver.onAcquired(mHalDeviceId, acquiredInfo);
                return false; // acquisition continues...
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendAcquired:", e);
                return true; // client failed
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendError(int error) {
            if (receiver != null) {
                try {
                    receiver.onError(mHalDeviceId, error);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to invoke sendError:", e);
                }
            }
            return true; // errors always terminate progress
        }
    }

    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override
        public long preEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPreEnroll(token);
        }

        @Override
        // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags) {
            checkPermission(MANAGE_FINGERPRINT);
            final byte [] cryptoClone = Arrays.copyOf(cryptoToken, cryptoToken.length);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startEnrollment(token, cryptoClone, groupId, receiver, flags);
                }
            });
        }

        @Override
        // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopEnrollment(token, true);
                }
            });
        }

        @Override
        // Binder call
        public void authenticate(final IBinder token, final long opId, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags, String opPackageName) {
            checkPermission(USE_FINGERPRINT);
            if (!canUserFingerPrint(opPackageName)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startAuthentication(token, opId, groupId, receiver, flags);
                }
            });
        }

        @Override

        // Binder call
        public void cancelAuthentication(final IBinder token, String opPackageName) {
            if (!canUserFingerPrint(opPackageName)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopAuthentication(token, true);
                }
            });
        }

        @Override
        // Binder call
        public void remove(final IBinder token, final int fingerId, final int groupId,
                final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT); // TODO: Maybe have another permission
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRemove(token, fingerId, groupId, receiver);
                }
            });

        }

        @Override
        // Binder call
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            if (!canUserFingerPrint(opPackageName)) {
                return false;
            }
            return mHalDeviceId != 0; // TODO
        }

        @Override
        // Binder call
        public void rename(final int fingerId, final int groupId, final String name) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Slog.w(TAG, "rename id=" + fingerId + ",gid=" + groupId + ",name=" + name);
                }
            });
        }

        @Override
        // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int groupId, String opPackageName) {
            if (!canUserFingerPrint(opPackageName)) {
                return Collections.emptyList();
            }
            return FingerprintService.this.getEnrolledFingerprints(groupId);
        }

        @Override
        // Binder call
        public boolean hasEnrolledFingerprints(int groupId, String opPackageName) {
            if (!canUserFingerPrint(opPackageName)) {
                return false;
            }
            return FingerprintService.this.hasEnrolledFingerprints(groupId);
        }

        @Override
        public long getAuthenticatorId(String opPackageName) {
            if (!canUserFingerPrint(opPackageName)) {
                return 0;
            }
            return nativeGetAuthenticatorId();
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        mHalDeviceId = nativeOpenHal();
        if (mHalDeviceId != 0) {
            int userId = ActivityManager.getCurrentUser();
            File path = Environment.getUserSystemDirectory(userId);
            nativeSetActiveGroup(0, path.getAbsolutePath().getBytes());
        }
        if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
    }

}
