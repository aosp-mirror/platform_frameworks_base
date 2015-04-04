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

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.SystemService;

import android.hardware.fingerprint.FingerprintUtils;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;

import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.USE_FINGERPRINT;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
    private ClientData mAuthClient = null;
    private ClientData mEnrollClient = null;
    private ClientData mRemoveClient = null;

    private static final int MSG_NOTIFY = 10;

    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute

    // Message types. Used internally to dispatch messages to the correct callback.
    // Must agree with the list in fingerprint.h
    private static final int FINGERPRINT_ERROR = -1;
    private static final int FINGERPRINT_ACQUIRED = 1;
    private static final int FINGERPRINT_PROCESSED = 2;
    private static final int FINGERPRINT_TEMPLATE_ENROLLING = 3;
    private static final int FINGERPRINT_TEMPLATE_REMOVED = 4;
    private static final int FINGERPRINT_AUTHENTICATED = 5;

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

    private static final int STATE_IDLE = 0;
    private static final int STATE_AUTHENTICATING = 1;
    private static final int STATE_ENROLLING = 2;
    private static final int STATE_REMOVING = 3;
    private static final long MS_PER_SEC = 1000;

    private class ClientData {
        IBinder token;
        IFingerprintServiceReceiver receiver;
        int userId;
        long opId;
        private TokenWatcher tokenWatcher;
        public ClientData(IBinder token, long opId, IFingerprintServiceReceiver receiver,
                int userId) {
            this.token = token;
            this.opId = opId;
            this.receiver = receiver;
            this.userId = userId;
            tokenWatcher = new TokenWatcher(token);
            try {
                token.linkToDeath(tokenWatcher, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
            }
        }

        IBinder getToken() {
            return tokenWatcher.getToken();
        }

        public void destroy() {
            token.unlinkToDeath(tokenWatcher, 0);
            tokenWatcher.token = null;
        }
    }

    private class TokenWatcher implements IBinder.DeathRecipient {
        WeakReference<IBinder> token;

        TokenWatcher(IBinder token) {
            this.token = new WeakReference<IBinder>(token);
        }

        IBinder getToken() {
            return token.get();
        }

        public void binderDied() {
            if (mAuthClient != null & mAuthClient.token == token)
                mAuthClient = null;
            if (mEnrollClient != null && mEnrollClient.token == token)
                mEnrollClient = null;
            this.token = null;
        }

        protected void finalize() throws Throwable {
            try {
                if (token != null) {
                    if (DEBUG) Slog.w(TAG, "removing leaked reference: " + token);
                    if (mAuthClient != null && mAuthClient.token == token) {
                        mAuthClient.destroy();
                        mAuthClient = null;
                    }
                    if (mEnrollClient != null && mEnrollClient.token == token) {
                        mAuthClient.destroy();
                        mEnrollClient = null;
                    }
                }
            } finally {
                super.finalize();
            }
        }
    }

    public FingerprintService(Context context) {
        super(context);
        mContext = context;
        nativeInit(Looper.getMainLooper().getQueue(), this);
    }

    // TODO: Move these into separate process
    // JNI methods to communicate from FingerprintService to HAL
    static native int nativeEnroll(long challenge, int groupId, int timeout);
    static native long nativePreEnroll();
    static native int nativeStopEnrollment();
    static native int nativeAuthenticate(long sessionId, int groupId);
    static native int nativeStopAuthentication(long sessionId);
    static native int nativeRemove(int fingerId, int groupId);
    static native int nativeOpenHal();
    static native int nativeCloseHal();
    static native void nativeInit(MessageQueue queue, FingerprintService service);

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
            try {
                final IBinder token = mEnrollClient.token;
                if (doNotify(mEnrollClient, type, arg1, arg2, arg3)) {
                    stopEnrollment(token);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "can't send message to mEnrollClient. Did it die?", e);
                mEnrollClient.destroy();
                mEnrollClient = null;
            }
        }
        if (mAuthClient != null) {
            try {
                final IBinder token = mAuthClient.getToken();
                if (doNotify(mAuthClient, type, arg1, arg2, arg3)) {
                    stopAuthentication(token);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "can't send message to mAuthClient. Did it die?", e);
                mAuthClient.destroy();
                mAuthClient = null;
            }
        }
        if (mRemoveClient != null) {
            try {
                if (doNotify(mRemoveClient, type, arg1, arg2, arg3)) {
                    mRemoveClient.destroy();
                    mRemoveClient = null;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "can't send message to mRemoveClient. Did it die?", e);
                mRemoveClient.destroy();
                mRemoveClient = null;
            }
        }
    }

    // Returns true if the operation is done, i.e. authentication completed
    boolean doNotify(ClientData clientData, int type, int arg1, int arg2, int arg3)
            throws RemoteException {
        if (clientData.receiver == null) {
            if (DEBUG) Slog.v(TAG, "receiver not registered!!");
            return false;
        }
        ContentResolver contentResolver = mContext.getContentResolver();
        boolean operationCompleted = false;
        switch (type) {
            case FINGERPRINT_ERROR:
                clientData.receiver.onError(mHalDeviceId, arg1 /* error */);
                if (arg1 == FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                    if (mEnrollClient != null) {
                        mEnrollClient.destroy();
                        mEnrollClient = null;
                    }
                    if (mAuthClient != null) {
                        mAuthClient.destroy();
                        mAuthClient = null;
                    }
                }
                operationCompleted = true; // any error means the operation is done
                break;
            case FINGERPRINT_ACQUIRED:
                clientData.receiver.onAcquired(mHalDeviceId, arg1 /* acquireInfo */);
                break;
            case FINGERPRINT_PROCESSED:
                clientData.receiver.onProcessed(mHalDeviceId, arg1 /* fpId */, arg2 /* gpId */);
                operationCompleted = true; // we either got a positive or negative match
                break;
            case FINGERPRINT_TEMPLATE_ENROLLING:
                {
                    final int fpId = arg1;
                    final int groupId = arg2;
                    final int remaining = arg3;
                    clientData.receiver.onEnrollResult(mHalDeviceId, fpId, groupId, remaining);
                    if (remaining == 0) {
                        addTemplateForUser(clientData, contentResolver, fpId);
                        operationCompleted = true; // enroll completed
                    }
                }
                break;
            case FINGERPRINT_TEMPLATE_REMOVED:
                {
                    final int fingerId = arg1;
                    final int groupId = arg2;
                    removeTemplateForUser(clientData, contentResolver, fingerId);
                    if (fingerId == 0) {
                        operationCompleted = true; // remove completed
                    } else {
                        clientData.receiver.onRemoved(mHalDeviceId, fingerId, groupId);
                    }
                }
                break;
        }
        return operationCompleted;
    }

    private void removeTemplateForUser(ClientData clientData, ContentResolver contentResolver,
            final int fingerId) {
        FingerprintUtils.removeFingerprintIdForUser(fingerId, contentResolver,
                clientData.userId);
    }

    private void addTemplateForUser(ClientData clientData, ContentResolver contentResolver,
            final int fingerId) {
        FingerprintUtils.addFingerprintIdForUser(contentResolver, fingerId,
                clientData.userId);
    }

    void startEnrollment(IBinder token, long opId,
            int groupId, IFingerprintServiceReceiver receiver, int flags) {
        stopPendingOperations();
        mEnrollClient = new ClientData(token, opId, receiver, groupId);
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        final int result = nativeEnroll(opId, groupId, timeout);
        if (result != 0) {
            Slog.w(TAG, "startEnroll failed, result=" + result);
        }
    }

    public long startPreEnroll(IBinder token) {
        return nativePreEnroll();
    }

    private void stopPendingOperations() {
        if (mEnrollClient != null) {
            stopEnrollment(mEnrollClient.token);
        }
        if (mAuthClient != null) {
            stopAuthentication(mAuthClient.token);
        }
        // mRemoveClient is allowed to continue
    }

    void stopEnrollment(IBinder token) {
        if (mEnrollClient == null || mEnrollClient.token != token) return;
        int result = nativeStopEnrollment();
        if (result != 0) {
            Slog.w(TAG, "startEnrollCancel failed, result=" + result);
        }
    }

    void startAuthentication(IBinder token, long opId, int groupId,
            IFingerprintServiceReceiver receiver, int flags) {
        stopPendingOperations();
        mAuthClient = new ClientData(token, opId, receiver, groupId);
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        final int result = nativeAuthenticate(opId, groupId);
        if (result != 0) {
            Slog.w(TAG, "startAuthentication failed, result=" + result);
        }
    }

    void stopAuthentication(IBinder token) {
        if (mAuthClient == null || mAuthClient.token != token) return;
        int result = nativeStopAuthentication(mAuthClient.opId);
        if (result != 0) {
            Slog.w(TAG, "stopAuthentication failed, result=" + result);
        }
    }

    void startRemove(IBinder token, int fingerId, int userId,
            IFingerprintServiceReceiver receiver) {
        mRemoveClient = new ClientData(token, 0, receiver, userId);
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

    void checkPermission(String permission) {
        getContext().enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    private static final class Message {
        IBinder token;
        long opId;
        int groupId;
        int flags;

        public Message(IBinder token, long challenge, int groupId, int flags) {
            this.token = token;
            this.opId = challenge;
            this.groupId = groupId;
            this.flags = flags;
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
        public void enroll(final IBinder token, final long opid, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startEnrollment(token, opid, groupId, receiver, flags);
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
                    stopEnrollment(token);
                }
            });
        }

        @Override
        // Binder call
        public void authenticate(final IBinder token, final long opId, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags) {
            checkPermission(USE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startAuthentication(token, opId, groupId, receiver, flags);
                }
            });
        }

        @Override

        // Binder call
        public void cancelAuthentication(final IBinder token) {
            checkPermission(USE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopAuthentication(token);
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
        public boolean isHardwareDetected(long deviceId) {
            checkPermission(USE_FINGERPRINT);
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
        public List<Fingerprint> getEnrolledFingerprints(int groupId) {
            checkPermission(USE_FINGERPRINT);
            return FingerprintService.this.getEnrolledFingerprints(groupId);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        mHalDeviceId = nativeOpenHal();
        if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
    }

}
