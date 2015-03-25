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
import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.fingerprint.FingerprintManager.EnrollmentCallback;
import android.util.Log;
import android.util.Slog;

import java.security.Signature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.crypto.Cipher;

/**
 * A class that coordinates access to the fingerprint hardware.
 * @hide
 */

public class FingerprintManager {
    private static final String TAG = "FingerprintManager";
    private static final boolean DEBUG = true;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_PROCESSED = 102;
    private static final int MSG_ERROR = 103;
    private static final int MSG_REMOVED = 104;

    // Message types.  Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ERROR = -1;
    public static final int FINGERPRINT_ACQUIRED = 1;
    public static final int FINGERPRINT_PROCESSED = 2;
    public static final int FINGERPRINT_TEMPLATE_ENROLLING = 3;
    public static final int FINGERPRINT_TEMPLATE_REMOVED = 4;

    // Error messages. Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ERROR_HW_UNAVAILABLE = 1;
    public static final int FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2;
    public static final int FINGERPRINT_ERROR_TIMEOUT = 3;
    public static final int FINGERPRINT_ERROR_NO_SPACE = 4;
    public static final int FINGERPRINT_ERROR_CANCELED = 5;
    public static final int FINGERPRINT_ERROR_VENDOR_BASE = 1000;

    // Image acquisition messages.  Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ACQUIRED_GOOD = 0;
    public static final int FINGERPRINT_ACQUIRED_PARTIAL = 1;
    public static final int FINGERPRINT_ACQUIRED_INSUFFICIENT = 2;
    public static final int FINGERPRINT_ACQUIRED_IMAGER_DIRTY = 3;
    public static final int FINGERPRINT_ACQUIRED_TOO_SLOW = 4;
    public static final int FINGERPRINT_ACQUIRED_TOO_FAST = 5;
    public static final int FINGERPRINT_ACQUIRED_VENDOR_BASE = 1000;

    private IFingerprintService mService;
    private Context mContext;
    private IBinder mToken = new Binder();
    private AuthenticationCallback mAuthenticationCallback;
    private EnrollmentCallback mEnrollmentCallback;
    private RemovalCallback mRemovalCallback;
    private CryptoObject mCryptoObject;
    private Fingerprint mRemovalFingerprint;
    private boolean mListening;

    /**
     * A wrapper class for a limited number of crypto objects supported by FingerprintManager.
     */
    public static class CryptoObject {
        CryptoObject(Signature signature) { mSignature = signature; }
        CryptoObject(Cipher cipher) { mCipher = cipher; }
        private Signature mSignature;
        private Cipher mCipher;
    };

    /**
     * Container for callback data from {@link FingerprintManager#authenticate(CryptoObject,
     *     AuthenticationCallback, CancellationSignal, int)}
     */
    public static final class AuthenticationResult {
        private Fingerprint mFingerprint;
        private CryptoObject mCryptoObject;

        public AuthenticationResult(CryptoObject crypto, Fingerprint fingerprint) {
            mCryptoObject = crypto;
            mFingerprint = fingerprint;
        }

        /**
         * Obtain the crypto object associated with this transaction
         * @return crypto object provided to {@link FingerprintManager#authenticate(CryptoObject,
         *     AuthenticationCallback, CancellationSignal, int)}
         */
        public CryptoObject getCryptoObject() { return mCryptoObject; }

        /**
         * Obtain the Fingerprint associated with this operation.  Applications are discouraged
         * from associating specific fingers with specific applications or operations.  Hence this
         * is not public.
         * @hide
         */
        public Fingerprint getFingerprint() { return mFingerprint; }
    };

    /**
     * Callback structure provided to {@link FingerprintManager#authenticate(CryptoObject,
     * AuthenticationCallback, CancellationSignal, int)}. Users of {@link #FingerprintManager()}
     * must provide an implementation of this to {@link FingerprintManager#authenticate(
     * CryptoObject, AuthenticationCallback, CancellationSignal, int) for listening to fingerprint
     * events.
     */
    public static abstract class AuthenticationCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         * @param errMsgId an integer identifying the error message.
         * @param errString a human-readible error string that can be shown in UI.
         */
        public abstract void onAuthenticationError(int errMsgId, CharSequence errString);

        /**
         * Called when a recoverable error has been encountered during authentication.  The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it."
         * @param helpMsgId an integer identifying the error message.
         * @param helpString a human-readible string that can be shown in UI.
         */
        public abstract void onAuthenticationHelp(int helpMsgId, CharSequence helpString);

        /**
         * Called when a fingerprint is recognized.
         * @param result an object containing authentication-related data.
         */
        public abstract void onAuthenticationSucceeded(AuthenticationResult result);
    };

    /**
     * Callback structure provided to {@link FingerprintManager#enroll(long, EnrollmentCallback,
     * CancellationSignal, int). Users of {@link #FingerprintManager()}
     * must provide an implementation of this to {@link FingerprintManager#enroll(long,
     * EnrollmentCallback, CancellationSignal, int) for listening to fingerprint events.
     */
    public static abstract class EnrollmentCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further callbacks will be made on this object.
         * @param errMsgId an integer identifying the error message.
         * @param errString a human-readible error string that can be shown in UI.
         */
        public abstract void onEnrollmentError(int errMsgId, CharSequence errString);

        /**
         * Called when a recoverable error has been encountered during enrollment.  The help
         * string is provided to give the user guidance for what went wrong, such as
         * "Sensor dirty, please clean it" or what they need to do next, such as
         * "Touch sensor again."
         * @param helpMsgId an integer identifying the error message.
         * @param helpString a human-readible string that can be shown in UI.
         */
        public abstract void onEnrollmentHelp(int helpMsgId, CharSequence helpString);

        /**
         * Called as each enrollment step progresses. Enrollment is considered complete when
         * remaining reaches 0.  This function will not be called if enrollment fails. See
         * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)}
         * @param remaining the number of remaining steps.
         */
        public abstract void onEnrollmentProgress(int remaining);
    };

    /**
     * Callback structure provided to {@link FingerprintManager#remove(int). Users of
     * {@link #FingerprintManager()} may optionally provide an implementation of this to
     * {@link FingerprintManager#remove(int, int, RemovalCallback)} for listening to
     * fingerprint template removal events.
     */
    public static abstract class RemovalCallback {
        /**
         * Called when the given fingerprint can't be removed.
         * @param fp the fingerprint that the call attempted to remove.
         * @parame errMsgId an associated error message id.
         * @param errString an error message indicating why the fingerprint id can't be removed.
         */
        public abstract void onRemovalError(Fingerprint fp, int errMsgId, CharSequence errString);

        /**
         * Called when a given fingerprint is successfully removed.
         * @param fingerprint the fingerprint template that was removed.
         */
        public abstract void onRemovalSucceeded(Fingerprint fingerprint);
    };

    /**
     * Request authentication of a crypto object.  This call warms up the fingerprint hardware
     * and starts scanning for a fingerprint.  It terminates when
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)} or
     * {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult) is called, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     *
     * @param crypto object associated with the call or null if none required.
     * @param callback an object to receive authentication events
     * @param cancel an object that can be used to cancel authentication
     * @param flags optional flags
     */
    public void authenticate(CryptoObject crypto, AuthenticationCallback callback,
            CancellationSignal cancel, int flags) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an authentication callback");
        }

        // TODO: handle cancel

        if (mService != null) try {
            mAuthenticationCallback = callback;
            mCryptoObject = crypto;
            long sessionId = 0; // TODO: get from crypto object
            startListening();
            mService.authenticate(mToken, sessionId, getCurrentUserId(), flags);
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception while authenticating: ", e);
            stopListening();
        }
    }

    /**
     * Request fingerprint enrollment. This call warms up the fingerprint hardware
     * and starts scanning for fingerprints. Progress will be indicated by callbacks to the
     * {@link EnrollmentCallback} object. It terminates when
     * {@link EnrollmentCallback#onEnrollmentError(int, CharSequence)} or
     * {@link EnrollmentCallback#onEnrollmentProgress(int) is called with remaining == 0, at
     * which point the object is no longer valid. The operation can be canceled by using the
     * provided cancel object.
     * @param challenge a unique id provided by a recent verification of device credentials
     *     (e.g. pin, pattern or password).
     * @param callback an object to receive enrollment events
     * @param cancel an object that can be used to cancel enrollment
     * @param flags optional flags
     */
    public void enroll(long challenge, EnrollmentCallback callback,
            CancellationSignal cancel, int flags) {
        if (callback == null) {
            throw new IllegalArgumentException("Must supply an enrollment callback");
        }

        // TODO: handle cancel

        if (mService != null) try {
            mEnrollmentCallback = callback;
            startListening();
            mService.enroll(mToken, getCurrentUserId(), flags);
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception in enroll: ", e);
            stopListening();
        }
    }

    /**
     * Remove given fingerprint template from fingerprint hardware and/or protected storage.
     * @param fp the fingerprint item to remove
     * @param callback an optional callback to verify that fingerprint templates have been
     * successfully removed.  May be null of no callback is required.
     * @hide
     */
    public void remove(Fingerprint fp, RemovalCallback callback) {
        if (mService != null) try {
            mRemovalCallback = callback;
            mRemovalFingerprint = fp;
            startListening();
            mService.remove(mToken, fp.getFingerId(), getCurrentUserId());
        } catch (RemoteException e) {
            Log.v(TAG, "Remote in remove: ", e);
            stopListening();
        }
    }

    /**
     * Renames the given fingerprint template
     * @param fpId the fingerprint id
     * @param newName the new name
     * @hide
     */
    public void rename(int fpId, String newName) {
        // Renames the given fpId
        if (mService != null) {
            try {
                mService.rename(fpId, getCurrentUserId(), newName);
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in rename(): ", e);
            }
        } else {
            Log.w(TAG, "rename(): Service not connected!");
        }
    }

    /**
     * Obtain the list of enrolled fingerprints templates.
     * @return list of current fingerprint items
     */
    public List<Fingerprint> getEnrolledFingerprints() {
        if (mService != null) try {
            return mService.getEnrolledFingerprints(getCurrentUserId());
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception in getEnrolledFingerprints: ", e);
        }
        return null;
    }

    /**
     * Determine if fingerprint hardware is present and functional.
     * @return true if hardware is present and functional, false otherwise.
     * @hide
     */
    public boolean isHardwareDetected() {
        if (mService != null) {
            try {
                long deviceId = 0; /* TODO: plumb hardware id to FPMS */
                return mService.isHardwareDetected(deviceId);
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in isFingerprintHardwareDetected(): ", e);
            }
        } else {
            Log.w(TAG, "isFingerprintHardwareDetected(): Service not connected!");
        }
        return false;
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch(msg.what) {
                case MSG_ENROLL_RESULT:
                    sendEnrollResult((Fingerprint) msg.obj, msg.arg1 /* remaining */);
                    break;
                case MSG_ACQUIRED:
                    sendAcquiredResult((Long) msg.obj /* deviceId */, msg.arg1 /* acquire info */);
                    break;
                case MSG_PROCESSED:
                    sendProcessedResult((Fingerprint) msg.obj);
                    break;
                case MSG_ERROR:
                    sendErrorResult((Long) msg.obj /* deviceId */, msg.arg1 /* errMsgId */);
                    break;
                case MSG_REMOVED:
                    sendRemovedResult((Long) msg.obj /* deviceId */, msg.arg1 /* fingerId */,
                            msg.arg2 /* groupId */);
            }
        }

        private void sendRemovedResult(long deviceId, int fingerId, int groupId) {
            if (mRemovalCallback != null) {
                int reqFingerId = mRemovalFingerprint.getFingerId();
                int reqGroupId = mRemovalFingerprint.getGroupId();
                if (fingerId != reqFingerId) {
                    Log.w(TAG, "Finger id didn't match: " + fingerId + " != " + reqFingerId);
                }
                if (fingerId != reqFingerId) {
                    Log.w(TAG, "Group id didn't match: " + groupId + " != " + reqGroupId);
                }
                mRemovalCallback.onRemovalSucceeded(mRemovalFingerprint);
            }
        }

        private void sendErrorResult(long deviceId, int errMsgId) {
            if (mEnrollmentCallback != null) {
                mEnrollmentCallback.onEnrollmentError(errMsgId, getErrorString(errMsgId));
            } else if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationError(errMsgId, getErrorString(errMsgId));
            } else if (mRemovalCallback != null) {
                mRemovalCallback.onRemovalError(mRemovalFingerprint, errMsgId,
                        getErrorString(errMsgId));
            }
        }

        private void sendEnrollResult(Fingerprint fp, int remaining) {
            if (mEnrollmentCallback != null) {
                mEnrollmentCallback.onEnrollmentProgress(remaining);
            }
        }

        private void sendProcessedResult(Fingerprint fp) {
            if (mAuthenticationCallback != null) {
                AuthenticationResult result = new AuthenticationResult(mCryptoObject, fp);
                mAuthenticationCallback.onAuthenticationSucceeded(result);
            }
        }

        private void sendAcquiredResult(long deviceId, int acquireInfo) {
            final String msg = getAcquiredString(acquireInfo);
            if (msg == null) return;

            if (mEnrollmentCallback != null) {
                mEnrollmentCallback.onEnrollmentHelp(acquireInfo, msg);
            } else if (mAuthenticationCallback != null) {
                mAuthenticationCallback.onAuthenticationHelp(acquireInfo, msg);
            }
        }

        private String getErrorString(int errMsg) {
            switch (errMsg) {
                case FINGERPRINT_ERROR_UNABLE_TO_PROCESS:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_error_unable_to_process);
                case FINGERPRINT_ERROR_HW_UNAVAILABLE:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_error_hw_not_available);
                case FINGERPRINT_ERROR_NO_SPACE:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_error_no_space);
                case FINGERPRINT_ERROR_TIMEOUT:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_error_timeout);
                default:
                    if (errMsg >= FINGERPRINT_ERROR_VENDOR_BASE) {
                        int msgNumber = errMsg - FINGERPRINT_ERROR_VENDOR_BASE;
                        String[] msgArray = mContext.getResources().getStringArray(
                                com.android.internal.R.array.fingerprint_error_vendor);
                        if (msgNumber < msgArray.length) {
                            return msgArray[msgNumber];
                        }
                    }
                    return null;
            }
        }

        private String getAcquiredString(int acquireInfo) {
            switch (acquireInfo) {
                case FINGERPRINT_ACQUIRED_GOOD:
                    return null;
                case FINGERPRINT_ACQUIRED_PARTIAL:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_acquired_partial);
                case FINGERPRINT_ACQUIRED_INSUFFICIENT:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_acquired_insufficient);
                case FINGERPRINT_ACQUIRED_IMAGER_DIRTY:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_acquired_imager_dirty);
                case FINGERPRINT_ACQUIRED_TOO_SLOW:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_acquired_too_slow);
                case FINGERPRINT_ACQUIRED_TOO_FAST:
                    return mContext.getString(
                        com.android.internal.R.string.fingerprint_acquired_too_fast);
                default:
                    if (acquireInfo >= FINGERPRINT_ACQUIRED_VENDOR_BASE) {
                        int msgNumber = acquireInfo - FINGERPRINT_ACQUIRED_VENDOR_BASE;
                        String[] msgArray = mContext.getResources().getStringArray(
                                com.android.internal.R.array.fingerprint_acquired_vendor);
                        if (msgNumber < msgArray.length) {
                            return msgArray[msgNumber];
                        }
                    }
                    return null;
            }
        }
    };

    /**
     * @hide
     */
    public FingerprintManager(Context context, IFingerprintService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FingerprintManagerService was null");
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
    private void stopListening() {
        if (mService != null) {
            try {
                if (mListening) {
                    mService.removeListener(mToken, mServiceReceiver);
                    mListening = false;
                }
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in stopListening(): ", e);
            }
        } else {
            Log.w(TAG, "stopListening(): Service not connected!");
        }
    }

    /**
     * Starts listening for fingerprint events for this client.
     */
    private void startListening() {
        if (mService != null) {
            try {
                if (!mListening) {
                    mService.addListener(mToken, mServiceReceiver, getCurrentUserId());
                    mListening = true;
                }
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in startListening(): ", e);
            }
        } else {
            Log.w(TAG, "startListening(): Service not connected!");
        }
    }

    private IFingerprintServiceReceiver mServiceReceiver = new IFingerprintServiceReceiver.Stub() {

        public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, remaining, 0,
                    new Fingerprint(null, groupId, fingerId, deviceId)).sendToTarget();
        }

        public void onAcquired(long deviceId, int acquireInfo) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, 0, deviceId).sendToTarget();
        }

        public void onProcessed(long deviceId, int fingerId, int groupId) {
            mHandler.obtainMessage(MSG_PROCESSED,
                    new Fingerprint(null, groupId, fingerId, deviceId)).sendToTarget();
        }

        public void onError(long deviceId, int error) {
            mHandler.obtainMessage(MSG_ERROR, error, 0, deviceId).sendToTarget();
        }

        public void onRemoved(long deviceId, int fingerId, int groupId) {
            mHandler.obtainMessage(MSG_REMOVED, fingerId, groupId, deviceId).sendToTarget();
        }
    };

}