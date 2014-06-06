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

import android.app.ActivityManagerNative;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/**
 * A class that coordinates access to the fingerprint hardware.
 * @hide
 */

public class FingerprintManager {
    private static final String TAG = "FingerprintManager";
    private static final boolean DEBUG = true;
    private static final String FINGERPRINT_SERVICE_PACKAGE = "com.android.service.fingerprint";
    private static final String FINGERPRINT_SERVICE_CLASS =
            "com.android.service.fingerprint.FingerprintService";
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_SCANNED = 101;
    private static final int MSG_ERROR = 102;
    private static final int MSG_REMOVED = 103;

    public static final int FINGERPRINT_ERROR_NO_RECEIVER = -10;
    public static final int FINGERPRINT_ERROR = -1; // One of the error messages below.

    // Progress messages.
    public static final int FINGERPRINT_SCANNED = 1;
    public static final int FINGERPRINT_TEMPLATE_ENROLLING = 2;
    public static final int FINGERPRINT_TEMPLATE_REMOVED = 4;

    // Error messages. Must agree with fingerprint HAL definitions.
    public static final int FINGERPRINT_ERROR_HW_UNAVAILABLE = 1;
    public static final int FINGERPRINT_ERROR_BAD_CAPTURE = 2;
    public static final int FINGERPRINT_ERROR_TIMEOUT = 3;
    public static final int FINGERPRINT_ERROR_NO_SPACE = 4;

    private IFingerprintService mService;
    private FingerprintManagerReceiver mClientReceiver;
    private Context mContext;

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            if (mClientReceiver != null) {
                switch(msg.what) {
                    case MSG_ENROLL_RESULT:
                        mClientReceiver.onEnrollResult(msg.arg1, msg.arg2);
                        break;
                    case MSG_SCANNED:
                        mClientReceiver.onScanned(msg.arg1, msg.arg2);
                        break;
                    case MSG_ERROR:
                        mClientReceiver.onError(msg.arg1);
                        break;
                    case MSG_REMOVED:
                        mClientReceiver.onRemoved(msg.arg1);
                }
            }
        }
    };

    public FingerprintManager(Context context) {
        mContext = context;
        // Connect to service...
        Intent intent = new Intent();
        intent.setClassName(FINGERPRINT_SERVICE_PACKAGE, FINGERPRINT_SERVICE_CLASS);
        if (!context.bindServiceAsUser(intent, mFingerprintConnection,
                Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF)) {
            if (DEBUG) Log.v(TAG, "Can't bind to " + FINGERPRINT_SERVICE_CLASS);
        }
    }

    private final ServiceConnection mFingerprintConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "Connected to FingerprintService");
            mService = IFingerprintService.Stub.asInterface(service);
            try {
                mService.startListening(mServiceReceiver, getCurrentUserId());
            } catch (RemoteException e) {
                if (DEBUG) Log.v(TAG, "Failed to set callback", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "Disconnected from FingerprintService");
            mService = null;
        }
    };

    private IFingerprintServiceReceiver mServiceReceiver = new IFingerprintServiceReceiver.Stub() {

        public void onEnrollResult(int fingerprintId,  int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, fingerprintId, remaining).sendToTarget();
        }

        public void onScanned(int fingerprintId, int confidence) {
            mHandler.obtainMessage(MSG_SCANNED, fingerprintId, confidence)
                    .sendToTarget();;
        }

        public void onError(int error) {
            mHandler.obtainMessage(MSG_ERROR, error, 0).sendToTarget();
        }

        public void onRemoved(int fingerprintId) {
            mHandler.obtainMessage(MSG_REMOVED, fingerprintId, 0).sendToTarget();
        }
    };

    /**
     * Determine whether the user has at least one fingerprint enrolled and enabled.
     *
     * @return true if at least one is enrolled and enabled
     */
    public boolean enrolledAndEnabled() {
        ContentResolver res = mContext.getContentResolver();
        return Settings.Secure.getInt(res, "fingerprint_enabled", 0) != 0
                && FingerprintUtils.getFingerprintIdsForUser(res, getCurrentUserId()).length > 0;
    }

    /**
     * Start the enrollment process.  Timeout dictates how long to wait for the user to
     * enroll a fingerprint.
     *
     * @param timeout
     */
    public void enroll(long timeout) {
        if (mServiceReceiver == null) {
            throw new IllegalStateException("enroll: Call registerCallback() first");
        }
        if (mService != null) try {
            mService.enroll(timeout, getCurrentUserId());
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception while enrolling: ", e);
        }
    }

    /**
     * Remove the given fingerprintId from the system.  FingerprintId of 0 has special meaning
     * which is to delete all fingerprint data for the current user. Use with caution.
     * @param fingerprintId
     */
    public void remove(int fingerprintId) {
        if (mService != null) try {
            mService.remove(fingerprintId, getCurrentUserId());
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception during remove of fingerprintId: " + fingerprintId, e);
        }
    }

    /**
     * Starts listening for fingerprint events.  When a finger is scanned or recognized, the
     * client will be notified via the callback.
     */
    public void startListening(FingerprintManagerReceiver receiver) {
        mClientReceiver = receiver;
        if (mService != null) {
            try {
                mService.startListening(mServiceReceiver, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in startListening(): ", e);
            }
        }
    }

    private int getCurrentUserId() {
        try {
            return ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get current user id\n");
            return UserHandle.USER_NULL;
        }
    }

    /**
     * Stops the client from listening to fingerprint events.
     */
    public void stopListening() {
        mClientReceiver = null;
        if (mService != null) {
            try {
                mService.stopListening(getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in stopListening(): ", e);
            }
        } else {
            Log.w(TAG, "stopListening(): Service not connected!");
        }
    }
}