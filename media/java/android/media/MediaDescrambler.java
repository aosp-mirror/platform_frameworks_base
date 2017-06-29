/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.hardware.cas.V1_0.*;
import android.media.MediaCasException.UnsupportedCasException;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * MediaDescrambler class can be used in conjunction with {@link android.media.MediaCodec}
 * and {@link android.media.MediaExtractor} to decode media data scrambled by conditional
 * access (CA) systems such as those in the ISO/IEC13818-1.
 *
 * A MediaDescrambler object is initialized from a session opened by a MediaCas object,
 * and can be used to descramble media streams scrambled with that session's keys.
 *
 * Scrambling schemes are identified by 16-bit unsigned integer as in CA_system_id.
 *
 */
public final class MediaDescrambler implements AutoCloseable {
    private static final String TAG = "MediaDescrambler";
    private IDescramblerBase mIDescrambler;

    private final void validateInternalStates() {
        if (mIDescrambler == null) {
            throw new IllegalStateException();
        }
    }

    private final void cleanupAndRethrowIllegalState() {
        mIDescrambler = null;
        throw new IllegalStateException();
    }

    /**
     * Instantiate a MediaDescrambler.
     *
     * @param CA_system_id The system id of the scrambling scheme.
     *
     * @throws UnsupportedCasException if the scrambling scheme is not supported.
     */
    public MediaDescrambler(int CA_system_id) throws UnsupportedCasException {
        try {
            mIDescrambler = MediaCas.getService().createDescrambler(CA_system_id);
        } catch(Exception e) {
            Log.e(TAG, "Failed to create descrambler: " + e);
            mIDescrambler = null;
        } finally {
            if (mIDescrambler == null) {
                throw new UnsupportedCasException("Unsupported CA_system_id " + CA_system_id);
            }
        }
        native_setup(mIDescrambler.asBinder());
    }

    IHwBinder getBinder() {
        validateInternalStates();

        return mIDescrambler.asBinder();
    }

    /**
     * Query if the scrambling scheme requires the use of a secure decoder
     * to decode data of the given mime type.
     *
     * @param mime The mime type of the media data
     *
     * @throws IllegalStateException if the descrambler instance is not valid.
     */
    public final boolean requiresSecureDecoderComponent(@NonNull String mime) {
        validateInternalStates();

        try {
            return mIDescrambler.requiresSecureDecoderComponent(mime);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
        return true;
    }

    /**
     * Associate a MediaCas session with this MediaDescrambler instance.
     * The MediaCas session is used to securely load decryption keys for
     * the descrambler. The crypto keys loaded through the MediaCas session
     * may be selected for use during the descrambling operation performed
     * by {@link android.media.MediaExtractor or @link
     * android.media.MediaCodec#queueSecureInputBuffer} by specifying even
     * or odd key in the {@link android.media.MediaCodec.CryptoInfo#key} field.
     *
     * @param session the MediaCas session to associate with this
     * MediaDescrambler instance.
     *
     * @throws IllegalStateException if the descrambler instance is not valid.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public final void setMediaCasSession(@NonNull MediaCas.Session session) {
        validateInternalStates();

        try {
            MediaCasStateException.throwExceptionIfNeeded(
                    mIDescrambler.setMediaCasSession(session.mSessionId));
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Descramble a ByteBuffer of data described by a
     * {@link android.media.MediaCodec.CryptoInfo} structure.
     *
     * @param srcBuf ByteBuffer containing the scrambled data, which starts at
     * srcBuf.position().
     * @param dstBuf ByteBuffer to hold the descrambled data, which starts at
     * dstBuf.position().
     * @param cryptoInfo a {@link android.media.MediaCodec.CryptoInfo} structure
     * describing the subsamples contained in src.
     *
     * @return number of bytes that have been successfully descrambled, with negative
     * values indicating errors.
     *
     * @throws IllegalStateException if the descrambler instance is not valid.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public final int descramble(
            @NonNull ByteBuffer srcBuf, @NonNull ByteBuffer dstBuf,
            @NonNull MediaCodec.CryptoInfo cryptoInfo) {
        validateInternalStates();

        if (cryptoInfo.numSubSamples <= 0) {
            throw new IllegalArgumentException(
                    "Invalid CryptoInfo: invalid numSubSamples=" + cryptoInfo.numSubSamples);
        } else if (cryptoInfo.numBytesOfClearData == null
                && cryptoInfo.numBytesOfEncryptedData == null) {
            throw new IllegalArgumentException(
                    "Invalid CryptoInfo: clearData and encryptedData size arrays are both null!");
        } else if (cryptoInfo.numBytesOfClearData != null
                && cryptoInfo.numBytesOfClearData.length < cryptoInfo.numSubSamples) {
            throw new IllegalArgumentException(
                    "Invalid CryptoInfo: numBytesOfClearData is too small!");
        } else if (cryptoInfo.numBytesOfEncryptedData != null
                && cryptoInfo.numBytesOfEncryptedData.length < cryptoInfo.numSubSamples) {
            throw new IllegalArgumentException(
                    "Invalid CryptoInfo: numBytesOfEncryptedData is too small!");
        } else if (cryptoInfo.key == null || cryptoInfo.key.length != 16) {
            throw new IllegalArgumentException(
                    "Invalid CryptoInfo: key array is invalid!");
        }

        try {
            return native_descramble(
                    cryptoInfo.key[0],
                    cryptoInfo.numSubSamples,
                    cryptoInfo.numBytesOfClearData,
                    cryptoInfo.numBytesOfEncryptedData,
                    srcBuf, srcBuf.position(), srcBuf.limit(),
                    dstBuf, dstBuf.position(), dstBuf.limit());
        } catch (ServiceSpecificException e) {
            MediaCasStateException.throwExceptionIfNeeded(e.errorCode, e.getMessage());
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
        return -1;
    }

    @Override
    public void close() {
        if (mIDescrambler != null) {
            try {
                mIDescrambler.release();
            } catch (RemoteException e) {
            } finally {
                mIDescrambler = null;
            }
        }
        native_release();
    }

    @Override
    protected void finalize() {
        close();
    }

    private static native final void native_init();
    private native final void native_setup(@NonNull IHwBinder decramblerBinder);
    private native final void native_release();
    private native final int native_descramble(
            byte key, int numSubSamples, int[] numBytesOfClearData, int[] numBytesOfEncryptedData,
            @NonNull ByteBuffer srcBuf, int srcOffset, int srcLimit,
            ByteBuffer dstBuf, int dstOffset, int dstLimit) throws RemoteException;

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;
}