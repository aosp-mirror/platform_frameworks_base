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

package android.service.fingerprint;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.HashMap;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint -related events.
 *
 * @hide
 */
public class FingerprintService extends Service {
    private final String TAG = FingerprintService.class.getSimpleName() +
            "[" + getClass().getSimpleName() + "]";
    private static final boolean DEBUG = true;
    HashMap<IFingerprintServiceReceiver, ClientData> mClients =
            new HashMap<IFingerprintServiceReceiver, ClientData>();

    private static final int MSG_NOTIFY = 10;

    Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_NOTIFY:
                    handleNotify(msg.arg1, msg.arg2, (Integer) msg.obj);
                    break;

                default:
                    Slog.w(TAG, "Unknown message:" + msg.what);
            }
        }
    };

    private static final int STATE_IDLE = 0;
    private static final int STATE_LISTENING = 1;
    private static final int STATE_ENROLLING = 2;
    private static final int STATE_DELETING = 3;
    private static final long MS_PER_SEC = 1000;

    private static final class ClientData {
        public IFingerprintServiceReceiver receiver;
        int state;
        int userId;
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (DEBUG) Slog.v(TAG, "onBind() intent = " + intent);
        return new FingerprintServiceWrapper();
    }

    // JNI methods to communicate from FingerprintManagerService to HAL
    native int nativeEnroll(int timeout);
    native int nativeRemove(int fingerprintId);

    // JNI methods for communicating from HAL to clients
    void notify(int msg, int arg1, int arg2) {
        mHandler.obtainMessage(MSG_NOTIFY, msg, arg1, arg2).sendToTarget();
    }

    void handleNotify(int msg, int arg1, int arg2) {
        for (int i = 0; i < mClients.size(); i++) {
            ClientData clientData = mClients.get(i);
            switch (msg) {
                case FingerprintManager.FINGERPRINT_ERROR: {
                    if (clientData.state != STATE_IDLE) {
                        // FINGERPRINT_ERROR_HW_UNAVAILABLE
                        // FINGERPRINT_ERROR_BAD_CAPTURE
                        // FINGERPRINT_ERROR_TIMEOUT
                        // FINGERPRINT_ERROR_NO_SPACE
                        final int error = arg1;
                        clientData.state = STATE_IDLE;
                        if (clientData.receiver != null) {
                            try {
                                clientData.receiver.onError(error);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "can't send message to client. Did it die?", e);
                            }
                        }
                    }
                }
                break;
                case FingerprintManager.FINGERPRINT_SCANNED: {
                    final int fingerId = arg1;
                    final int confidence = arg2;
                    if (clientData.state == STATE_LISTENING && clientData.receiver != null) {
                        try {
                            clientData.receiver.onScanned(fingerId, confidence);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "can't send message to client. Did it die?", e);
                        }
                    }
                    break;
                }
                case FingerprintManager.FINGERPRINT_TEMPLATE_ENROLLING: {
                    if (clientData.state == STATE_ENROLLING) {
                        final int fingerId = arg1;
                        final int remaining = arg2;
                        if (remaining == 0) {
                            FingerprintUtils.addFingerprintIdForUser(fingerId,
                                    getContentResolver(), clientData.userId);
                            clientData.state = STATE_IDLE; // Nothing left to do
                        }
                        if (clientData.receiver != null) {
                            try {
                                clientData.receiver.onEnrollResult(fingerId, remaining);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "can't send message to client. Did it die?", e);
                            }
                        }
                    }
                    break;
                }
                case FingerprintManager.FINGERPRINT_TEMPLATE_REMOVED: {
                    int fingerId = arg1;
                    if (fingerId == 0) throw new IllegalStateException("Got illegal id from HAL");
                    if (clientData.state == STATE_DELETING) {
                        FingerprintUtils.removeFingerprintIdForUser(fingerId, getContentResolver(),
                                clientData.userId);
                        if (clientData.receiver != null) {
                            try {
                                clientData.receiver.onRemoved(fingerId);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "can't send message to client. Did it die?", e);
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    int enroll(IFingerprintServiceReceiver receiver, long timeout, int userId) {
        ClientData clientData = mClients.get(receiver);
        if (clientData != null) {
            if (clientData.userId != userId) throw new IllegalStateException("Bad user");
            clientData.state = STATE_ENROLLING;
            return nativeEnroll((int) (timeout / MS_PER_SEC));
        }
        return -1;
    }

    int remove(IFingerprintServiceReceiver receiver, int fingerId, int userId) {
        ClientData clientData = mClients.get(receiver);
        if (clientData != null) {
            if (clientData.userId != userId) throw new IllegalStateException("Bad user");
            clientData.state = STATE_DELETING;
            // The fingerprint id will be removed when we get confirmation from the HAL
            return nativeRemove(fingerId);
        }
        return -1;
    }

    void startListening(IFingerprintServiceReceiver receiver, int userId) {
        ClientData clientData = new ClientData();
        clientData.state = STATE_LISTENING;
        clientData.receiver = receiver;
        clientData.userId = userId;
        mClients.put(receiver, clientData);
    }

    void stopListening(IFingerprintServiceReceiver receiver, int userId) {
        ClientData clientData = mClients.get(receiver);
        if (clientData != null) {
            clientData.state = STATE_IDLE;
            clientData.userId = -1;
            clientData.receiver = null;
        }
        mClients.remove(receiver);
    }

    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        IFingerprintServiceReceiver mReceiver;
        public int enroll(long timeout, int userId) {
            return mReceiver != null ? FingerprintService.this.enroll(mReceiver, timeout, userId)
                    : FingerprintManager.FINGERPRINT_ERROR_NO_RECEIVER;
        }

        public int remove(int fingerprintId, int userId) {
            return FingerprintService.this.remove(mReceiver, fingerprintId, userId);
        }

        public void startListening(IFingerprintServiceReceiver receiver, int userId) {
            mReceiver = receiver;
            FingerprintService.this.startListening(receiver, userId);
        }

        public void stopListening(int userId) {
            FingerprintService.this.stopListening(mReceiver, userId);
        }
    }
}
