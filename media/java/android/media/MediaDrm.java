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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.util.Log;

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
 * {@link MediaCodec.#configure} method to enable the codec to decrypt content.
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
public final class MediaDrm {

    private static final String TAG = "MediaDrm";

    private static final String PERMISSION = android.Manifest.permission.ACCESS_DRM_CERTIFICATES;

    private EventHandler mEventHandler;
    private EventHandler mOnKeyStatusChangeEventHandler;
    private EventHandler mOnExpirationUpdateEventHandler;
    private OnEventListener mOnEventListener;
    private OnKeyStatusChangeListener mOnKeyStatusChangeListener;
    private OnExpirationUpdateListener mOnExpirationUpdateListener;

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
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid), null);
    }

    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device, and whether the drm plugin is able to handle the
     * media container format specified by mimeType.
     * @param uuid The UUID of the crypto scheme.
     * @param mimeType The MIME type of the media container, e.g. "video/mp4"
     *   or "video/webm"
     */
    public static final boolean isCryptoSchemeSupported(
            @NonNull UUID uuid, @NonNull String mimeType) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid), mimeType);
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
            @NonNull byte[] uuid, @Nullable String mimeType);

    /**
     * Instantiate a MediaDrm object
     *
     * @param uuid The UUID of the crypto scheme.
     *
     * @throws UnsupportedSchemeException if the device does not support the
     * specified scheme UUID
     */
    public MediaDrm(@NonNull UUID uuid) throws UnsupportedSchemeException {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaDrm>(this),
                getByteArrayFromUUID(uuid));
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
        if (listener != null) {
            Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
            if (looper != null) {
                if (mEventHandler == null || mEventHandler.getLooper() != looper) {
                    mEventHandler = new EventHandler(this, looper);
                }
            }
        }
        mOnExpirationUpdateListener = listener;
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
        if (listener != null) {
            Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
            if (looper != null) {
                if (mEventHandler == null || mEventHandler.getLooper() != looper) {
                    mEventHandler = new EventHandler(this, looper);
                }
            }
        }
        mOnKeyStatusChangeListener = listener;
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
         * @return one of {@link #STATUS_USABLE}, {@link #STATUS_EXPIRED},
         * {@link #STATUS_OUTPUT_NOT_ALLOWED}, {@link #STATUS_PENDING}
         * or {@link #STATUS_INTERNAL_ERROR}.
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
        mOnEventListener = listener;
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
                return;

            case KEY_STATUS_CHANGE:
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
                return;

            case EXPIRATION_UPDATE:
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
        if (md.mEventHandler != null) {
            Message m = md.mEventHandler.obtainMessage(what, eventType, extra, obj);
            md.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Open a new session with the MediaDrm object.  A session ID is returned.
     *
     * @throws NotProvisionedException if provisioning is needed
     * @throws ResourceBusyException if required resources are in use
     */
    @NonNull
    public native byte[] openSession() throws NotProvisionedException,
            ResourceBusyException;

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
     * Contains the opaque data an app uses to request keys from a license server
     */
    public static final class KeyRequest {
        private byte[] mData;
        private String mDefaultUrl;
        private int mRequestType;

        /**
         * Key request type is initial license request
         */
        public static final int REQUEST_TYPE_INITIAL = 0;

        /**
         * Key request type is license renewal
         */
        public static final int REQUEST_TYPE_RENEWAL = 1;

        /**
         * Key request type is license release
         */
        public static final int REQUEST_TYPE_RELEASE = 2;

        /** @hide */
        @IntDef({
            REQUEST_TYPE_INITIAL,
            REQUEST_TYPE_RENEWAL,
            REQUEST_TYPE_RELEASE,
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
         * @return one of {@link #REQUEST_TYPE_INITIAL},
         * {@link #REQUEST_TYPE_RENEWAL} or {@link #REQUEST_TYPE_RELEASE}
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
     * it should deliver to the response to the DRM engine plugin using the method
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
     * required in generating the key request. init may be null when keyType is
     * KEY_TYPE_RELEASE.
     * @param mimeType identifies the mime type of the content
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
     * provided to the DRM engine plugin using provideKeyResponse.  When the
     * response is for an offline key request, a keySetId is returned that can be
     * used to later restore the keys to a new session with the method
     * {@link #restoreKeys}.
     * When the response is for a streaming or release request, null is returned.
     *
     * @param scope may be a sessionId or keySetId depending on the type of the
     * response.  Scope should be set to the sessionId when the response is for either
     * streaming or offline key requests.  Scope should be set to the keySetId when
     * the response is for a release request.
     * @param response the byte array response from the server
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
     * DRM engine plugin.
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
     * After a provision response is received by the app, it is provided to the DRM
     * engine plugin using this method.
     *
     * @param response the opaque provisioning response byte array to provide to the
     * DRM engine plugin.
     *
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    public void provideProvisionResponse(@NonNull byte[] response)
            throws DeniedByServerException {
        provideProvisionResponseNative(response);
    }

    @NonNull
    /* could there be a valid response with 0-sized certificate or key? */
    private native Certificate provideProvisionResponseNative(@NonNull byte[] response)
            throws DeniedByServerException;

    /**
     * A means of enforcing limits on the number of concurrent streams per subscriber
     * across devices is provided via SecureStop. This is achieved by securely
     * monitoring the lifetime of sessions.
     * <p>
     * Information from the server related to the current playback session is written
     * to persistent storage on the device when each MediaCrypto object is created.
     * <p>
     * In the normal case, playback will be completed, the session destroyed and the
     * Secure Stops will be queried. The app queries secure stops and forwards the
     * secure stop message to the server which verifies the signature and notifies the
     * server side database that the session destruction has been confirmed. The persisted
     * record on the client is only removed after positive confirmation that the server
     * received the message using releaseSecureStops().
     */
    @NonNull
    public native List<byte[]> getSecureStops();

    /**
     * Access secure stop by secure stop ID.
     *
     * @param ssid - The secure stop ID provided by the license server.
     */
    @NonNull
    public native byte[] getSecureStop(@NonNull byte[] ssid);

    /**
     * Process the SecureStop server response message ssRelease.  After authenticating
     * the message, remove the SecureStops identified in the response.
     *
     * @param ssRelease the server response indicating which secure stops to release
     */
    public native void releaseSecureStops(@NonNull byte[] ssRelease);

    /**
     * Remove all secure stops without requiring interaction with the server.
     */
     public native void releaseAllSecureStops();

    /**
     * String property name: identifies the maker of the DRM engine plugin
     */
    public static final String PROPERTY_VENDOR = "vendor";

    /**
     * String property name: identifies the version of the DRM engine plugin
     */
    public static final String PROPERTY_VERSION = "version";

    /**
     * String property name: describes the DRM engine plugin
     */
    public static final String PROPERTY_DESCRIPTION = "description";

    /**
     * String property name: a comma-separated list of cipher and mac algorithms
     * supported by CryptoSession.  The list may be empty if the DRM engine
     * plugin does not support CryptoSession operations.
     */
    public static final String PROPERTY_ALGORITHMS = "algorithms";

    /** @hide */
    @StringDef({
        PROPERTY_VENDOR,
        PROPERTY_VERSION,
        PROPERTY_DESCRIPTION,
        PROPERTY_ALGORITHMS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StringProperty {}

    /**
     * Read a DRM engine plugin String property value, given the property name string.
     * <p>
     * Standard fields names are:
     * {@link #PROPERTY_VENDOR}, {@link #PROPERTY_VERSION},
     * {@link #PROPERTY_DESCRIPTION}, {@link #PROPERTY_ALGORITHMS}
     */
    /* FIXME this throws IllegalStateException for invalid property names */
    @NonNull
    public native String getPropertyString(@NonNull @StringProperty String propertyName);

    /**
     * Byte array property name: the device unique identifier is established during
     * device provisioning and provides a means of uniquely identifying each device.
     */
    /* FIXME this throws IllegalStateException for invalid property names */
    public static final String PROPERTY_DEVICE_UNIQUE_ID = "deviceUniqueId";

    /** @hide */
    @StringDef({
        PROPERTY_DEVICE_UNIQUE_ID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ArrayProperty {}

    /**
     * Read a DRM engine plugin byte array property value, given the property name string.
     * <p>
     * Standard fields names are {@link #PROPERTY_DEVICE_UNIQUE_ID}
     */
    @NonNull
    public native byte[] getPropertyByteArray(@ArrayProperty String propertyName);

    /**
     * Set a DRM engine plugin String property value.
     */
    public native void setPropertyString(
            @StringProperty String propertyName, @NonNull String value);

    /**
     * Set a DRM engine plugin byte array property value.
     */
    public native void setPropertyByteArray(
            @ArrayProperty String propertyName, @NonNull byte[] value);

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
     * The list of supported algorithms for a DRM engine plugin can be obtained
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
        public byte[] getData() { return mData; }

        /**
         * Get the default URL to use when sending the certificate request
         * message to a server, if known. The app may prefer to use a different
         * certificate server URL obtained from other sources.
         */
        @NonNull
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
        public byte[] getWrappedPrivateKey() {
            if (mWrappedKey == null) {
                // this should never happen as mWrappedKey is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("Cerfificate is not initialized");
            }
            return mWrappedKey;
        }

        /**
         * Get the PEM-encoded certificate chain
         */
        @NonNull
        public byte[] getContent() {
            if (mCertificateData == null) {
                // this should never happen as mCertificateData is initialized in
                // JNI after construction of the KeyRequest object. The check
                // is needed here to guarantee @NonNull annotation.
                throw new RuntimeException("Cerfificate is not initialized");
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
     * DRM engine plugin.
     *
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     *
     * @hide - not part of the public API at this time
     */
    @NonNull
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
    public byte[] signRSA(
            @NonNull byte[] sessionId, @NonNull String algorithm,
            @NonNull byte[] wrappedKey, @NonNull byte[] message) {
        return signRSANative(this, sessionId, algorithm, wrappedKey, message);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    public native final void release();
    private static native final void native_init();

    private native final void native_setup(Object mediadrm_this, byte[] uuid);

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }
}
