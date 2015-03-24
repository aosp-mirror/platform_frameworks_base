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
import android.service.fingerprint.FingerprintManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.SystemService;

import android.service.fingerprint.FingerprintUtils;
import android.service.fingerprint.Fingerprint;
import android.service.fingerprint.IFingerprintService;
import android.service.fingerprint.IFingerprintServiceReceiver;

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
    private final String TAG = "FingerprintService";
    private static final boolean DEBUG = true;
    private ArrayMap<IBinder, ClientData> mClients = new ArrayMap<IBinder, ClientData>();

    private static final int MSG_NOTIFY = 10;

    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute

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

    private static final class ClientData {
        public IFingerprintServiceReceiver receiver;
        int state;
        int userId;
        public TokenWatcher tokenWatcher;

        IBinder getToken() {
            return tokenWatcher.getToken();
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
            mClients.remove(token);
            this.token = null;
        }

        protected void finalize() throws Throwable {
            try {
                if (token != null) {
                    if (DEBUG) Slog.w(TAG, "removing leaked reference: " + token);
                    mClients.remove(token);
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
    // JNI methods to communicate from FingerprintManagerService to HAL
    static native int nativeEnroll(int timeout, int groupId);

    static native int nativeAuthenticate(long sessionId, int groupId);

    static native int nativeEnrollCancel();

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

    // JNI methods for communicating from HAL to clients
    void notify(int type, int arg1, int arg2, int arg3) {
        mHandler.obtainMessage(MSG_NOTIFY, new FpHalMsg(type, arg1, arg2, arg3)).sendToTarget();
    }

    void handleNotify(int type, int arg1, int arg2, int arg3) {
        Slog.v(TAG, "handleNotify(type=" + type + ", arg1=" + arg1 + ", arg2=" + arg2 + ")" + ", "
                + mClients.size() + " clients");
        for (int i = 0; i < mClients.size(); i++) {
            if (DEBUG) Slog.v(TAG, "Client[" + i + "] binder token: " + mClients.keyAt(i));
            ClientData clientData = mClients.valueAt(i);
            if (clientData == null || clientData.receiver == null) {
                if (DEBUG) Slog.v(TAG, "clientData is invalid!!");
                continue;
            }
            ContentResolver contentResolver = mContext.getContentResolver();
            switch (type) {
                case FingerprintManager.FINGERPRINT_ERROR: {
                    try {
                        clientData.receiver.onError(mHalDeviceId, arg1 /* error */);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "can't send message to client. Did it die?", e);
                        mClients.remove(mClients.keyAt(i));
                    }
                }
                    break;
                case FingerprintManager.FINGERPRINT_ACQUIRED: {
                    try {
                        clientData.receiver.onAcquired(mHalDeviceId, arg1 /* acquireInfo */);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "can't send message to client. Did it die?", e);
                        mClients.remove(mClients.keyAt(i));
                    }
                    break;
                }
                case FingerprintManager.FINGERPRINT_PROCESSED: {
                    try {
                        clientData.receiver
                                .onProcessed(mHalDeviceId, arg1 /* fingerId */, arg2 /* groupId */);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "can't send message to client. Did it die?", e);
                        mClients.remove(mClients.keyAt(i));
                    }
                    break;
                }
                case FingerprintManager.FINGERPRINT_TEMPLATE_ENROLLING: {
                    final int fingerId = arg1;
                    final int groupId = arg2;
                    final int remaining = arg3;
                    if (clientData.state == STATE_ENROLLING) {
                        // Only send enroll updates to clients that are actually enrolling
                        try {
                            clientData.receiver.onEnrollResult(mHalDeviceId, fingerId, groupId,
                                    remaining);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "can't send message to client. Did it die?", e);
                            mClients.remove(mClients.keyAt(i));
                        }
                        // Update the database with new finger id.
                        // TODO: move to client code (Settings)
                        if (remaining == 0) {
                            FingerprintUtils.addFingerprintIdForUser(contentResolver, fingerId,
                                    clientData.userId);
                            clientData.state = STATE_IDLE; // Nothing left to do
                        }
                    } else {
                        if (DEBUG) Slog.w(TAG, "Client not enrolling");
                        break;
                    }
                    break;
                }
                case FingerprintManager.FINGERPRINT_TEMPLATE_REMOVED: {
                    int fingerId = arg1;
                    int groupId = arg2;
                    if (fingerId == 0) {
                        throw new IllegalStateException("Got illegal id from HAL");
                    }
                    FingerprintUtils.removeFingerprintIdForUser(fingerId, contentResolver,
                            clientData.userId);
                    if (clientData.receiver != null) {
                        try {
                            clientData.receiver.onRemoved(mHalDeviceId, fingerId, groupId);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "can't send message to client. Did it die?", e);
                            mClients.remove(mClients.keyAt(i));
                        }
                    }
                    clientData.state = STATE_IDLE;
                }
                    break;
            }
        }
    }

    void startEnroll(IBinder token, int groupId, int flags) {
        ClientData clientData = mClients.get(token);
        if (clientData != null) {
            if (clientData.userId != groupId) {
                throw new IllegalStateException("Bad user");
            }
            clientData.state = STATE_ENROLLING;
            final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
            nativeEnroll(timeout, groupId);
        } else {
            Slog.w(TAG, "enroll(): No listener registered");
        }
    }

    void startAuthenticate(IBinder token, long sessionId, int groupId, int flags) {
        ClientData clientData = mClients.get(token);
        if (clientData != null) {
            if (clientData.userId != groupId) {
                throw new IllegalStateException("Bad user");
            }
            clientData.state = STATE_AUTHENTICATING;
            final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
            nativeAuthenticate(sessionId, groupId);
        } else {
            Slog.w(TAG, "enroll(): No listener registered");
        }
    }

    void startEnrollCancel(IBinder token, int userId) {
        ClientData clientData = mClients.get(token);
        if (clientData != null) {
            if (clientData.userId != userId) throw new IllegalStateException("Bad user");
            clientData.state = STATE_IDLE;
            nativeEnrollCancel();
        } else {
            Slog.w(TAG, "enrollCancel(): No listener registered");
        }
    }

    // Remove all fingerprints for the given user.
    void startRemove(IBinder token, int fingerId, int userId) {
        ClientData clientData = mClients.get(token);
        if (clientData != null) {
            if (clientData.userId != userId) throw new IllegalStateException("Bad user");
            clientData.state = STATE_REMOVING;
            // The fingerprint id will be removed when we get confirmation from the HAL
            int result = nativeRemove(fingerId, userId);
            if (result != 0) {
                Slog.w(TAG, "Error removing fingerprint with id = " + fingerId);
            }
        } else {
            Slog.w(TAG, "remove(" + token + "): No listener registered");
        }
    }

    void addListener(IBinder token, IFingerprintServiceReceiver receiver, int userId) {
        if (DEBUG) Slog.v(TAG, "startListening(" + receiver + ")");
        if (mClients.get(token) == null) {
            ClientData clientData = new ClientData();
            clientData.state = STATE_IDLE;
            clientData.receiver = receiver;
            clientData.userId = userId;
            clientData.tokenWatcher = new TokenWatcher(token);
            try {
                token.linkToDeath(clientData.tokenWatcher, 0);
                mClients.put(token, clientData);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
            }
        } else {
            if (DEBUG) Slog.v(TAG, "listener already registered for " + token);
        }
    }

    void removeListener(IBinder token, IFingerprintServiceReceiver receiver) {
        if (DEBUG) Slog.v(TAG, "stopListening(" + token + ")");
        ClientData clientData = mClients.get(token);
        if (clientData != null) {
            token.unlinkToDeath(clientData.tokenWatcher, 0);
            mClients.remove(token);
        } else {
            if (DEBUG) Slog.v(TAG, "listener not registered: " + token);
        }
        mClients.remove(token);
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

    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override
        // Binder call
        public void enroll(IBinder token, int groupId, int flags) {
            checkPermission(MANAGE_FINGERPRINT);
            startEnroll(token, groupId, flags);
        }

        @Override 
        // Binder call
        public void authenticate(IBinder token, long sessionId, int groupId, int flags) {
            checkPermission(USE_FINGERPRINT);
            startAuthenticate(token, sessionId, groupId, flags);
        }

        @Override
        // Binder call
        public void remove(IBinder token, int fingerId, int groupId) {
            checkPermission(MANAGE_FINGERPRINT); // TODO: Maybe have another permission
            startRemove(token, fingerId, groupId);
        }

        @Override
        // Binder call
        public void addListener(IBinder token, IFingerprintServiceReceiver receiver, int userId) {
            checkPermission(USE_FINGERPRINT);
            FingerprintService.this.addListener(token, receiver, userId);
        }

        @Override
        // Binder call
        public void removeListener(IBinder token, IFingerprintServiceReceiver receiver) {
            checkPermission(USE_FINGERPRINT);
            FingerprintService.this.removeListener(token, receiver);
        }

        @Override
        // Binder call
        public boolean isHardwareDetected(long deviceId) {
            checkPermission(USE_FINGERPRINT);
            return mHalDeviceId != 0; // TODO
        }

        @Override
        // Binder call
        public void rename(int fingerId, int groupId, String name) {
            checkPermission(MANAGE_FINGERPRINT);
            Slog.w(TAG, "rename id=" + fingerId + ",gid=" + groupId + ",name=" + name);
            // TODO
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
