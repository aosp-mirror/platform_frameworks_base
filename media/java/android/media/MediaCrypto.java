/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.NonNull;
import android.media.MediaCryptoException;
import java.util.UUID;

/**
 * MediaCrypto class can be used in conjunction with {@link android.media.MediaCodec}
 * to decode encrypted media data.
 *
 * Crypto schemes are assigned 16 byte UUIDs,
 * the method {@link #isCryptoSchemeSupported} can be used to query if a given
 * scheme is supported on the device.
 *
 */
public final class MediaCrypto {
    /**
     * Query if the given scheme identified by its UUID is supported on
     * this device.
     * @param uuid The UUID of the crypto scheme.
     */
    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid) {
        return isCryptoSchemeSupportedNative(getByteArrayFromUUID(uuid));
    }

    @NonNull
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

    private static final native boolean isCryptoSchemeSupportedNative(@NonNull byte[] uuid);

    /**
     * Instantiate a MediaCrypto object and associate it with a MediaDrm session
     *
     * @param uuid The UUID of the crypto scheme.
     * @param sessionId The MediaDrm sessionId to associate with this
     * MediaCrypto session. The sessionId may be changed after the MediaCrypto
     * is created using {@link #setMediaDrmSession}
     */
    public MediaCrypto(@NonNull UUID uuid, @NonNull byte[] sessionId) throws MediaCryptoException {
        native_setup(getByteArrayFromUUID(uuid), sessionId);
    }

    /**
     * Query if the crypto scheme requires the use of a secure decoder
     * to decode data of the given mime type.
     * @param mime The mime type of the media data
     */
    public final native boolean requiresSecureDecoderComponent(@NonNull String mime);

    /**
     * Associate a MediaDrm session with this MediaCrypto instance.  The
     * MediaDrm session is used to securely load decryption keys for a
     * crypto scheme.  The crypto keys loaded through the MediaDrm session
     * may be selected for use during the decryption operation performed
     * by {@link android.media.MediaCodec#queueSecureInputBuffer} by specifying
     * their key ids in the {@link android.media.MediaCodec.CryptoInfo#key} field.
     * @param sessionId the MediaDrm sessionId to associate with this
     * MediaCrypto instance
     * @throws MediaCryptoException on failure to set the sessionId
     */
    public final native void setMediaDrmSession(@NonNull byte[] sessionId)
        throws MediaCryptoException;

    @Override
    protected void finalize() {
        native_finalize();
    }

    public native final void release();
    private static native final void native_init();

    private native final void native_setup(@NonNull byte[] uuid, @NonNull byte[] initData)
        throws MediaCryptoException;

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;
}
