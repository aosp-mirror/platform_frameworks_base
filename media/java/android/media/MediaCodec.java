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

import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * MediaCodec class can be used to access low-level media codec, i.e.
 * encoder/decoder components.
 *
 * <p>MediaCodec is generally used like this:
 * <pre>
 * MediaCodec codec = MediaCodec.createDecoderByType(type);
 * codec.configure(format, ...);
 * codec.start();
 * ByteBuffer[] inputBuffers = codec.getInputBuffers();
 * ByteBuffer[] outputBuffers = codec.getOutputBuffers();
 * MediaFormat format = codec.getOutputFormat();
 * for (;;) {
 *   int inputBufferIndex = codec.dequeueInputBuffer(timeoutUs);
 *   if (inputBufferIndex &gt;= 0) {
 *     // fill inputBuffers[inputBufferIndex] with valid data
 *     ...
 *     codec.queueInputBuffer(inputBufferIndex, ...);
 *   }
 *
 *   int outputBufferIndex = codec.dequeueOutputBuffer(timeoutUs);
 *   if (outputBufferIndex &gt;= 0) {
 *     // outputBuffer is ready to be processed or rendered.
 *     ...
 *     codec.releaseOutputBuffer(outputBufferIndex, ...);
 *   } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
 *     outputBuffers = codec.getOutputBuffers();
 *   } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
 *     // Subsequent data will conform to new format.
 *     format = codec.getOutputFormat();
 *     ...
 *   }
 * }
 * codec.stop();
 * codec.release();
 * codec = null;
 * </pre>
 *
 * Each codec maintains a number of input and output buffers that are
 * referred to by index in API calls.
 * The contents of these buffers is represented by the ByteBuffer[] arrays
 * accessible through getInputBuffers() and getOutputBuffers().
 *
 * After a successful call to {@link #start} the client "owns" neither
 * input nor output buffers, subsequent calls to {@link #dequeueInputBuffer}
 * and {@link #dequeueOutputBuffer} then transfer ownership from the codec
 * to the client.<p>
 * The client is not required to resubmit/release buffers immediately
 * to the codec, the sample code above simply does this for simplicity's sake.<p>
 * Once the client has an input buffer available it can fill it with data
 * and submit it it to the codec via a call to {@link #queueInputBuffer}.<p>
 * The codec in turn will return an output buffer to the client in response
 * to {@link #dequeueOutputBuffer}. After the output buffer has been processed
 * a call to {@link #releaseOutputBuffer} will return it to the codec.
 * If a video surface has been provided in the call to {@link #configure},
 * {@link #releaseOutputBuffer} optionally allows rendering of the buffer
 * to the surface.<p>
 *
 * Input buffers (for decoders) and Output buffers (for encoders) contain
 * encoded data according to the format's type. For video types this data
 * is all the encoded data representing a single moment in time, for audio
 * data this is slightly relaxed in that a buffer may contain multiple
 * encoded frames of audio. In either case, buffers do not start and end on
 * arbitrary byte boundaries, this is not a stream of bytes, it's a stream
 * of access units.<p>
 *
 * Most formats also require the actual data to be prefixed by a number
 * of buffers containing setup data, or codec specific data, i.e. the
 * first few buffers submitted to the codec object after starting it must
 * be codec specific data marked as such using the flag {@link #BUFFER_FLAG_CODEC_CONFIG}
 * in a call to {@link #queueInputBuffer}.
 *
 * Codec specific data included in the format passed to {@link #configure}
 * (in ByteBuffer entries with keys "csd-0", "csd-1", ...) is automatically
 * submitted to the codec, this data MUST NOT be submitted explicitly by the
 * client.
 *
 * Once the client reaches the end of the input data it signals the end of
 * the input stream by specifying a flag of {@link #BUFFER_FLAG_END_OF_STREAM} in the call to
 * {@link #queueInputBuffer}. The codec will continue to return output buffers
 * until it eventually signals the end of the output stream by specifying
 * the same flag ({@link #BUFFER_FLAG_END_OF_STREAM}) on the BufferInfo returned in
 * {@link #dequeueOutputBuffer}.
 *
 * In order to start decoding data that's not adjacent to previously submitted
 * data (i.e. after a seek) it is necessary to {@link #flush} the decoder.
 * Any input or output buffers the client may own at the point of the flush are
 * immediately revoked, i.e. after a call to {@link #flush} the client does not
 * own any buffers anymore.
 * Note that the format of the data submitted after a flush must not change,
 * flush does not support format discontinuities,
 * for this a full stop(), configure(), start() cycle is necessary.
 *
 */
final public class MediaCodec {
    /**
     * Per buffer metadata includes an offset and size specifying
     * the range of valid data in the associated codec buffer.
     */
    public final static class BufferInfo {
        public void set(
                int newOffset, int newSize, long newTimeUs, int newFlags) {
            offset = newOffset;
            size = newSize;
            presentationTimeUs = newTimeUs;
            flags = newFlags;
        }

        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
    };

    // The follow flag constants MUST stay in sync with their equivalents
    // in MediaCodec.h !

    /**
     * This indicates that the buffer marked as such contains the data
     * for a sync frame.
     */
    public static final int BUFFER_FLAG_SYNC_FRAME   = 1;

    /**
     * This indicated that the buffer marked as such contains codec
     * initialization / codec specific data instead of media data.
     */
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;

    /**
     * This signals the end of stream, i.e. no buffers will be available
     * after this, unless of course, {@link #flush} follows.
     */
    public static final int BUFFER_FLAG_END_OF_STREAM         = 4;

    /**
     * Instantiate a decoder supporting input data of the given mime type.
     *
     * The following is a partial list of defined mime types and their semantics:
     * <ul>
     * <li>"video/x-vnd.on2.vp8" - VPX video (i.e. video in .webm)
     * <li>"video/avc" - H.264/AVC video
     * <li>"video/mp4v-es" - MPEG4 video
     * <li>"video/3gpp" - H.263 video
     * <li>"audio/3gpp" - AMR narrowband audio
     * <li>"audio/amr-wb" - AMR wideband audio
     * <li>"audio/mpeg" - MPEG1/2 audio layer III
     * <li>"audio/mp4a-latm" - AAC audio
     * <li>"audio/vorbis" - vorbis audio
     * <li>"audio/g711-alaw" - G.711 alaw audio
     * <li>"audio/g711-mlaw" - G.711 ulaw audio
     * </ul>
     *
     * @param type The mime type of the input data.
     */
    public static MediaCodec createDecoderByType(String type) {
        return new MediaCodec(type, true /* nameIsType */, false /* encoder */);
    }

    /**
     * Instantiate an encoder supporting output data of the given mime type.
     * @param type The desired mime type of the output data.
     */
    public static MediaCodec createEncoderByType(String type) {
        return new MediaCodec(type, true /* nameIsType */, true /* encoder */);
    }

    /**
     * If you know the exact name of the component you want to instantiate
     * use this method to instantiate it. Use with caution.
     * Likely to be used with information obtained from {@link android.media.MediaCodecList}
     * @param name The name of the codec to be instantiated.
     */
    public static MediaCodec createByCodecName(String name) {
        return new MediaCodec(
                name, false /* nameIsType */, false /* unused */);
    }

    private MediaCodec(
            String name, boolean nameIsType, boolean encoder) {
        native_setup(name, nameIsType, encoder);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    /**
     * Make sure you call this when you're done to free up any opened
     * component instance instead of relying on the garbage collector
     * to do this for you at some point in the future.
     */
    public native final void release();

    /**
     * If this codec is to be used as an encoder, pass this flag.
     */
    public static final int CONFIGURE_FLAG_ENCODE = 1;

    /**
     * Configures a component.
     *
     * @param format The format of the input data (decoder) or the desired
     *               format of the output data (encoder).
     * @param surface Specify a surface on which to render the output of this
     *                decoder.
     * @param crypto  Specify a crypto object to facilitate secure decryption
     *                of the media data.
     * @param flags   Specify {@link #CONFIGURE_FLAG_ENCODE} to configure the
     *                component as an encoder.
     */
    public void configure(
            MediaFormat format,
            Surface surface, MediaCrypto crypto, int flags) {
        Map<String, Object> formatMap = format.getMap();

        String[] keys = null;
        Object[] values = null;

        if (format != null) {
            keys = new String[formatMap.size()];
            values = new Object[formatMap.size()];

            int i = 0;
            for (Map.Entry<String, Object> entry: formatMap.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                ++i;
            }
        }

        native_configure(keys, values, surface, crypto, flags);
    }

    private native final void native_configure(
            String[] keys, Object[] values,
            Surface surface, MediaCrypto crypto, int flags);

    /**
     * After successfully configuring the component, call start. On return
     * you can query the component for its input/output buffers.
     */
    public native final void start();

    /**
     * Finish the decode/encode session, note that the codec instance
     * remains active and ready to be {@link #start}ed again.
     * To ensure that it is available to other client call {@link #release}
     * and don't just rely on garbage collection to eventually do this for you.
     */
    public native final void stop();

    /**
     * Flush both input and output ports of the component, all indices
     * previously returned in calls to {@link #dequeueInputBuffer} and
     * {@link #dequeueOutputBuffer} become invalid.
     */
    public native final void flush();

    public final static class CryptoException extends RuntimeException {
        public CryptoException(int errorCode, String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        private int mErrorCode;
    }

    /**
     * After filling a range of the input buffer at the specified index
     * submit it to the component.
     *
     * Many decoders require the actual compressed data stream to be
     * preceded by "codec specific data", i.e. setup data used to initialize
     * the codec such as PPS/SPS in the case of AVC video or code tables
     * in the case of vorbis audio.
     * The class {@link android.media.MediaExtractor} provides codec
     * specific data as part of
     * the returned track format in entries named "csd-0", "csd-1" ...
     *
     * These buffers should be submitted using the flag {@link #BUFFER_FLAG_CODEC_CONFIG}.
     *
     * To indicate that this is the final piece of input data (or rather that
     * no more input data follows unless the decoder is subsequently flushed)
     * specify the flag {@link #BUFFER_FLAG_END_OF_STREAM}.
     *
     * @param index The index of a client-owned input buffer previously returned
     *              in a call to {@link #dequeueInputBuffer}.
     * @param offset The byte offset into the input buffer at which the data starts.
     * @param size The number of bytes of valid input data.
     * @param presentationTimeUs The time at which this buffer should be rendered.
     * @param flags A bitmask of flags {@link #BUFFER_FLAG_SYNC_FRAME},
     *              {@link #BUFFER_FLAG_CODEC_CONFIG} or {@link #BUFFER_FLAG_END_OF_STREAM}.
     * @throws CryptoException if a crypto object has been specified in
     *         {@link #configure}
     */
    public native final void queueInputBuffer(
            int index,
            int offset, int size, long presentationTimeUs, int flags)
        throws CryptoException;

    // The following mode constants MUST stay in sync with their equivalents
    // in media/hardware/CryptoAPI.h !
    public static final int CRYPTO_MODE_UNENCRYPTED = 0;
    public static final int CRYPTO_MODE_AES_CTR     = 1;

    /**
     * Metadata describing the structure of a (at least partially) encrypted
     * input sample.
     * A buffer's data is considered to be partitioned into "subSamples",
     * each subSample starts with a (potentially empty) run of plain,
     * unencrypted bytes followed by a (also potentially empty) run of
     * encrypted bytes.
     * numBytesOfClearData can be null to indicate that all data is encrypted.
     * This information encapsulates per-sample metadata as outlined in
     * ISO/IEC FDIS 23001-7:2011 "Common encryption in ISO base media file format files".
     */
    public final static class CryptoInfo {
        public void set(
                int newNumSubSamples,
                int[] newNumBytesOfClearData,
                int[] newNumBytesOfEncryptedData,
                byte[] newKey,
                byte[] newIV,
                int newMode) {
            numSubSamples = newNumSubSamples;
            numBytesOfClearData = newNumBytesOfClearData;
            numBytesOfEncryptedData = newNumBytesOfEncryptedData;
            key = newKey;
            iv = newIV;
            mode = newMode;
        }

        /**
         * The number of subSamples that make up the buffer's contents.
         */
        public int numSubSamples;
        /**
         * The number of leading unencrypted bytes in each subSample.
         */
        public int[] numBytesOfClearData;
        /**
         * The number of trailing encrypted bytes in each subSample.
         */
        public int[] numBytesOfEncryptedData;
        /**
         * A 16-byte opaque key
         */
        public byte[] key;
        /**
         * A 16-byte initialization vector
         */
        public byte[] iv;
        /**
         * The type of encryption that has been applied,
         * see {@link #CRYPTO_MODE_UNENCRYPTED} and {@link #CRYPTO_MODE_AES_CTR}.
         */
        public int mode;
    };

    /**
     * Similar to {@link #queueInputBuffer} but submits a buffer that is
     * potentially encrypted.
     * @param index The index of a client-owned input buffer previously returned
     *              in a call to {@link #dequeueInputBuffer}.
     * @param offset The byte offset into the input buffer at which the data starts.
     * @param info Metadata required to facilitate decryption, the object can be
     *             reused immediately after this call returns.
     * @param presentationTimeUs The time at which this buffer should be rendered.
     * @param flags A bitmask of flags {@link #BUFFER_FLAG_SYNC_FRAME},
     *              {@link #BUFFER_FLAG_CODEC_CONFIG} or {@link #BUFFER_FLAG_END_OF_STREAM}.
     */
    public native final void queueSecureInputBuffer(
            int index,
            int offset,
            CryptoInfo info,
            long presentationTimeUs,
            int flags) throws CryptoException;

    /**
     * Returns the index of an input buffer to be filled with valid data
     * or -1 if no such buffer is currently available.
     * This method will return immediately if timeoutUs == 0, wait indefinitely
     * for the availability of an input buffer if timeoutUs &lt; 0 or wait up
     * to "timeoutUs" microseconds if timeoutUs &gt; 0.
     * @param timeoutUs The timeout in microseconds, a negative timeout indicates "infinite".
     */
    public native final int dequeueInputBuffer(long timeoutUs);

    /**
     * If a non-negative timeout had been specified in the call
     * to {@link #dequeueOutputBuffer}, indicates that the call timed out.
     */
    public static final int INFO_TRY_AGAIN_LATER        = -1;

    /**
     * The output format has changed, subsequent data will follow the new
     * format. {@link #getOutputFormat} returns the new format.
     */
    public static final int INFO_OUTPUT_FORMAT_CHANGED  = -2;

    /**
     * The output buffers have changed, the client must refer to the new
     * set of output buffers returned by {@link #getOutputBuffers} from
     * this point on.
     */
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    /**
     * Dequeue an output buffer, block at most "timeoutUs" microseconds.
     * Returns the index of an output buffer that has been successfully
     * decoded or one of the INFO_* constants below.
     * @param info Will be filled with buffer meta data.
     * @param timeoutUs The timeout in microseconds, a negative timeout indicates "infinite".
     */
    public native final int dequeueOutputBuffer(
            BufferInfo info, long timeoutUs);

    /**
     * If you are done with a buffer, use this call to return the buffer to
     * the codec. If you previously specified a surface when configuring this
     * video decoder you can optionally render the buffer.
     * @param index The index of a client-owned output buffer previously returned
     *              in a call to {@link #dequeueOutputBuffer}.
     * @param render If a valid surface was specified when configuring the codec,
     *               passing true renders this output buffer to the surface.
     */
    public native final void releaseOutputBuffer(int index, boolean render);

    /**
     * Call this after dequeueOutputBuffer signals a format change by returning
     * {@link #INFO_OUTPUT_FORMAT_CHANGED}
     */
    public final MediaFormat getOutputFormat() {
        return new MediaFormat(getOutputFormatNative());
    }

    private native final Map<String, Object> getOutputFormatNative();

    /**
     * Call this after start() returns.
     */
    public ByteBuffer[] getInputBuffers() {
        return getBuffers(true /* input */);
    }

    /**
     * Call this after start() returns and whenever dequeueOutputBuffer
     * signals an output buffer change by returning
     * {@link #INFO_OUTPUT_BUFFERS_CHANGED}
     */
    public ByteBuffer[] getOutputBuffers() {
        return getBuffers(false /* input */);
    }

    /**
     * The content is scaled to the surface dimensions
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT               = 1;

    /**
     * The content is scaled, maintaining its aspect ratio, the whole
     * surface area is used, content may be cropped
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;

    /**
     * If a surface has been specified in a previous call to {@link #configure}
     * specifies the scaling mode to use. The default is "scale to fit".
     */
    public native final void setVideoScalingMode(int mode);

    private native final ByteBuffer[] getBuffers(boolean input);

    private static native final void native_init();

    private native final void native_setup(
            String name, boolean nameIsType, boolean encoder);

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private int mNativeContext;
}
