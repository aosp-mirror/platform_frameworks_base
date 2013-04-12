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

import android.media.MediaDrmException;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

/**
 * MediaDrm can be used in conjunction with {@link android.media.MediaCrypto}
 * to obtain keys for decrypting protected media data.
 *
 * Crypto schemes are assigned 16 byte UUIDs,
 * the method {@link #isCryptoSchemeSupported} can be used to query if a given
 * scheme is supported on the device.
 *
 * <a name="Callbacks"></a>
 * <h3>Callbacks</h3>
 * <p>Applications may want to register for informational events in order
 * to be informed of some internal state update during playback or streaming.
 * Registration for these events is done via a call to
 * {@link #setOnEventListener(OnInfoListener)}setOnInfoListener,
 * In order to receive the respective callback
 * associated with this listener, applications are required to create
 * MediaDrm objects on a thread with its own Looper running (main UI
 * thread by default has a Looper running).
 *
 * @hide -- don't expose yet
 */
public final class MediaDrm {

    private final static String TAG = "MediaDrm";

    private EventHandler mEventHandler;
    private OnEventListener mOnEventListener;

    private int mNativeContext;

    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device.
     * @param uuid The UUID of the crypto scheme.
     */
    public static final boolean isCryptoSchemeSupported(UUID uuid) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid));
    }

    private static final byte[] getByteArrayFromUUID(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];
        for (int i = 0; i < 8; ++i) {
            uuidBytes[i] = (byte)(msb >>> (8 * (7 - i)));
            uuidBytes[8 + i] = (byte)(lsb >>> (8 * (7 - i)));
        }

        return uuidBytes;
    }

    private static final native boolean isCryptoSchemeSupportedNative(byte[] uuid);

    /**
     * Instantiate a MediaDrm object using opaque, crypto scheme specific
     * data.
     * @param uuid The UUID of the crypto scheme.
     */
    public MediaDrm(UUID uuid) throws MediaDrmException {
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
     * Register a callback to be invoked when an event occurs
     *
     * @param listener the callback that will be run
     */
    public void setOnEventListener(OnEventListener listener)
    {
        mOnEventListener = listener;
    }

    /**
     * Interface definition for a callback to be invoked when a drm event
     * occurs.
     */
    public interface OnEventListener
    {
        /**
         * Called when an event occurs that requires the app to be notified
         *
         * @param md the MediaDrm object on which the event occurred
         * @param sessionId the DRM session ID on which the event occurred
         * @param event indicates the event type
         * @param extra an secondary error code
         * @param data optional byte array of data that may be associated with the event
         */
        void onEvent(MediaDrm md, byte[] sessionId, int event, int extra, byte[] data);
    }

    public static final int MEDIA_DRM_EVENT_PROVISION_REQUIRED = 1;
    public static final int MEDIA_DRM_EVENT_KEY_REQUIRED = 2;
    public static final int MEDIA_DRM_EVENT_KEY_EXPIRED = 3;
    public static final int MEDIA_DRM_EVENT_VENDOR_DEFINED = 4;

    private static final int DRM_EVENT = 200;

    private class EventHandler extends Handler
    {
        private MediaDrm mMediaDrm;

        public EventHandler(MediaDrm md, Looper looper) {
            super(looper);
            mMediaDrm = md;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mMediaDrm.mNativeContext == 0) {
                Log.w(TAG, "MediaDrm went away with unhandled events");
                return;
            }
            switch(msg.what) {

            case DRM_EVENT:
                Log.i(TAG, "Drm event (" + msg.arg1 + "," + msg.arg2 + ")");

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
                        mOnEventListener.onEvent(mMediaDrm, sessionId, msg.arg1, msg.arg2, data);
                    }
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /*
     * Called from native code when an interesting event happens.  This method
     * just uses the EventHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaPlayer object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(Object mediadrm_ref,
                                            int eventType, int extra, Object obj)
    {
        MediaDrm md = (MediaDrm)((WeakReference)mediadrm_ref).get();
        if (md == null) {
            return;
        }
        if (md.mEventHandler != null) {
            Message m = md.mEventHandler.obtainMessage(DRM_EVENT, eventType, extra, obj);
            md.mEventHandler.sendMessage(m);
        }
    }

    /**
     *  Open a new session with the MediaDrm object.  A session ID is returned.
     */
    public native byte[] openSession() throws MediaDrmException;

    /**
     *  Close a session on the MediaDrm object that was previously opened
     *  with {@link #openSession}.
     */
    public native void closeSession(byte[] sessionId) throws MediaDrmException;

    public static final int MEDIA_DRM_KEY_TYPE_STREAMING = 1;
    public static final int MEDIA_DRM_KEY_TYPE_OFFLINE = 2;
    public static final int MEDIA_DRM_KEY_TYPE_RELEASE = 3;

    public final class KeyRequest {
        public KeyRequest() {}
        public byte[] data;
        public String defaultUrl;
    };

    /**
     * A key request/response exchange occurs between the app and a license server
     * to obtain or release keys used to decrypt encrypted content.
     * getKeyRequest() is used to obtain an opaque key request byte array that is
     * delivered to the license server.  The opaque key request byte array is returned
     * in KeyRequest.data.  The recommended URL to deliver the key request to is
     * returned in KeyRequest.defaultUrl.
     *
     * After the app has received the key request response from the server,
     * it should deliver to the response to the DRM engine plugin using the method
     * {@link #provideKeyResponse}.
     *
     * @param scope may be a sessionId or a keySetId, depending on the specified keyType.
     * When the keyType is MEDIA_DRM_KEY_TYPE_STREAMING or MEDIA_DRM_KEY_TYPE_OFFLINE,
     * scope should be set to the sessionId the keys will be provided to.  When the keyType
     * is MEDIA_DRM_KEY_TYPE_RELEASE, scope should be set to the keySetId of the keys
     * being released. Releasing keys from a device invalidates them for all sessions.
     * @param init container-specific data, its meaning is interpreted based on the
     * mime type provided in the mimeType parameter.  It could contain, for example,
     * the content ID, key ID or other data obtained from the content metadata that is
     * required in generating the key request. init may be null when keyType is
     * MEDIA_DRM_KEY_TYPE_RELEASE.
     * @param mimeType identifies the mime type of the content
     * @param keyType specifes the type of the request. The request may be to acquire
     * keys for streaming or offline content, or to release previously acquired
     * keys, which are identified by a keySetId.

     * @param optionalParameters are included in the key request message to
     * allow a client application to provide additional message parameters to the server.
     */
    public native KeyRequest getKeyRequest(byte[] scope, byte[] init,
                                           String mimeType, int keyType,
                                           HashMap<String, String> optionalParameters)
        throws MediaDrmException;

    /**
     * A key response is received from the license server by the app, then it is
     * provided to the DRM engine plugin using provideKeyResponse. The byte array
     * returned is a keySetId that can be used to later restore the keys to a new
     * session with the method {@link restoreKeys}, enabling offline key use.
     *
     * @param sessionId the session ID for the DRM session
     * @param response the byte array response from the server
     */
    public native byte[] provideKeyResponse(byte[] sessionId, byte[] response)
        throws MediaDrmException;

    /**
     * Restore persisted offline keys into a new session.  keySetId identifies the
     * keys to load, obtained from a prior call to {@link provideKeyResponse}.
     *
     * @param sessionId the session ID for the DRM session
     * @param keySetId identifies the saved key set to restore
     */
    public native void restoreKeys(byte[] sessionId, byte[] keySetId)
        throws MediaDrmException;

    /**
     * Remove the current keys from a session.
     *
     * @param sessionId the session ID for the DRM session
     */
    public native void removeKeys(byte[] sessionId) throws MediaDrmException;

    /**
     * Request an informative description of the key status for the session.  The status is
     * in the form of {name, value} pairs.  Since DRM license policies vary by vendor,
     * the specific status field names are determined by each DRM vendor.  Refer to your
     * DRM provider documentation for definitions of the field names for a particular
     * DRM engine plugin.
     *
     * @param sessionId the session ID for the DRM session
     */
    public native HashMap<String, String> queryKeyStatus(byte[] sessionId)
        throws MediaDrmException;

    public final class ProvisionRequest {
        public ProvisionRequest() {}
        public byte[] data;
        public String defaultUrl;
    }

    /**
     * A provision request/response exchange occurs between the app and a provisioning
     * server to retrieve a device certificate.  If provisionining is required, the
     * MEDIA_DRM_EVENT_PROVISION_REQUIRED event will be sent to the event handler.
     * getProvisionRequest is used to obtain the opaque provision request byte array that
     * should be delivered to the provisioning server. The provision request byte array
     * is returned in ProvisionRequest.data. The recommended URL to deliver the provision
     * request to is returned in ProvisionRequest.defaultUrl.
     */
    public native ProvisionRequest getProvisionRequest() throws MediaDrmException;

    /**
     * After a provision response is received by the app, it is provided to the DRM
     * engine plugin using this method.
     *
     * @param response the opaque provisioning response byte array to provide to the
     * DRM engine plugin.
     */
    public native void provideProvisionResponse(byte[] response)
        throws MediaDrmException;

    /**
     * A means of enforcing the contractual requirement for a concurrent stream limit
     * per subscriber across devices is provided via SecureStop.  SecureStop is a means
     * of securely monitoring the lifetime of sessions. Since playback on a device can
     * be interrupted due to reboot, power failure, etc. a means of persisting the
     * lifetime information on the device is needed.
     *
     * A signed version of the sessionID is written to persistent storage on the device
     * when each MediaCrypto object is created. The sessionID is signed by the device
     * private key to prevent tampering.
     *
     * In the normal case, playback will be completed, the session destroyed and the
     * Secure Stops will be queried. The App queries secure stops and forwards the
     * secure stop message to the server which verifies the signature and notifies the
     * server side database that the session destruction has been confirmed. The persisted
     * record on the client is only removed after positive confirmation that the server
     * received the message using releaseSecureStops().
     */
    public native List<byte[]> getSecureStops() throws MediaDrmException;


    /**
     * Process the SecureStop server response message ssRelease.  After authenticating
     * the message, remove the SecureStops identiied in the response.
     *
     * @param ssRelease the server response indicating which secure stops to release
     */
    public native void releaseSecureStops(byte[] ssRelease)
        throws MediaDrmException;


    /**
     * Read a DRM engine plugin property value, given the property name string.  There are
     * several forms of property access functions, depending on the data type returned.
     *
     * Standard fields names are:
     *   vendor         String - identifies the maker of the DRM engine plugin
     *   version        String - identifies the version of the DRM engine plugin
     *   description    String - describes the DRM engine plugin
     *   deviceUniqueId byte[] - The device unique identifier is established during device
     *                           provisioning and provides a means of uniquely identifying
     *                           each device
     *   algorithms     String - a comma-separate list of cipher and mac algorithms supported
     *                           by CryptoSession.  The list may be empty if the DRM engine
     *                           plugin does not support CryptoSession operations.
     */
    public native String getPropertyString(String propertyName)
        throws MediaDrmException;

    public native byte[] getPropertyByteArray(String propertyName)
        throws MediaDrmException;

    /**
     * Write a DRM engine plugin property value.  There are several forms of
     * property setting functions, depending on the data type being set.
     */
    public native void setPropertyString(String propertyName, String value)
        throws MediaDrmException;

    public native void setPropertyByteArray(String propertyName, byte[] value)
        throws MediaDrmException;

    /**
     * In addition to supporting decryption of DASH Common Encrypted Media, the
     * MediaDrm APIs provide the ability to securely deliver session keys from
     * an operator's session key server to a client device, based on the factory-installed
     * root of trust, and provide the ability to do encrypt, decrypt, sign and verify
     * with the session key on arbitrary user data.
     *
     * The CryptoSession class implements generic encrypt/decrypt/sign/verify methods
     * based on the established session keys.  These keys are exchanged using the
     * getKeyRequest/provideKeyResponse methods.
     *
     * Applications of this capability could include securing various types of
     * purchased or private content, such as applications, books and other media,
     * photos or media delivery protocols.
     *
     * Operators can create session key servers that are functionally similar to a
     * license key server, except that instead of receiving license key requests and
     * providing encrypted content keys which are used specifically to decrypt A/V media
     * content, the session key server receives session key requests and provides
     * encrypted session keys which can be used for general purpose crypto operations.
     */

    private static final native void setCipherAlgorithmNative(MediaDrm drm, byte[] sessionId,
                                                              String algorithm);

    private static final native void setMacAlgorithmNative(MediaDrm drm, byte[] sessionId,
                                                           String algorithm);

    private static final native byte[] encryptNative(MediaDrm drm, byte[] sessionId,
                                                     byte[] keyId, byte[] input, byte[] iv);

    private static final native byte[] decryptNative(MediaDrm drm, byte[] sessionId,
                                                     byte[] keyId, byte[] input, byte[] iv);

    private static final native byte[] signNative(MediaDrm drm, byte[] sessionId,
                                                  byte[] keyId, byte[] message);

    private static final native boolean verifyNative(MediaDrm drm, byte[] sessionId,
                                                     byte[] keyId, byte[] message,
                                                     byte[] signature);

    public final class CryptoSession {
        private MediaDrm mDrm;
        private byte[] mSessionId;

        /**
         * Construct a CryptoSession which can be used to encrypt, decrypt,
         * sign and verify messages or data using the session keys established
         * for the session using methods {@link getKeyRequest} and
         * {@link provideKeyResponse} using a session key server.
         *
         * @param sessionId the session ID for the session containing keys
         * to be used for encrypt, decrypt, sign and/or verify
         *
         * @param cipherAlgorithm the algorithm to use for encryption and
         * decryption ciphers. The algorithm string conforms to JCA Standard
         * Names for Cipher Transforms and is case insensitive.  For example
         * "AES/CBC/PKCS5Padding".
         *
         * @param macAlgorithm the algorithm to use for sign and verify
         * The algorithm string conforms to JCA Standard Names for Mac
         * Algorithms and is case insensitive.  For example "HmacSHA256".
         *
         * The list of supported algorithms for a DRM engine plugin can be obtained
         * using the method {@link getPropertyString("algorithms")}
         */

        public CryptoSession(MediaDrm drm, byte[] sessionId,
                             String cipherAlgorithm, String macAlgorithm)
            throws MediaDrmException {
            mSessionId = sessionId;
            mDrm = drm;
            setCipherAlgorithmNative(drm, sessionId, cipherAlgorithm);
            setMacAlgorithmNative(drm, sessionId, macAlgorithm);
        }

        public byte[] encrypt(byte[] keyid, byte[] input, byte[] iv) {
            return encryptNative(mDrm, mSessionId, keyid, input, iv);
        }

        public byte[] decrypt(byte[] keyid, byte[] input, byte[] iv) {
            return decryptNative(mDrm, mSessionId, keyid, input, iv);
        }

        public byte[] sign(byte[] keyid, byte[] message) {
            return signNative(mDrm, mSessionId, keyid, message);
        }
        public boolean verify(byte[] keyid, byte[] message, byte[] signature) {
            return verifyNative(mDrm, mSessionId, keyid, message, signature);
        }
    };

    public CryptoSession getCryptoSession(byte[] sessionId,
                                          String cipherAlgorithm,
                                          String macAlgorithm)
        throws MediaDrmException {
        return new CryptoSession(this, sessionId, cipherAlgorithm, macAlgorithm);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    public native final void release();
    private static native final void native_init();

    private native final void native_setup(Object mediadrm_this, byte[] uuid)
        throws MediaDrmException;

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }
}
