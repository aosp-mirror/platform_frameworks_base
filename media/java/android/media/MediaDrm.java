/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.Log;
import dalvik.system.CloseGuard;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * MediaDrm can be used to obtain keys for decrypting protected media streams, in
 * conjunction with {@link android.media.MediaCrypto}.  The MediaDrm APIs
 * are designed to support the ISO/IEC 23001-7: Common Encryption standard, but
 * may also be used to implement other encryption schemes.
 * <p>
 * Encrypted content is prepared using an encryption server and stored in a content
 * library. The encrypted content is streamed or downloaded from the content library to
 * client devices via content servers.  Licenses to view the content are obtained from
 * a License Server.
 * <p>
 * <p><img src="../../../images/mediadrm_overview.png"
 *      alt="MediaDrm Overview diagram"
 *      border="0" /></p>
 * <p>
 * Keys are requested from the license server using a key request. The key
 * response is delivered to the client app, which provides the response to the
 * MediaDrm API.
 * <p>
 * A Provisioning server may be required to distribute device-unique credentials to
 * the devices.
 * <p>
 * Enforcing requirements related to the number of devices that may play content
 * simultaneously can be performed either through key renewal or using the secure
 * stop methods.
 * <p>
 * The following sequence diagram shows the interactions between the objects
 * involved while playing back encrypted content:
 * <p>
 * <p><img src="../../../images/mediadrm_decryption_sequence.png"
 *         alt="MediaDrm Overview diagram"
 *         border="0" /></p>
 * <p>
 * The app first constructs {@link android.media.MediaExtractor} and
 * {@link android.media.MediaCodec} objects. It accesses the DRM-scheme-identifying UUID,
 * typically from metadata in the content, and uses this UUID to construct an instance
 * of a MediaDrm object that is able to support the DRM scheme required by the content.
 * Crypto schemes are assigned 16 byte UUIDs.  The method {@link #isCryptoSchemeSupported}
 * can be used to query if a given scheme is supported on the device.
 * <p>
 * The app calls {@link #openSession} to generate a sessionId that will uniquely identify
 * the session in subsequent interactions. The app next uses the MediaDrm object to
 * obtain a key request message and send it to the license server, then provide
 * the server's response to the MediaDrm object.
 * <p>
 * Once the app has a sessionId, it can construct a MediaCrypto object from the UUID and
 * sessionId.  The MediaCrypto object is registered with the MediaCodec in the
 * {@link MediaCodec#configure} method to enable the codec to decrypt content.
 * <p>
 * When the app has constructed {@link android.media.MediaExtractor},
 * {@link android.media.MediaCodec} and {@link android.media.MediaCrypto} objects,
 * it proceeds to pull samples from the extractor and queue them into the decoder.  For
 * encrypted content, the samples returned from the extractor remain encrypted, they
 * are only decrypted when the samples are delivered to the decoder.
 * <p>
 * MediaDrm methods throw {@link android.media.MediaDrm.MediaDrmStateException}
 * when a method is called on a MediaDrm object that has had an unrecoverable failure
 * in the DRM plugin or security hardware.
 * {@link android.media.MediaDrm.MediaDrmStateException} extends
 * {@link java.lang.IllegalStateException} with the addition of a developer-readable
 * diagnostic information string associated with the exception.
 * <p>
 * In the event of a mediaserver process crash or restart while a MediaDrm object
 * is active, MediaDrm methods may throw {@link android.media.MediaDrmResetException}.
 * To recover, the app must release the MediaDrm object, then create and initialize
 * a new one.
 * <p>
 * As {@link android.media.MediaDrmResetException} and
 * {@link android.media.MediaDrm.MediaDrmStateException} both extend
 * {@link java.lang.IllegalStateException}, they should be in an earlier catch()
 * block than {@link java.lang.IllegalStateException} if handled separately.
 * <p>
 * <a name="Callbacks"></a>
 * <h3>Callbacks</h3>
 * <p>Applications should register for informational events in order
 * to be informed of key state updates during playback or streaming.
 * Registration for these events is done via a call to
 * {@link #setOnEventListener}. In order to receive the respective
 * callback associated with this listener, applications are required to create
 * MediaDrm objects on a thread with its own Looper running (main UI
 * thread by default has a Looper running).
 */
public final class MediaDrm implements AutoCloseable {

    private static final String TAG = "MediaDrm";

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    private static final String PERMISSION = android.Manifest.permission.ACCESS_DRM_CERTIFICATES;

    private EventHandler mEventHandler;
    private EventHandler mKeyStatusChangeHandler;
    private EventHandler mExpirationUpdateHandler;
    private EventHandler mSessionLostStateHandler;

    private OnEventListener mOnEventListener;
    private OnKeyStatusChangeListener mOnKeyStatusChangeListener;
    private OnExpirationUpdateListener mOnExpirationUpdateListener;
    private OnSessionLostStateListener mOnSessionLostStateListener;

    private final Object mEventLock = new Object();
    private final Object mKeyStatusChangeLock = new Object();
    private final Object mExpirationUpdateLock = new Object();
    private final Object mSessionLostStateLock = new Object();

    private long mNativeContext;

    /**
     * Specify no certificate type
     *
     * @hide - not part of the public API at this time
     */
    public static final int CERTIFICATE_TYPE_NONE = 0;

    /**
     * Specify X.509 certificate type
     *
     * @hide - not part of the public API at this time
     */
    public static final int CERTIFICATE_TYPE_X509 = 1;

    /** @hide */
    @IntDef({
        CERTIFICATE_TYPE_NONE,
        CERTIFICATE_TYPE_X509,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CertificateType {}

    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device.
     * @param uuid The UUID of the crypto scheme.
     */
    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid), null,
                SECURITY_LEVEL_UNKNOWN);
    }

    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device, and whether the DRM plugin is able to handle the
     * media container format specified by mimeType.
     * @param uuid The UUID of the crypto scheme.
     * @param mimeType The MIME type of the media container, e.g. "video/mp4"
     *   or "video/webm"
     */
    public static final boolean isCryptoSchemeSupported(
            @NonNull UUID uuid, @NonNull String mimeType) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid),
                mimeType, SECURITY_LEVEL_UNKNOWN);
    }

    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device, and whether the DRM plugin is able to handle the
     * media container format specified by mimeType at the requested
     * security level.
     *
     * @param uuid The UUID of the crypto scheme.
     * @param mimeType The MIME type of the media container, e.g. "video/mp4"
     *   or "video/webm"
     * @param securityLevel the security level requested
     */
    public static final boolean isCryptoSchemeSupported(
            @NonNull UUID uuid, @NonNull String mimeType, @SecurityLevel int securityLevel) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid), mimeType,
                securityLevel);
    }

    private static final byte[] getByteArrayFromUUID(@NonNull UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];
        for (int i = 0; i < 8; ++i) {
            uuidBytes[i] = (byte)(msb >>> (8 * (7 - i)));
            uuidBytes[8 + i] = (byte)(lsb >>> (8 * (7 - i)));
        }

        return uuidBytes;
    }

    private static final native boolean isCryptoSchemeSupportedNative(
            @NonNull byte[] uuid, @Nullable String mimeType, @SecurityLevel int securityLevel);

    private EventHandler createHandler() {
        Looper looper;
        EventHandler handler;
        if ((looper = Looper.myLooper()) != null) {
            handler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            handler = new EventHandler(this, looper);
        } else {
            handler = null;
        }
        return handler;
    }

    private EventHandler updateHandler(Handler handler) {
        Looper looper;
        EventHandler newHandler = null;
        if (handler != null) {
            looper = handler.getLooper();
        } else {
            looper = Looper.myLooper();
        }
        if (looper != null) {
            if (handler == null || handler.getLooper() != looper) {
                newHandler = new EventHandler(this, looper);
            }
        }
        return newHandler;
    }

    /**
     * Instantiate a MediaDrm object
     *
     * @param uuid The UUID of the crypto scheme.
     *
     * @throws UnsupportedSchemeException if the device does not support the
     * specified scheme UUID
     */
    public MediaDrm(@NonNull UUID uuid) throws UnsupportedSchemeException {
        mEventHandler = createHandler();
        mKeyStatusChangeHandler = createHandler();
        mExpirationUpdateHandler = createHandler();
        mSessionLostStateHandler = createHandler();

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaDrm>(this),
                getByteArrayFromUUID(uuid),  ActivityThread.currentOpPackageName());

        mCloseGuard.open("release");
    }

    /**
     * Thrown when an unrecoverable failure occurs during a MediaDrm operation.
     * Extends java.lang.IllegalStateException with the addition of an error
     * code that may be useful in diagnosing the failure.
     */
    public static final class MediaDrmStateException extends java.lang.IllegalStateException {
        private final int mErrorCode;
        private final String mDiagnosticInfo;

        /**
         * @hide
         */
        public MediaDrmStateException(int errorCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;

            // TODO get this from DRM session
            final String sign = errorCode < 0 ? "neg_" : "";
            mDiagnosticInfo =
                "android.media.MediaDrm.error_" + sign + Math.abs(errorCode);

        }

        /**
         * Retrieve the associated error code
         *
         * @hide
         */
        public int getErrorCode() {
            return mErrorCode;
        }

        /**
         * Retrieve a developer-readable diagnostic information string
         * associated with the exception. Do not show this to end-users,
         * since this string will not be localized or generally comprehensible
         * to end-users.
         */
        @NonNull
        public String getDiagnosticInfo() {
            return mDiagnosticInfo;
        }
    }

    /**
     * Thrown when an error occurs in any method that has a session context.
     */
    public static final class SessionException extends RuntimeException {
        public SessionException(int errorCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
        }

        /**
         * This indicates that apps using MediaDrm sessions are
         * temporarily exceeding the capacity of available crypto
         * resources. The app should retry the operation later.
         */
        public static final int ERROR_RESOURCE_CONTENTION = 1;

        /** @hide */
        @IntDef({
            ERROR_RESOURCE_CONTENTION,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SessionErrorCode {}

        /**
         * Retrieve the error code associated with the SessionException
         */
        @SessionErrorCode
        public int getErrorCode() {
            return mErrorCode;
        }

        private final int mErrorCode;
    }

    /**
     * Register a callback to be invoked when a session expiration update
     * occurs.  The app's OnExpirationUpdateListener will be notified
     * when the expiration time of the keys in the session have changed.
     * @param listener the callback that will be run, or {@code null} to unregister the
     *     previously registered callback.
     * @param handler the handler on which the listener should be invoked, or
     *     {@code null} if the listener should be invoked on the calling thread's looper.
     */
    public void setOnExpirationUpdateListener(
            @Nullable OnExpirationUpdateListener listener, @Nullable Handler handler) {
        synchronized(mExpirationUpdateLock) {
            if (listener != null) {
                mExpirationUpdateHandler = updateHandler(handler);
            }
            mOnExpirationUpdateListener = listener;
        }
    }

    /**
     * Interface definition for a callback to be invoked when a drm session
     * expiration update occurs
     */
    public interface OnExpirationUpdateListener
    {
        /**
         * Called when a session expiration update occurs, to inform the app
         * about the change in expiration time
         *
         * @param md the MediaDrm object on which the event occurred
         * @param sessionId the DRM session ID on which the event occurred
         * @param expirationTime the new expiration time for the keys in the session.
         *     The time is in milliseconds, relative to the Unix epoch.  A time of
         *     0 indicates that the keys never expire.
         */
        void onExpirationUpdate(
                @NonNull MediaDrm md, @NonNull byte[] sessionId, long expirationTime);
    }

    /**
     * Register a callback to be invoked when the state of keys in a session
     * change, e.g. when a license update occurs or when a license expires.
     *
     * @param listener the callback that will be run when key status changes, or
     *     {@code null} to unregister the previously registered callback.
     * @param handler the handler on which the listener should be invoked, or
     *     null if the listener should be invoked on the calling thread's looper.
     */
    public void setOnKeyStatusChangeListener(
            @Nullable OnKeyStatusChangeListener listener, @Nullable Handler handler) {
        synchronized(mKeyStatusChangeLock) {
            if (listener != null) {
                mKeyStatusChangeHandler = updateHandler(handler);
            }
            mOnKeyStatusChangeListener = listener;
        }
    }

    /**
     * Interface definition for a callback to be invoked when the keys in a drm
     * session change states.
     */
    public interface OnKeyStatusChangeListener
    {
        /**
         * Called when the keys in a session change status, such as when the license
         * is renewed or expires.
         *
         * @param md the MediaDrm object on which the event occurred
         * @param sessionId the DRM session ID on which the event occurred
         * @param keyInformation a list of {@link MediaDrm.KeyStatus}
         *     instances indicating the status for each key in the session
         * @param hasNewUsableKey indicates if a key has been added that is usable,
         *     which may trigger an attempt to resume playback on the media stream
         *     if it is currently blocked waiting for a key.
         */
        void onKeyStatusChange(
                @NonNull MediaDrm md, @NonNull byte[] sessionId,
                @NonNull List<KeyStatus> keyInformation,
                boolean hasNewUsableKey);
    }

    /**
     * Register a callback to be invoked when session state has been
     * lost. This event can occur on devices that are not capable of
     * retaining crypto session state across device suspend/resume
     * cycles.  When this event occurs, the session must be closed and
     * a new session opened to resume operation.
     *
     * @param listener the callback that will be run, or {@code null} to unregister the
     *     previously registered callback.
     * @param handler the handler on which the listener should be invoked, or
     *     {@code null} if the listener should be invoked on the calling thread's looper.
     */
    public void setOnSessionLostStateListener(
            @Nullable OnSessionLostStateListener listener, @Nullable Handler handler) {
        synchronized(mSessionLostStateLock) {
            if (listener != null) {
                mSessionLostStateHandler = updateHandler(handler);
            }
            mOnSessionLostStateListener = listener;
        }
    }

    /**
     * Interface definition for a callback to be invoked when the
     * session state has been lost and is now invalid
     */
    public interface OnSessionLostStateListener
    {
        /**
         * Called when session state has lost state, to inform the app
         * about the condition so it can close the session and open a new
         * one to resume operation.
         *
         * @param md the MediaDrm object on which the event occurred
         * @param sessionId the DRM session ID on which the event occurred
         */
        void onSessionLostState(
                @NonNull MediaDrm md, @NonNull byte[] sessionId);
    }

    /**
     * Defines the status of a key.
     * A KeyStatus for each key in a session is provided to the
     * {@link OnKeyStatusChangeListener#onKeyStatusChange}
     * listener.
     */
    public static final class KeyStatus {
        private final byte[] mKeyId;
        private final int mStatusCode;

        /**
         * The key is currently usable to decrypt media data
         */
        public static final int STATUS_USABLE = 0;

        /**
         * The key is no longer usable to decrypt media data because its
         * expiration time has passed.
         */
        public static final int STATUS_EXPIRED = 1;

        /**
         * The key is not currently usable to decrypt media data because its
         * output requirements cannot currently be met.
         */
        public static final int STATUS_OUTPUT_NOT_ALLOWED = 2;

        /**
         * The status of the key is not yet known and is being determined.
         * The status will be updated with the actual status when it has
         * been determined.
         */
        public static final int STATUS_PENDING = 3;

        /**
         * The key is not currently usable to decrypt media data because of an
         * internal error in processing unrelated to input parameters.  This error
         * is not actionable by an app.
         */
        public static final int STATUS_INTERNAL_ERROR = 4;

        /** @hide */
        @IntDef({
            STATUS_USABLE,
            STATUS_EXPIRED,
            STATUS_OUTPUT_NOT_ALLOWED,
            STATUS_PENDING,
            STATUS_INTERNAL_ERROR,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface KeyStatusCode {}

        KeyStatus(@NonNull byte[] keyId, @KeyStatusCode int statusCode) {
            mKeyId = keyId;
            mStatusCode = statusCode;
        }

        /**
         * Returns the status code for the key
         */
        @KeyStatusCode
        public int getStatusCode() { return mStatusCode; }

        /**
         * Returns the id for the key
         */
        @NonNull
        public byte[] getKeyId() { return mKeyId; }
    }

    /**
     * Register a callback to be invoked when an event occurs
     *
     * @param listener the callback that will be run.  Use {@code null} to
     *        stop receiving event callbacks.
     */
    public void setOnEventListener(@Nullable OnEventListener listener)
    {
        synchronized(mEventLock) {
            mOnEventListener = listener;
        }
    }

    /**
     * Interface definition for a callback to be invoked when a drm event
     * occurs
     */
    public interface OnEventListener
    {
        /**
         * Called when an event occurs that requires the app to be notified
         *
         * @param md the MediaDrm object on which the event occurred
         * @param sessionId the DRM session ID on which the event occurred,
         *        or {@code null} if there is no session ID associated with the event.
         * @param event indicates the event type
         * @param extra an secondary error code
         * @param data optional byte array of data that may be associated with the event
         */
        void onEvent(
                @NonNull MediaDrm md, @Nullable byte[] sessionId,
                @DrmEvent int event, int extra,
                @Nullable byte[] data);
    }

    /**
     * This event type indicates that the app needs to request a certificate from
     * the provisioning server.  The request message data is obtained using
     * {@link #getProvisionRequest}
     *
     * @deprecated Handle provisioning via {@link android.media.NotProvisionedException}
     * instead.
     */
    public static final int EVENT_PROVISION_REQUIRED = 1;

    /**
     * This event type indicates that the app needs to request keys from a license
     * server.  The request message data is obtained using {@link #getKeyRequest}.
     */
    public static final int EVENT_KEY_REQUIRED = 2;

    /**
     * This event type indicates that the licensed usage duration for keys in a session
     * has expired.  The keys are no longer valid.
     * @deprecated Use {@link OnKeyStatusChangeListener#onKeyStatusChange}
     * and check for {@link MediaDrm.KeyStatus#STATUS_EXPIRED} in the {@link MediaDrm.KeyStatus}
     * instead.
     */
    public static final int EVENT_KEY_EXPIRED = 3;

    /**
     * This event may indicate some specific vendor-defined condition, see your
     * DRM provider documentation for details
     */
    public static final int EVENT_VENDOR_DEFINED = 4;

    /**
     * This event indicates that a session opened by the app has been reclaimed by the resource
     * manager.
     */
    public static final int EVENT_SESSION_RECLAIMED = 5;

    /** @hide */
    @IntDef({
        EVENT_PROVISION_REQUIRED,
        EVENT_KEY_REQUIRED,
        EVENT_KEY_EXPIRED,
        EVENT_VENDOR_DEFINED,
        EVENT_SESSION_RECLAIMED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DrmEvent {}

    private static final int DRM_EVENT = 200;
    private static final int EXPIRATION_UPDATE = 201;
    private static final int KEY_STATUS_CHANGE = 202;
    private static final int SESSION_LOST_STATE = 203;

    private class EventHandler extends Handler
    {
        private MediaDrm mMediaDrm;

        public EventHandler(@NonNull MediaDrm md, @NonNull Looper looper) {
            super(looper);
            mMediaDrm = md;
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            if (mMediaDrm.mNativeContext == 0) {
                Log.w(TAG, "MediaDrm went away with unhandled events");
                return;
            }
            switch(msg.what) {

            case DRM_EVENT:
                synchronized(mEventLock) {
                    if (mOnEventListener != null) {
                        if (msg.obj != null && msg.obj instanceof Parcel) {
                            Parcel parcel = (Parcel)msg.obj;
                            byte[] sessionId = parcel.createByteArray();
                            if (sessionId.length == 0) {
                                sessionId = null;
                            }
                            byte[] data = parcel.createByteArray();
                            if (data.length == 0) {
                                data = null;
                            }

                            Log.i(TAG, "Drm event (" + msg.arg1 + "," + msg.arg2 + ")");
                            mOnEventListener.onEvent(mMediaDrm, sessionId, msg.arg1, msg.arg2, data);
                        }
                    }
                }
                return;

            case KEY_STATUS_CHANGE:
                synchronized(mKeyStatusChangeLock) {
                    if (mOnKeyStatusChangeListener != null) {
                        if (msg.obj != null && msg.obj instanceof Parcel) {
                            Parcel parcel = (Parcel)msg.obj;
                            byte[] sessionId = parcel.createByteArray();
                            if (sessionId.length > 0) {
                                List<KeyStatus> keyStatusList = keyStatusListFromParcel(parcel);
                                boolean hasNewUsableKey = (parcel.readInt() != 0);

                                Log.i(TAG, "Drm key status changed");
                                mOnKeyStatusChangeListener.onKeyStatusChange(mMediaDrm, sessionId,
                                        keyStatusList, hasNewUsableKey);
                            }
                        }
                    }
                }
                return;

            case EXPIRATION_UPDATE:
                synchronized(mExpirationUpdateLock) {
                    if (mOnExpirationUpdateListener != null) {
                        if (msg.obj != null && msg.obj instanceof Parcel) {
                            Parcel parcel = (Parcel)msg.obj;
                            byte[] sessionId = parcel.createByteArray();
                            if (sessionId.length > 0) {
                                long expirationTime = parcel.readLong();

                                Log.i(TAG, "Drm key expiration update: " + expirationTime);
                                mOnExpirationUpdateListener.onExpirationUpdate(mMediaDrm, sessionId,
                                        expirationTime);
                            }
                        }
                    }
                }
                return;

            case SESSION_LOST_STATE:
                synchronized(mSessionLostStateLock) {
                    if (mOnSessionLostStateListener != null) {
                        if (msg.obj != null && msg.obj instanceof Parcel) {
                            Parcel parcel = (Parcel)msg.obj;
                            byte[] sessionId = parcel.createByteArray();
                            Log.i(TAG, "Drm session lost state event: ");
                            mOnSessionLostStateListener.onSessionLostState(mMediaDrm,
                                    sessionId);
                        }
                    }
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /**
     * Parse a list of KeyStatus objects from an event parcel
     */
    @NonNull
    private List<KeyStatus> keyStatusListFromParcel(@NonNull Parcel parcel) {
        int nelems = parcel.readInt();
        List<KeyStatus> keyStatusList = new ArrayList(nelems);
        while (nelems-- > 0) {
            byte[] keyId = parcel.createByteArray();
            int keyStatusCode = parcel.readInt();
            keyStatusList.add(new KeyStatus(keyId, keyStatusCode));
        }
        return keyStatusList;
    }

    /**
     * This method is called from native code when an event occurs.  This method
     * just uses the EventHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaPlayer object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(@NonNull Object mediadrm_ref,
            int what, int eventType, int extra, @Nullable Object obj)
    {
        MediaDrm md = (MediaDrm)((WeakReference<MediaDrm>)mediadrm_ref).get();
        if (md == null) {
            return;
        }
        switch (what) {
            case DRM_EVENT:
                synchronized(md.mEventLock) {
                    if (md.mEventHandler != null) {
                        Message m = md.mEventHandler.obtainMessage(what, eventType, extra, obj);
                        md.mEventHandler.sendMessage(m);
                    }
                }
                break;
            case EXPIRATION_UPDATE:
                synchronized(md.mExpirationUpdateLock) {
                    if (md.mExpirationUpdateHandler != null) {
                        Message m = md.mExpirationUpdateHandler.obtainMessage(what, obj);
                        md.mExpirationUpdateHandler.sendMessage(m);
                    }
                }
                break;
            case KEY_STATUS_CHANGE:
                synchronized(md.mKeyStatusChangeLock) {
                    if (md.mKeyStatusChangeHandler != null) {
                        Message m = md.mKeyStatusChangeHandler.obtainMessage(what, obj);
                        md.mKeyStatusChangeHandler.sendMessage(m);
                    }
                }
                break;
            case SESSION_LOST_STATE:
                synchronized(md.mSessionLostStateLock) {
                    if (md.mSessionLostStateHandler != null) {
                        Message m = md.mSessionLostStateHandler.obtainMessage(what, obj);
                        md.mSessionLostStateHandler.sendMessage(m);
                    }
                }
                break;
            default:
                Log.e(TAG, "Unknown message type " + what);
                break;
        }
    }

    /**
     * Open a new session with the MediaDrm object. A session ID is returned.
     * By default, sessions are opened at the native security level of the device.
     *
     * @throws NotProvisionedException if provisioning is needed
     * @throws ResourceBusyException if required resources are in use
     */
    @NonNull
    public byte[] openSession() throws NotProvisionedException,
            ResourceBusyException {
        return openSession(getMaxSecurityLevel());
    }

    /**
     * Open a new session at a requested security level. The security level
     * represents the robustness of the device's DRM implementation. By default,
     * sessions are opened at the native security level of the device.
     * Overriding the security level is necessary when the decrypted frames need
     * to be manipulated, such as for image compositing. The security level
     * parameter must be lower than the native level. Reducing the security
     * level will typically limit the content to lower resolutions, as
     * determined by the license policy. If the requested level is not
     * supported, the next lower supported security level will be set. The level
     * can be queried using {@link #getSecurityLevel}. A session
     * ID is returned.
     *
     * @param level the new security level
     * @throws NotProvisionedException if provisioning is needed
     * @throws ResourceBusyException if required resources are in use
     * @throws IllegalArgumentException if the requested security level is
     * higher than the native level or lower than the lowest supported level or
     * if the device does not support specifying the security level when opening
     * a session
     */
    @NonNull
    public native byte[] openSession(@SecurityLevel int level) throws
            NotProvisionedException, ResourceBusyException;

    /**
     * Close a session on the MediaDrm object that was previously opened
     * with {@link #openSession}.
     */
    public native void closeSession(@NonNull byte[] sessionId);

    /**
     * This key request type species that the keys will be for online use, they will
     * not be saved to the device for subsequent use when the device is not connected
     * to a network.
     */
    public static final int KEY_TYPE_STREAMING = 1;

    /**
     * This key request type specifies that the keys will be for offline use, they
     * will be saved to the device for use when the device is not connected to a network.
     */
    public static final int KEY_TYPE_OFFLINE = 2;

    /**
     * This key request type specifies that previously saved offline keys should be released.
     */
    public static final int KEY_TYPE_RELEASE = 3;

    /** @hide */
    @IntDef({
        KEY_TYPE_STREAMING,
        KEY_TYPE_OFFLINE,
        KEY_TYPE_RELEASE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KeyType {}

    /**
     * Contains the opaque data an app uses to request keys from a license server.
     * These request types may or may not be generated by a given plugin. Refer
     * to plugin vendor documentation for more information.
     */
    public static final class KeyRequest {
        private byte[] mData;
        private String mDefaultUrl;
        private int mRequestType;

        /**
         * Key request type is initial license request. A license request
         * is necessary to load keys.
         */
        public static final int REQUEST_TYPE_INITIAL = 0;

        /**
         * Key request type is license renewal. A license request is
         * necessary to prevent the keys from expiring.
         */
        public static final int REQUEST_TYPE_RENEWAL = 1;

        /**
         * Key request type is license release
         */
        public static final int REQUEST_TYPE_RELEASE = 2;

        /**
         * Keys are already loaded and are available for use. No license request is necessary, and
         * no key request data is returned.
         */
        public static final int REQUEST_TYPE_NONE = 3;

        /**
         * Keys have been loaded but an additional license request is needed
         * to update their values.
         */
        public static final int REQUEST_TYPE_UPDATE = 4;

        /** @hide */
        @IntDef({
            REQUEST_TYPE_INITIAL,
            REQUEST_TYPE_RENEWAL,
            REQUEST_TYPE_RELEASE,
            REQUEST_TYPE_NONE,
            REQUEST_TYPE_UPDATE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface RequestType {}

        KeyRequest() {}

        /**
         * Get the opaque message data
         */
        @NonNull
        public byte[] getData() {
            if (mData == null) {
                // this should never happen as mData is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("KeyRequest is not initialized");
            }
            return mData;
        }

        /**
         * Get the default URL to use when sending the key request message to a
         * server, if known.  The app may prefer to use a different license
         * server URL from other sources.
         * This method returns an empty string if the default URL is not known.
         */
        @NonNull
        public String getDefaultUrl() {
            if (mDefaultUrl == null) {
                // this should never happen as mDefaultUrl is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("KeyRequest is not initialized");
            }
            return mDefaultUrl;
        }

        /**
         * Get the type of the request
         */
        @RequestType
        public int getRequestType() { return mRequestType; }
    };

    /**
     * A key request/response exchange occurs between the app and a license server
     * to obtain or release keys used to decrypt encrypted content.
     * <p>
     * getKeyRequest() is used to obtain an opaque key request byte array that is
     * delivered to the license server.  The opaque key request byte array is returned
     * in KeyRequest.data.  The recommended URL to deliver the key request to is
     * returned in KeyRequest.defaultUrl.
     * <p>
     * After the app has received the key request response from the server,
     * it should deliver to the response to the MediaDrm instance using the method
     * {@link #provideKeyResponse}.
     *
     * @param scope may be a sessionId or a keySetId, depending on the specified keyType.
     * When the keyType is KEY_TYPE_STREAMING or KEY_TYPE_OFFLINE,
     * scope should be set to the sessionId the keys will be provided to.  When the keyType
     * is KEY_TYPE_RELEASE, scope should be set to the keySetId of the keys
     * being released. Releasing keys from a device invalidates them for all sessions.
     * @param init container-specific data, its meaning is interpreted based on the
     * mime type provided in the mimeType parameter.  It could contain, for example,
     * the content ID, key ID or other data obtained from the content metadata that is
     * required in generating the key request. May be null when keyType is
     * KEY_TYPE_RELEASE or if the request is a renewal, i.e. not the first key
     * request for the session.
     * @param mimeType identifies the mime type of the content. May be null if the
     * keyType is KEY_TYPE_RELEASE or if the request is a renewal, i.e. not the
     * first key request for the session.
     * @param keyType specifes the type of the request. The request may be to acquire
     * keys for streaming or offline content, or to release previously acquired
     * keys, which are identified by a keySetId.
     * @param optionalParameters are included in the key request message to
     * allow a client application to provide additional message parameters to the server.
     * This may be {@code null} if no additional parameters are to be sent.
     * @throws NotProvisionedException if reprovisioning is needed, due to a
     * problem with the certifcate
     */
    @NonNull
    public native KeyRequest getKeyRequest(
            @NonNull byte[] scope, @Nullable byte[] init,
            @Nullable String mimeType, @KeyType int keyType,
            @Nullable HashMap<String, String> optionalParameters)
            throws NotProvisionedException;


    /**
     * A key response is received from the license server by the app, then it is
     * provided to the MediaDrm instance using provideKeyResponse.  When the
     * response is for an offline key request, a keySetId is returned that can be
     * used to later restore the keys to a new session with the method
     * {@link #restoreKeys}.
     * When the response is for a streaming or release request, an empty byte array
     * is returned.
     *
     * @param scope may be a sessionId or keySetId depending on the type of the
     * response.  Scope should be set to the sessionId when the response is for either
     * streaming or offline key requests.  Scope should be set to the keySetId when
     * the response is for a release request.
     * @param response the byte array response from the server
     * @return If the response is for an offline request, the keySetId for the offline
     * keys will be returned. If the response is for a streaming or release request
     * an empty byte array will be returned.
     *
     * @throws NotProvisionedException if the response indicates that
     * reprovisioning is required
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    @Nullable
    public native byte[] provideKeyResponse(
            @NonNull byte[] scope, @NonNull byte[] response)
            throws NotProvisionedException, DeniedByServerException;


    /**
     * Restore persisted offline keys into a new session.  keySetId identifies the
     * keys to load, obtained from a prior call to {@link #provideKeyResponse}.
     *
     * @param sessionId the session ID for the DRM session
     * @param keySetId identifies the saved key set to restore
     */
    public native void restoreKeys(@NonNull byte[] sessionId, @NonNull byte[] keySetId);

    /**
     * Remove the current keys from a session.
     *
     * @param sessionId the session ID for the DRM session
     */
    public native void removeKeys(@NonNull byte[] sessionId);

    /**
     * Request an informative description of the key status for the session.  The status is
     * in the form of {name, value} pairs.  Since DRM license policies vary by vendor,
     * the specific status field names are determined by each DRM vendor.  Refer to your
     * DRM provider documentation for definitions of the field names for a particular
     * DRM plugin.
     *
     * @param sessionId the session ID for the DRM session
     */
    @NonNull
    public native HashMap<String, String> queryKeyStatus(@NonNull byte[] sessionId);

    /**
     * Contains the opaque data an app uses to request a certificate from a provisioning
     * server
     */
    public static final class ProvisionRequest {
        ProvisionRequest() {}

        /**
         * Get the opaque message data
         */
        @NonNull
        public byte[] getData() {
            if (mData == null) {
                // this should never happen as mData is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("ProvisionRequest is not initialized");
            }
            return mData;
        }

        /**
         * Get the default URL to use when sending the provision request
         * message to a server, if known. The app may prefer to use a different
         * provisioning server URL obtained from other sources.
         * This method returns an empty string if the default URL is not known.
         */
        @NonNull
        public String getDefaultUrl() {
            if (mDefaultUrl == null) {
                // this should never happen as mDefaultUrl is initialized in
                // JNI after construction of the ProvisionRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("ProvisionRequest is not initialized");
            }
            return mDefaultUrl;
        }

        private byte[] mData;
        private String mDefaultUrl;
    }

    /**
     * A provision request/response exchange occurs between the app and a provisioning
     * server to retrieve a device certificate.  If provisionining is required, the
     * EVENT_PROVISION_REQUIRED event will be sent to the event handler.
     * getProvisionRequest is used to obtain the opaque provision request byte array that
     * should be delivered to the provisioning server. The provision request byte array
     * is returned in ProvisionRequest.data. The recommended URL to deliver the provision
     * request to is returned in ProvisionRequest.defaultUrl.
     */
    @NonNull
    public ProvisionRequest getProvisionRequest() {
        return getProvisionRequestNative(CERTIFICATE_TYPE_NONE, "");
    }

    @NonNull
    private native ProvisionRequest getProvisionRequestNative(int certType,
           @NonNull String certAuthority);

    /**
     * After a provision response is received by the app, it is provided to the
     * MediaDrm instance using this method.
     *
     * @param response the opaque provisioning response byte array to provide to the
     * MediaDrm instance.
     *
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    public void provideProvisionResponse(@NonNull byte[] response)
            throws DeniedByServerException {
        provideProvisionResponseNative(response);
    }

    @NonNull
    private native Certificate provideProvisionResponseNative(@NonNull byte[] response)
            throws DeniedByServerException;

    /**
     * The keys in an offline license allow protected content to be played even
     * if the device is not connected to a network. Offline licenses are stored
     * on the device after a key request/response exchange when the key request
     * KeyType is OFFLINE. Normally each app is responsible for keeping track of
     * the keySetIds it has created. If an app loses the keySetId for any stored
     * licenses that it created, however, it must be able to recover the stored
     * keySetIds so those licenses can be removed when they expire or when the
     * app is uninstalled.
     * <p>
     * This method returns a list of the keySetIds for all offline licenses.
     * The offline license keySetId may be used to query the status of an
     * offline license with {@link #getOfflineLicenseState} or remove it with
     * {@link #removeOfflineLicense}.
     *
     * @return a list of offline license keySetIds
     */
    @NonNull
    public native List<byte[]> getOfflineLicenseKeySetIds();

    /**
     * Normally offline licenses are released using a key request/response
     * exchange using {@link #getKeyRequest} where the key type is
     * KEY_TYPE_RELEASE, followed by {@link #provideKeyResponse}. This allows
     * the server to cryptographically confirm that the license has been removed
     * and then adjust the count of offline licenses allocated to the device.
     * <p>
     * In some exceptional situations it may be necessary to directly remove
     * offline licenses without notifying the server, which may be performed
     * using this method.
     *
     * @param keySetId the id of the offline license to remove
     * @throws IllegalArgumentException if the keySetId does not refer to an
     * offline license.
     */
    public native void removeOfflineLicense(@NonNull byte[] keySetId);

    /**
     * Offline license state is unknown, an error occurred while trying
     * to access it.
     */
    public static final int OFFLINE_LICENSE_STATE_UNKNOWN = 0;

    /**
     * Offline license is usable, the keys may be used for decryption.
     */
    public static final int OFFLINE_LICENSE_STATE_USABLE = 1;

    /**
     * Offline license is released, the keys have been marked for
     * release using {@link #getKeyRequest} with KEY_TYPE_RELEASE but
     * the key response has not been received.
     */
    public static final int OFFLINE_LICENSE_STATE_RELEASED = 2;

    /** @hide */
    @IntDef({
        OFFLINE_LICENSE_STATE_UNKNOWN,
        OFFLINE_LICENSE_STATE_USABLE,
        OFFLINE_LICENSE_STATE_RELEASED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OfflineLicenseState {}

    /**
     * Request the state of an offline license. An offline license may be usable
     * or inactive. The keys in a usable offline license are available for
     * decryption. When the offline license state is inactive, the keys have
     * been marked for release using {@link #getKeyRequest} with
     * KEY_TYPE_RELEASE but the key response has not been received. The keys in
     * an inactive offline license are not usable for decryption.
     *
     * @param keySetId selects the offline license
     * @return the offline license state
     * @throws IllegalArgumentException if the keySetId does not refer to an
     * offline license.
     */
    @OfflineLicenseState
    public native int getOfflineLicenseState(@NonNull byte[] keySetId);

    /**
     * Secure stops are a way to enforce limits on the number of concurrent
     * streams per subscriber across devices. They provide secure monitoring of
     * the lifetime of content decryption keys in MediaDrm sessions.
     * <p>
     * A secure stop is written to secure persistent memory when keys are loaded
     * into a MediaDrm session. The secure stop state indicates that the keys
     * are available for use. When playback completes and the keys are removed
     * or the session is destroyed, the secure stop state is updated to indicate
     * that keys are no longer usable.
     * <p>
     * After playback, the app can query the secure stop and send it in a
     * message to the license server confirming that the keys are no longer
     * active. The license server returns a secure stop release response
     * message to the app which then deletes the secure stop from persistent
     * memory using {@link #releaseSecureStops}.
     * <p>
     * Each secure stop has a unique ID that can be used to identify it during
     * enumeration, access and removal.
     * @return a list of all secure stops from secure persistent memory
     */
    @NonNull
    public native List<byte[]> getSecureStops();

    /**
     * Return a list of all secure stop IDs currently in persistent memory.
     * The secure stop ID can be used to access or remove the corresponding
     * secure stop.
     *
     * @return a list of secure stop IDs
     */
    @NonNull
    public native List<byte[]> getSecureStopIds();

    /**
     * Access a specific secure stop given its secure stop ID.
     * Each secure stop has a unique ID.
     *
     * @param ssid the ID of the secure stop to return
     * @return the secure stop identified by ssid
     */
    @NonNull
    public native byte[] getSecureStop(@NonNull byte[] ssid);

    /**
     * Process the secure stop server response message ssRelease.  After
     * authenticating the message, remove the secure stops identified in the
     * response.
     *
     * @param ssRelease the server response indicating which secure stops to release
     */
    public native void releaseSecureStops(@NonNull byte[] ssRelease);

    /**
     * Remove a specific secure stop without requiring a secure stop release message
     * from the license server.
     * @param ssid the ID of the secure stop to remove
     */
    public native void removeSecureStop(@NonNull byte[] ssid);

    /**
     * Remove all secure stops without requiring a secure stop release message from
     * the license server.
     *
     * This method was added in API 28. In API versions 18 through 27,
     * {@link #releaseAllSecureStops} should be called instead. There is no need to
     * do anything for API versions prior to 18.
     */
    public native void removeAllSecureStops();

    /**
     * Remove all secure stops without requiring a secure stop release message from
     * the license server.
     *
     * @deprecated Remove all secure stops using {@link #removeAllSecureStops} instead.
     */
    public void releaseAllSecureStops() {
        removeAllSecureStops();;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HDCP_LEVEL_UNKNOWN, HDCP_NONE, HDCP_V1, HDCP_V2,
                        HDCP_V2_1, HDCP_V2_2, HDCP_V2_3, HDCP_NO_DIGITAL_OUTPUT})
    public @interface HdcpLevel {}


    /**
     * The DRM plugin did not report an HDCP level, or an error
     * occurred accessing it
     */
    public static final int HDCP_LEVEL_UNKNOWN = 0;

    /**
     * HDCP is not supported on this device, content is unprotected
     */
    public static final int HDCP_NONE = 1;

    /**
     * HDCP version 1.0
     */
    public static final int HDCP_V1 = 2;

    /**
     * HDCP version 2.0 Type 1.
     */
    public static final int HDCP_V2 = 3;

    /**
     * HDCP version 2.1 Type 1.
     */
    public static final int HDCP_V2_1 = 4;

    /**
     *  HDCP version 2.2 Type 1.
     */
    public static final int HDCP_V2_2 = 5;

    /**
     *  HDCP version 2.3 Type 1.
     */
    public static final int HDCP_V2_3 = 6;

    /**
     * No digital output, implicitly secure
     */
    public static final int HDCP_NO_DIGITAL_OUTPUT = Integer.MAX_VALUE;

    /**
     * Return the HDCP level negotiated with downstream receivers the
     * device is connected to. If multiple HDCP-capable displays are
     * simultaneously connected to separate interfaces, this method
     * returns the lowest negotiated level of all interfaces.
     * <p>
     * This method should only be used for informational purposes, not for
     * enforcing compliance with HDCP requirements. Trusted enforcement of
     * HDCP policies must be handled by the DRM system.
     * <p>
     * @return the connected HDCP level
     */
    @HdcpLevel
    public native int getConnectedHdcpLevel();

    /**
     * Return the maximum supported HDCP level. The maximum HDCP level is a
     * constant for a given device, it does not depend on downstream receivers
     * that may be connected. If multiple HDCP-capable interfaces are present,
     * it indicates the highest of the maximum HDCP levels of all interfaces.
     * <p>
     * @return the maximum supported HDCP level
     */
    @HdcpLevel
    public native int getMaxHdcpLevel();

    /**
     * Return the number of MediaDrm sessions that are currently opened
     * simultaneously among all MediaDrm instances for the active DRM scheme.
     * @return the number of open sessions.
     */
    public native int getOpenSessionCount();

    /**
     * Return the maximum number of MediaDrm sessions that may be opened
     * simultaneosly among all MediaDrm instances for the active DRM
     * scheme. The maximum number of sessions is not affected by any
     * sessions that may have already been opened.
     * @return maximum sessions.
     */
    public native int getMaxSessionCount();

    /**
     * Security level indicates the robustness of the device's DRM
     * implementation.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SECURITY_LEVEL_UNKNOWN, SECURITY_LEVEL_SW_SECURE_CRYPTO,
            SECURITY_LEVEL_SW_SECURE_DECODE, SECURITY_LEVEL_HW_SECURE_CRYPTO,
            SECURITY_LEVEL_HW_SECURE_DECODE, SECURITY_LEVEL_HW_SECURE_ALL})
    public @interface SecurityLevel {}

    /**
     * The DRM plugin did not report a security level, or an error occurred
     * accessing it
     */
    public static final int SECURITY_LEVEL_UNKNOWN = 0;

    /**
     * DRM key management uses software-based whitebox crypto.
     */
    public static final int SECURITY_LEVEL_SW_SECURE_CRYPTO = 1;

    /**
     * DRM key management and decoding use software-based whitebox crypto.
     */
    public static final int SECURITY_LEVEL_SW_SECURE_DECODE = 2;

    /**
     * DRM key management and crypto operations are performed within a hardware
     * backed trusted execution environment.
     */
    public static final int SECURITY_LEVEL_HW_SECURE_CRYPTO = 3;

    /**
     * DRM key management, crypto operations and decoding of content are
     * performed within a hardware backed trusted execution environment.
     */
    public static final int SECURITY_LEVEL_HW_SECURE_DECODE = 4;

    /**
     * DRM key management, crypto operations, decoding of content and all
     * handling of the media (compressed and uncompressed) is handled within a
     * hardware backed trusted execution environment.
     */
    public static final int SECURITY_LEVEL_HW_SECURE_ALL = 5;

    /**
     * Indicates that the maximum security level supported by the device should
     * be used when opening a session. This is the default security level
     * selected when a session is opened.
     * @hide
     */
    public static final int SECURITY_LEVEL_MAX = 6;

    /**
     * Returns a value that may be passed as a parameter to {@link #openSession(int)}
     * requesting that the session be opened at the maximum security level of
     * the device.
     */
    public static final int getMaxSecurityLevel() {
        return SECURITY_LEVEL_MAX;
    }

    /**
     * Return the current security level of a session. A session has an initial
     * security level determined by the robustness of the DRM system's
     * implementation on the device. The security level may be changed at the
     * time a session is opened using {@link #openSession}.
     * @param sessionId the session to query.
     * <p>
     * @return the security level of the session
     */
    @SecurityLevel
    public native int getSecurityLevel(@NonNull byte[] sessionId);

    /**
     * String property name: identifies the maker of the DRM plugin
     */
    public static final String PROPERTY_VENDOR = "vendor";

    /**
     * String property name: identifies the version of the DRM plugin
     */
    public static final String PROPERTY_VERSION = "version";

    /**
     * String property name: describes the DRM plugin
     */
    public static final String PROPERTY_DESCRIPTION = "description";

    /**
     * String property name: a comma-separated list of cipher and mac algorithms
     * supported by CryptoSession.  The list may be empty if the DRM
     * plugin does not support CryptoSession operations.
     */
    public static final String PROPERTY_ALGORITHMS = "algorithms";

    /** @hide */
    @StringDef(prefix = { "PROPERTY_" }, value = {
        PROPERTY_VENDOR,
        PROPERTY_VERSION,
        PROPERTY_DESCRIPTION,
        PROPERTY_ALGORITHMS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StringProperty {}

    /**
     * Read a MediaDrm String property value, given the property name string.
     * <p>
     * Standard fields names are:
     * {@link #PROPERTY_VENDOR}, {@link #PROPERTY_VERSION},
     * {@link #PROPERTY_DESCRIPTION}, {@link #PROPERTY_ALGORITHMS}
     */
    @NonNull
    public native String getPropertyString(@NonNull @StringProperty String propertyName);

    /**
     * Set a MediaDrm String property value, given the property name string
     * and new value for the property.
     */
    public native void setPropertyString(@NonNull @StringProperty String propertyName,
            @NonNull String value);

    /**
     * Byte array property name: the device unique identifier is established during
     * device provisioning and provides a means of uniquely identifying each device.
     */
    public static final String PROPERTY_DEVICE_UNIQUE_ID = "deviceUniqueId";

    /** @hide */
    @StringDef(prefix = { "PROPERTY_" }, value = {
        PROPERTY_DEVICE_UNIQUE_ID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ArrayProperty {}

    /**
     * Read a MediaDrm byte array property value, given the property name string.
     * <p>
     * Standard fields names are {@link #PROPERTY_DEVICE_UNIQUE_ID}
     */
    @NonNull
    public native byte[] getPropertyByteArray(@ArrayProperty String propertyName);

    /**
    * Set a MediaDrm byte array property value, given the property name string
    * and new value for the property.
    */
    public native void setPropertyByteArray(@NonNull @ArrayProperty
            String propertyName, @NonNull byte[] value);

    private static final native void setCipherAlgorithmNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId, @NonNull String algorithm);

    private static final native void setMacAlgorithmNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId, @NonNull String algorithm);

    @NonNull
    private static final native byte[] encryptNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId,
            @NonNull byte[] keyId, @NonNull byte[] input, @NonNull byte[] iv);

    @NonNull
    private static final native byte[] decryptNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId,
            @NonNull byte[] keyId, @NonNull byte[] input, @NonNull byte[] iv);

    @NonNull
    private static final native byte[] signNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId,
            @NonNull byte[] keyId, @NonNull byte[] message);

    private static final native boolean verifyNative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId,
            @NonNull byte[] keyId, @NonNull byte[] message, @NonNull byte[] signature);

    /**
     * Return Metrics data about the current MediaDrm instance.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for this instance of MediaDrm.
     * The attributes are described in {@link MetricsConstants}.
     *
     * Additional vendor-specific fields may also be present in
     * the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = getMetricsNative();
        return bundle;
    }

    private native PersistableBundle getMetricsNative();

    /**
     * In addition to supporting decryption of DASH Common Encrypted Media, the
     * MediaDrm APIs provide the ability to securely deliver session keys from
     * an operator's session key server to a client device, based on the factory-installed
     * root of trust, and then perform encrypt, decrypt, sign and verify operations
     * with the session key on arbitrary user data.
     * <p>
     * The CryptoSession class implements generic encrypt/decrypt/sign/verify methods
     * based on the established session keys.  These keys are exchanged using the
     * getKeyRequest/provideKeyResponse methods.
     * <p>
     * Applications of this capability could include securing various types of
     * purchased or private content, such as applications, books and other media,
     * photos or media delivery protocols.
     * <p>
     * Operators can create session key servers that are functionally similar to a
     * license key server, except that instead of receiving license key requests and
     * providing encrypted content keys which are used specifically to decrypt A/V media
     * content, the session key server receives session key requests and provides
     * encrypted session keys which can be used for general purpose crypto operations.
     * <p>
     * A CryptoSession is obtained using {@link #getCryptoSession}
     */
    public final class CryptoSession {
        private byte[] mSessionId;

        CryptoSession(@NonNull byte[] sessionId,
                      @NonNull String cipherAlgorithm,
                      @NonNull String macAlgorithm)
        {
            mSessionId = sessionId;
            setCipherAlgorithmNative(MediaDrm.this, sessionId, cipherAlgorithm);
            setMacAlgorithmNative(MediaDrm.this, sessionId, macAlgorithm);
        }

        /**
         * Encrypt data using the CryptoSession's cipher algorithm
         *
         * @param keyid specifies which key to use
         * @param input the data to encrypt
         * @param iv the initialization vector to use for the cipher
         */
        @NonNull
        public byte[] encrypt(
                @NonNull byte[] keyid, @NonNull byte[] input, @NonNull byte[] iv) {
            return encryptNative(MediaDrm.this, mSessionId, keyid, input, iv);
        }

        /**
         * Decrypt data using the CryptoSessions's cipher algorithm
         *
         * @param keyid specifies which key to use
         * @param input the data to encrypt
         * @param iv the initialization vector to use for the cipher
         */
        @NonNull
        public byte[] decrypt(
                @NonNull byte[] keyid, @NonNull byte[] input, @NonNull byte[] iv) {
            return decryptNative(MediaDrm.this, mSessionId, keyid, input, iv);
        }

        /**
         * Sign data using the CryptoSessions's mac algorithm.
         *
         * @param keyid specifies which key to use
         * @param message the data for which a signature is to be computed
         */
        @NonNull
        public byte[] sign(@NonNull byte[] keyid, @NonNull byte[] message) {
            return signNative(MediaDrm.this, mSessionId, keyid, message);
        }

        /**
         * Verify a signature using the CryptoSessions's mac algorithm. Return true
         * if the signatures match, false if they do no.
         *
         * @param keyid specifies which key to use
         * @param message the data to verify
         * @param signature the reference signature which will be compared with the
         *        computed signature
         */
        public boolean verify(
                @NonNull byte[] keyid, @NonNull byte[] message, @NonNull byte[] signature) {
            return verifyNative(MediaDrm.this, mSessionId, keyid, message, signature);
        }
    };

    /**
     * Obtain a CryptoSession object which can be used to encrypt, decrypt,
     * sign and verify messages or data using the session keys established
     * for the session using methods {@link #getKeyRequest} and
     * {@link #provideKeyResponse} using a session key server.
     *
     * @param sessionId the session ID for the session containing keys
     * to be used for encrypt, decrypt, sign and/or verify
     * @param cipherAlgorithm the algorithm to use for encryption and
     * decryption ciphers. The algorithm string conforms to JCA Standard
     * Names for Cipher Transforms and is case insensitive.  For example
     * "AES/CBC/NoPadding".
     * @param macAlgorithm the algorithm to use for sign and verify
     * The algorithm string conforms to JCA Standard Names for Mac
     * Algorithms and is case insensitive.  For example "HmacSHA256".
     * <p>
     * The list of supported algorithms for a DRM plugin can be obtained
     * using the method {@link #getPropertyString} with the property name
     * "algorithms".
     */
    public CryptoSession getCryptoSession(
            @NonNull byte[] sessionId,
            @NonNull String cipherAlgorithm, @NonNull String macAlgorithm)
    {
        return new CryptoSession(sessionId, cipherAlgorithm, macAlgorithm);
    }

    /**
     * Contains the opaque data an app uses to request a certificate from a provisioning
     * server
     *
     * @hide - not part of the public API at this time
     */
    public static final class CertificateRequest {
        private byte[] mData;
        private String mDefaultUrl;

        CertificateRequest(@NonNull byte[] data, @NonNull String defaultUrl) {
            mData = data;
            mDefaultUrl = defaultUrl;
        }

        /**
         * Get the opaque message data
         */
        @NonNull
        @UnsupportedAppUsage
        public byte[] getData() { return mData; }

        /**
         * Get the default URL to use when sending the certificate request
         * message to a server, if known. The app may prefer to use a different
         * certificate server URL obtained from other sources.
         */
        @NonNull
        @UnsupportedAppUsage
        public String getDefaultUrl() { return mDefaultUrl; }
    }

    /**
     * Generate a certificate request, specifying the certificate type
     * and authority. The response received should be passed to
     * provideCertificateResponse.
     *
     * @param certType Specifies the certificate type.
     *
     * @param certAuthority is passed to the certificate server to specify
     * the chain of authority.
     *
     * @hide - not part of the public API at this time
     */
    @NonNull
    @UnsupportedAppUsage
    public CertificateRequest getCertificateRequest(
            @CertificateType int certType, @NonNull String certAuthority)
    {
        ProvisionRequest provisionRequest = getProvisionRequestNative(certType, certAuthority);
        return new CertificateRequest(provisionRequest.getData(),
                provisionRequest.getDefaultUrl());
    }

    /**
     * Contains the wrapped private key and public certificate data associated
     * with a certificate.
     *
     * @hide - not part of the public API at this time
     */
    public static final class Certificate {
        Certificate() {}

        /**
         * Get the wrapped private key data
         */
        @NonNull
        @UnsupportedAppUsage
        public byte[] getWrappedPrivateKey() {
            if (mWrappedKey == null) {
                // this should never happen as mWrappedKey is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("Certificate is not initialized");
            }
            return mWrappedKey;
        }

        /**
         * Get the PEM-encoded certificate chain
         */
        @NonNull
        @UnsupportedAppUsage
        public byte[] getContent() {
            if (mCertificateData == null) {
                // this should never happen as mCertificateData is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("Certificate is not initialized");
            }
            return mCertificateData;
        }

        private byte[] mWrappedKey;
        private byte[] mCertificateData;
    }


    /**
     * Process a response from the certificate server.  The response
     * is obtained from an HTTP Post to the url provided by getCertificateRequest.
     * <p>
     * The public X509 certificate chain and wrapped private key are returned
     * in the returned Certificate objec.  The certificate chain is in PEM format.
     * The wrapped private key should be stored in application private
     * storage, and used when invoking the signRSA method.
     *
     * @param response the opaque certificate response byte array to provide to the
     * MediaDrm instance.
     *
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     *
     * @hide - not part of the public API at this time
     */
    @NonNull
    @UnsupportedAppUsage
    public Certificate provideCertificateResponse(@NonNull byte[] response)
            throws DeniedByServerException {
        return provideProvisionResponseNative(response);
    }

    @NonNull
    private static final native byte[] signRSANative(
            @NonNull MediaDrm drm, @NonNull byte[] sessionId,
            @NonNull String algorithm, @NonNull byte[] wrappedKey, @NonNull byte[] message);

    /**
     * Sign data using an RSA key
     *
     * @param sessionId a sessionId obtained from openSession on the MediaDrm object
     * @param algorithm the signing algorithm to use, e.g. "PKCS1-BlockType1"
     * @param wrappedKey - the wrapped (encrypted) RSA private key obtained
     * from provideCertificateResponse
     * @param message the data for which a signature is to be computed
     *
     * @hide - not part of the public API at this time
     */
    @NonNull
    @UnsupportedAppUsage
    public byte[] signRSA(
            @NonNull byte[] sessionId, @NonNull String algorithm,
            @NonNull byte[] wrappedKey, @NonNull byte[] message) {
        return signRSANative(this, sessionId, algorithm, wrappedKey, message);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Releases resources associated with the current session of
     * MediaDrm. It is considered good practice to call this method when
     * the {@link MediaDrm} object is no longer needed in your
     * application. After this method is called, {@link MediaDrm} is no
     * longer usable since it has lost all of its required resource.
     *
     * This method was added in API 28. In API versions 18 through 27, release()
     * should be called instead. There is no need to do anything for API
     * versions prior to 18.
     */
    @Override
    public void close() {
        release();
    }

    /**
     * @deprecated replaced by {@link #close()}.
     */
    @Deprecated
    public void release() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            native_release();
        }
    }

    /** @hide */
    public native final void native_release();

    private static native final void native_init();

    private native final void native_setup(Object mediadrm_this, byte[] uuid,
            String appPackageName);

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    /**
     * Definitions for the metrics that are reported via the
     * {@link #getMetrics} call.
     */
    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        /**
         * Key to extract the number of successful {@link #openSession} calls
         * from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String OPEN_SESSION_OK_COUNT
            = "drm.mediadrm.open_session.ok.count";

        /**
         * Key to extract the number of failed {@link #openSession} calls
         * from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String OPEN_SESSION_ERROR_COUNT
            = "drm.mediadrm.open_session.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #openSession} calls. The key is used to lookup the list
         * in the {@link PersistableBundle} returned by a {@link #getMetrics}
         * call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String OPEN_SESSION_ERROR_LIST
            = "drm.mediadrm.open_session.error.list";

        /**
         * Key to extract the number of successful {@link #closeSession} calls
         * from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String CLOSE_SESSION_OK_COUNT
            = "drm.mediadrm.close_session.ok.count";

        /**
         * Key to extract the number of failed {@link #closeSession} calls
         * from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String CLOSE_SESSION_ERROR_COUNT
            = "drm.mediadrm.close_session.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #closeSession} calls. The key is used to lookup the list
         * in the {@link PersistableBundle} returned by a {@link #getMetrics}
         * call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String CLOSE_SESSION_ERROR_LIST
            = "drm.mediadrm.close_session.error.list";

        /**
         * Key to extract the start times of sessions. Times are
         * represented as milliseconds since epoch (1970-01-01T00:00:00Z).
         * The start times are returned from the {@link PersistableBundle}
         * from a {@link #getMetrics} call.
         * The start times are returned as another {@link PersistableBundle}
         * containing the session ids as keys and the start times as long
         * values. Use {@link android.os.BaseBundle#keySet} to get the list of
         * session ids, and then {@link android.os.BaseBundle#getLong} to get
         * the start time for each session.
         */
        public static final String SESSION_START_TIMES_MS
            = "drm.mediadrm.session_start_times_ms";

        /**
         * Key to extract the end times of sessions. Times are
         * represented as milliseconds since epoch (1970-01-01T00:00:00Z).
         * The end times are returned from the {@link PersistableBundle}
         * from a {@link #getMetrics} call.
         * The end times are returned as another {@link PersistableBundle}
         * containing the session ids as keys and the end times as long
         * values. Use {@link android.os.BaseBundle#keySet} to get the list of
         * session ids, and then {@link android.os.BaseBundle#getLong} to get
         * the end time for each session.
         */
        public static final String SESSION_END_TIMES_MS
            = "drm.mediadrm.session_end_times_ms";

        /**
         * Key to extract the number of successful {@link #getKeyRequest} calls
         * from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_KEY_REQUEST_OK_COUNT
            = "drm.mediadrm.get_key_request.ok.count";

        /**
         * Key to extract the number of failed {@link #getKeyRequest}
         * calls from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_KEY_REQUEST_ERROR_COUNT
            = "drm.mediadrm.get_key_request.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #getKeyRequest} calls. The key is used to lookup the list
         * in the {@link PersistableBundle} returned by a {@link #getMetrics}
         * call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String GET_KEY_REQUEST_ERROR_LIST
            = "drm.mediadrm.get_key_request.error.list";

        /**
         * Key to extract the average time in microseconds of calls to
         * {@link #getKeyRequest}. The value is retrieved from the
         * {@link PersistableBundle} returned from {@link #getMetrics}.
         * The time is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_KEY_REQUEST_OK_TIME_MICROS
            = "drm.mediadrm.get_key_request.ok.average_time_micros";

        /**
         * Key to extract the number of successful {@link #provideKeyResponse}
         * calls from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String PROVIDE_KEY_RESPONSE_OK_COUNT
            = "drm.mediadrm.provide_key_response.ok.count";

        /**
         * Key to extract the number of failed {@link #provideKeyResponse}
         * calls from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String PROVIDE_KEY_RESPONSE_ERROR_COUNT
            = "drm.mediadrm.provide_key_response.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #provideKeyResponse} calls. The key is used to lookup the
         * list in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String PROVIDE_KEY_RESPONSE_ERROR_LIST
            = "drm.mediadrm.provide_key_response.error.list";

        /**
         * Key to extract the average time in microseconds of calls to
         * {@link #provideKeyResponse}. The valus is retrieved from the
         * {@link PersistableBundle} returned from {@link #getMetrics}.
         * The time is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String PROVIDE_KEY_RESPONSE_OK_TIME_MICROS
            = "drm.mediadrm.provide_key_response.ok.average_time_micros";

        /**
         * Key to extract the number of successful {@link #getProvisionRequest}
         * calls from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_PROVISION_REQUEST_OK_COUNT
            = "drm.mediadrm.get_provision_request.ok.count";

        /**
         * Key to extract the number of failed {@link #getProvisionRequest}
         * calls from the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_PROVISION_REQUEST_ERROR_COUNT
            = "drm.mediadrm.get_provision_request.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #getProvisionRequest} calls. The key is used to lookup the
         * list in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String GET_PROVISION_REQUEST_ERROR_LIST
            = "drm.mediadrm.get_provision_request.error.list";

        /**
         * Key to extract the number of successful
         * {@link #provideProvisionResponse} calls from the
         * {@link PersistableBundle} returned by a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String PROVIDE_PROVISION_RESPONSE_OK_COUNT
            = "drm.mediadrm.provide_provision_response.ok.count";

        /**
         * Key to extract the number of failed
         * {@link #provideProvisionResponse} calls from the
         * {@link PersistableBundle} returned by a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String PROVIDE_PROVISION_RESPONSE_ERROR_COUNT
            = "drm.mediadrm.provide_provision_response.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #provideProvisionResponse} calls. The key is used to lookup
         * the list in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String PROVIDE_PROVISION_RESPONSE_ERROR_LIST
            = "drm.mediadrm.provide_provision_response.error.list";

        /**
         * Key to extract the number of successful
         * {@link #getPropertyByteArray} calls were made with the
         * {@link #PROPERTY_DEVICE_UNIQUE_ID} value. The key is used to lookup
         * the value in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_DEVICE_UNIQUE_ID_OK_COUNT
            = "drm.mediadrm.get_device_unique_id.ok.count";

        /**
         * Key to extract the number of failed
         * {@link #getPropertyByteArray} calls were made with the
         * {@link #PROPERTY_DEVICE_UNIQUE_ID} value. The key is used to lookup
         * the value in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String GET_DEVICE_UNIQUE_ID_ERROR_COUNT
            = "drm.mediadrm.get_device_unique_id.error.count";

        /**
         * Key to extract the list of error codes that were returned from
         * {@link #getPropertyByteArray} calls with the
         * {@link #PROPERTY_DEVICE_UNIQUE_ID} value. The key is used to lookup
         * the list in the {@link PersistableBundle} returned by a
         * {@link #getMetrics} call.
         * The list is an array of Long values
         * ({@link android.os.BaseBundle#getLongArray}).
         */
        public static final String GET_DEVICE_UNIQUE_ID_ERROR_LIST
            = "drm.mediadrm.get_device_unique_id.error.list";

        /**
         * Key to extraact the count of {@link KeyStatus#STATUS_EXPIRED} events
         * that occured. The count is extracted from the
         * {@link PersistableBundle} returned from a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String KEY_STATUS_EXPIRED_COUNT
            = "drm.mediadrm.key_status.EXPIRED.count";

        /**
         * Key to extract the count of {@link KeyStatus#STATUS_INTERNAL_ERROR}
         * events that occured. The count is extracted from the
         * {@link PersistableBundle} returned from a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String KEY_STATUS_INTERNAL_ERROR_COUNT
            = "drm.mediadrm.key_status.INTERNAL_ERROR.count";

        /**
         * Key to extract the count of
         * {@link KeyStatus#STATUS_OUTPUT_NOT_ALLOWED} events that occured.
         * The count is extracted from the
         * {@link PersistableBundle} returned from a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String KEY_STATUS_OUTPUT_NOT_ALLOWED_COUNT
            = "drm.mediadrm.key_status_change.OUTPUT_NOT_ALLOWED.count";

        /**
         * Key to extract the count of {@link KeyStatus#STATUS_PENDING}
         * events that occured. The count is extracted from the
         * {@link PersistableBundle} returned from a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String KEY_STATUS_PENDING_COUNT
            = "drm.mediadrm.key_status_change.PENDING.count";

        /**
         * Key to extract the count of {@link KeyStatus#STATUS_USABLE}
         * events that occured. The count is extracted from the
         * {@link PersistableBundle} returned from a {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String KEY_STATUS_USABLE_COUNT
            = "drm.mediadrm.key_status_change.USABLE.count";

        /**
         * Key to extract the count of {@link OnEventListener#onEvent}
         * calls of type PROVISION_REQUIRED occured. The count is
         * extracted from the {@link PersistableBundle} returned from a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String EVENT_PROVISION_REQUIRED_COUNT
            = "drm.mediadrm.event.PROVISION_REQUIRED.count";

        /**
         * Key to extract the count of {@link OnEventListener#onEvent}
         * calls of type KEY_NEEDED occured. The count is
         * extracted from the {@link PersistableBundle} returned from a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String EVENT_KEY_NEEDED_COUNT
            = "drm.mediadrm.event.KEY_NEEDED.count";

        /**
         * Key to extract the count of {@link OnEventListener#onEvent}
         * calls of type KEY_EXPIRED occured. The count is
         * extracted from the {@link PersistableBundle} returned from a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String EVENT_KEY_EXPIRED_COUNT
            = "drm.mediadrm.event.KEY_EXPIRED.count";

        /**
         * Key to extract the count of {@link OnEventListener#onEvent}
         * calls of type VENDOR_DEFINED. The count is
         * extracted from the {@link PersistableBundle} returned from a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String EVENT_VENDOR_DEFINED_COUNT
            = "drm.mediadrm.event.VENDOR_DEFINED.count";

        /**
         * Key to extract the count of {@link OnEventListener#onEvent}
         * calls of type SESSION_RECLAIMED. The count is
         * extracted from the {@link PersistableBundle} returned from a
         * {@link #getMetrics} call.
         * The count is a Long value ({@link android.os.BaseBundle#getLong}).
         */
        public static final String EVENT_SESSION_RECLAIMED_COUNT
            = "drm.mediadrm.event.SESSION_RECLAIMED.count";
    }
}
