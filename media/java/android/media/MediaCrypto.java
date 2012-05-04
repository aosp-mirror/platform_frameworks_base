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
     * Instantiate a MediaCrypto object using opaque, crypto scheme specific
     * data.
     * @param uuid The UUID of the crypto scheme.
     * @param initData Opaque initialization data specific to the crypto scheme.
     */
    public MediaCrypto(UUID uuid, byte[] initData) throws MediaCryptoException {
        native_setup(getByteArrayFromUUID(uuid), initData);
    }

    /**
     * Query if the crypto scheme requires the use of a secure decoder
     * to decode data of the given mime type.
     * @param mime The mime type of the media data
     */
    public final native boolean requiresSecureDecoderComponent(String mime);

    @Override
    protected void finalize() {
        native_finalize();
    }

    public native final void release();
    private static native final void native_init();

    private native final void native_setup(byte[] uuid, byte[] initData)
        throws MediaCryptoException;

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private int mNativeContext;
}
