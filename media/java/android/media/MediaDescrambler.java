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
import android.hardware.cas.DestinationBuffer;
import android.hardware.cas.IDescrambler;
import android.hardware.cas.ScramblingControl;
import android.hardware.cas.SharedBuffer;
import android.hardware.cas.SubSample;
import android.hardware.cas.V1_0.IDescramblerBase;
import android.hardware.common.Ashmem;
import android.hardware.common.NativeHandle;
import android.media.MediaCasException.UnsupportedCasException;
import android.os.IHwBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

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
    private DescramblerWrapper mIDescrambler;

    private interface DescramblerWrapper {

        IHwBinder asBinder();

        int descramble(
                @NonNull ByteBuffer srcBuf,
                @NonNull ByteBuffer dstBuf,
                @NonNull MediaCodec.CryptoInfo cryptoInfo)
                throws RemoteException;

        boolean requiresSecureDecoderComponent(@NonNull String mime) throws RemoteException;

        void setMediaCasSession(byte[] sessionId) throws RemoteException;

        void release() throws RemoteException;
    }
    ;

    private long getSubsampleInfo(
            int numSubSamples,
            int[] numBytesOfClearData,
            int[] numBytesOfEncryptedData,
            SubSample[] subSamples) {
        long totalSize = 0;

        for (int i = 0; i < numSubSamples; i++) {
            totalSize += numBytesOfClearData[i];
            subSamples[i].numBytesOfClearData = numBytesOfClearData[i];
            totalSize += numBytesOfEncryptedData[i];
            subSamples[i].numBytesOfEncryptedData = numBytesOfEncryptedData[i];
        }
        return totalSize;
    }

    private ParcelFileDescriptor createSharedMemory(ByteBuffer buffer, String name)
            throws RemoteException {
        byte[] source = buffer.array();
        if (source.length == 0) {
            return null;
        }
        ParcelFileDescriptor fd = null;
        try {
            SharedMemory ashmem = SharedMemory.create(name == null ? "" : name, source.length);
            ByteBuffer ptr = ashmem.mapReadWrite();
            ptr.put(buffer);
            ashmem.unmap(ptr);
            fd = ashmem.getFdDup();
            return fd;
        } catch (ErrnoException | IOException e) {
            throw new RemoteException(e);
        }
    }

    private class AidlDescrambler implements DescramblerWrapper {

        IDescrambler mAidlDescrambler;

        AidlDescrambler(IDescrambler aidlDescrambler) {
            mAidlDescrambler = aidlDescrambler;
        }

        @Override
        public IHwBinder asBinder() {
            return null;
        }

        @Override
        public int descramble(
                @NonNull ByteBuffer src,
                @NonNull ByteBuffer dst,
                @NonNull MediaCodec.CryptoInfo cryptoInfo)
                throws RemoteException {
            SubSample[] subSamples = new SubSample[cryptoInfo.numSubSamples];
            long totalLength =
                    getSubsampleInfo(
                            cryptoInfo.numSubSamples,
                            cryptoInfo.numBytesOfClearData,
                            cryptoInfo.numBytesOfEncryptedData,
                            subSamples);
            SharedBuffer srcBuffer = new SharedBuffer();
            DestinationBuffer dstBuffer;
            srcBuffer.heapBase = new Ashmem();
            srcBuffer.heapBase.fd = createSharedMemory(src, "Descrambler Source Buffer");
            srcBuffer.heapBase.size = src.array().length;
            if (dst == null) {
                dstBuffer = DestinationBuffer.nonsecureMemory(srcBuffer);
            } else {
                ParcelFileDescriptor pfd =
                        createSharedMemory(dst, "Descrambler Destination Buffer");
                NativeHandle nh = new NativeHandle();
                nh.fds = new ParcelFileDescriptor[] {pfd};
                nh.ints = new int[] {1}; // Mark 1 since source buffer also uses it?
                dstBuffer = DestinationBuffer.secureMemory(nh);
            }
            @ScramblingControl int control = cryptoInfo.key[0];

            return mAidlDescrambler.descramble(
                    (byte) control,
                    subSamples,
                    srcBuffer,
                    src.position(),
                    dstBuffer,
                    dst.position());
        }

        @Override
        public boolean requiresSecureDecoderComponent(@NonNull String mime) throws RemoteException {
            return mAidlDescrambler.requiresSecureDecoderComponent(mime);
        }

        @Override
        public void setMediaCasSession(byte[] sessionId) throws RemoteException {
            mAidlDescrambler.setMediaCasSession(sessionId);
        }

        @Override
        public void release() throws RemoteException {
            mAidlDescrambler.release();
        }
    }

    private class HidlDescrambler implements DescramblerWrapper {

        IDescramblerBase mHidlDescrambler;

        HidlDescrambler(IDescramblerBase hidlDescrambler) {
            mHidlDescrambler = hidlDescrambler;
            native_setup(hidlDescrambler.asBinder());
        }

        @Override
        public IHwBinder asBinder() {
            return mHidlDescrambler.asBinder();
        }

        @Override
        public int descramble(
                @NonNull ByteBuffer srcBuf,
                @NonNull ByteBuffer dstBuf,
                @NonNull MediaCodec.CryptoInfo cryptoInfo)
                throws RemoteException {

            try {
                return native_descramble(
                        cryptoInfo.key[0],
                        cryptoInfo.key[1],
                        cryptoInfo.numSubSamples,
                        cryptoInfo.numBytesOfClearData,
                        cryptoInfo.numBytesOfEncryptedData,
                        srcBuf,
                        srcBuf.position(),
                        srcBuf.limit(),
                        dstBuf,
                        dstBuf.position(),
                        dstBuf.limit());
            } catch (ServiceSpecificException e) {
                MediaCasStateException.throwExceptionIfNeeded(e.errorCode, e.getMessage());
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
            return -1;
        }

        @Override
        public boolean requiresSecureDecoderComponent(@NonNull String mime) throws RemoteException {
            return mHidlDescrambler.requiresSecureDecoderComponent(mime);
        }

        @Override
        public void setMediaCasSession(byte[] sessionId) throws RemoteException {
            ArrayList<Byte> byteArray = new ArrayList<>();

            if (sessionId != null) {
                int length = sessionId.length;
                byteArray = new ArrayList<Byte>(length);
                for (int i = 0; i < length; i++) {
                    byteArray.add(Byte.valueOf(sessionId[i]));
                }
            }

            MediaCasStateException.throwExceptionIfNeeded(
                    mHidlDescrambler.setMediaCasSession(byteArray));
        }

        @Override
        public void release() throws RemoteException {
            mHidlDescrambler.release();
            native_release();
        }
    }

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
            if (MediaCas.getService() != null) {
                mIDescrambler =
                        new AidlDescrambler(MediaCas.getService().createDescrambler(CA_system_id));
            } else if (MediaCas.getServiceHidl() != null) {
                mIDescrambler =
                        new HidlDescrambler(
                                MediaCas.getServiceHidl().createDescrambler(CA_system_id));
            }
        } catch(Exception e) {
            Log.e(TAG, "Failed to create descrambler: " + e);
            mIDescrambler = null;
        } finally {
            if (mIDescrambler == null) {
                throw new UnsupportedCasException("Unsupported CA_system_id " + CA_system_id);
            }
        }
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
            mIDescrambler.setMediaCasSession(session.mSessionId);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Scramble control value indicating that the samples are not scrambled.
     *
     * @see #descramble(ByteBuffer, ByteBuffer, android.media.MediaCodec.CryptoInfo)
     */
    public static final byte SCRAMBLE_CONTROL_UNSCRAMBLED = (byte) ScramblingControl.UNSCRAMBLED;

    /**
     * Scramble control value reserved and shouldn't be used currently.
     *
     * @see #descramble(ByteBuffer, ByteBuffer, android.media.MediaCodec.CryptoInfo)
     */
    public static final byte SCRAMBLE_CONTROL_RESERVED = (byte) ScramblingControl.RESERVED;

    /**
     * Scramble control value indicating that the even key is used.
     *
     * @see #descramble(ByteBuffer, ByteBuffer, android.media.MediaCodec.CryptoInfo)
     */
    public static final byte SCRAMBLE_CONTROL_EVEN_KEY = (byte) ScramblingControl.EVENKEY;

    /**
     * Scramble control value indicating that the odd key is used.
     *
     * @see #descramble(ByteBuffer, ByteBuffer, android.media.MediaCodec.CryptoInfo)
     */
    public static final byte SCRAMBLE_CONTROL_ODD_KEY = (byte) ScramblingControl.ODDKEY;

    /**
     * Scramble flag for a hint indicating that the descrambling request is for
     * retrieving the PES header info only.
     *
     * @see #descramble(ByteBuffer, ByteBuffer, android.media.MediaCodec.CryptoInfo)
     */
    public static final byte SCRAMBLE_FLAG_PES_HEADER = (1 << 0);

    /**
     * Descramble a ByteBuffer of data described by a
     * {@link android.media.MediaCodec.CryptoInfo} structure.
     *
     * @param srcBuf ByteBuffer containing the scrambled data, which starts at
     * srcBuf.position().
     * @param dstBuf ByteBuffer to hold the descrambled data, which starts at
     * dstBuf.position().
     * @param cryptoInfo a {@link android.media.MediaCodec.CryptoInfo} structure
     * describing the subsamples contained in srcBuf. The iv and mode fields in
     * CryptoInfo are not used. key[0] contains the MPEG2TS scrambling control bits
     * (as defined in ETSI TS 100 289 (2011): "Digital Video Broadcasting (DVB);
     * Support for use of the DVB Scrambling Algorithm version 3 within digital
     * broadcasting systems"), and the value must be one of {@link #SCRAMBLE_CONTROL_UNSCRAMBLED},
     * {@link #SCRAMBLE_CONTROL_RESERVED}, {@link #SCRAMBLE_CONTROL_EVEN_KEY} or
     * {@link #SCRAMBLE_CONTROL_ODD_KEY}. key[1] is a set of bit flags, with the
     * only possible bit being {@link #SCRAMBLE_FLAG_PES_HEADER} currently.
     * key[2~15] are not used.
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
            return mIDescrambler.descramble(srcBuf, dstBuf, cryptoInfo);
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
    }

    @Override
    protected void finalize() {
        close();
    }

    private static native final void native_init();
    private native final void native_setup(@NonNull IHwBinder decramblerBinder);
    private native final void native_release();
    private native final int native_descramble(
            byte key, byte flags, int numSubSamples,
            int[] numBytesOfClearData, int[] numBytesOfEncryptedData,
            @NonNull ByteBuffer srcBuf, int srcOffset, int srcLimit,
            ByteBuffer dstBuf, int dstOffset, int dstLimit) throws RemoteException;

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;
}
