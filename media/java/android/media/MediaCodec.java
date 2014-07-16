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

import android.media.Image;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
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
 *
 * // if API level <= 20, get input and output buffer arrays here
 * ByteBuffer[] inputBuffers = codec.getInputBuffers();
 * ByteBuffer[] outputBuffers = codec.getOutputBuffers();
 * for (;;) {
 *   int inputBufferIndex = codec.dequeueInputBuffer(timeoutUs);
 *   if (inputBufferIndex &gt;= 0) {
 *     // if API level >= 21, get input buffer here
 *     ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
 *     // fill inputBuffers[inputBufferIndex] with valid data
 *     ...
 *     codec.queueInputBuffer(inputBufferIndex, ...);
 *   }
 *
 *   int outputBufferIndex = codec.dequeueOutputBuffer(timeoutUs);
 *   if (outputBufferIndex &gt;= 0) {
 *     // if API level >= 21, get output buffer here
 *     ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
 *     // outputBuffer is ready to be processed or rendered.
 *     ...
 *     codec.releaseOutputBuffer(outputBufferIndex, ...);
 *   } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
 *     // no needed to handle if API level >= 21 and using getOutputBuffer(int)
 *     outputBuffers = codec.getOutputBuffers();
 *   } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
 *     // Subsequent data will conform to new format.
 *     // can ignore if API level >= 21 and using getOutputFormat(outputBufferIndex)
 *     MediaFormat format = codec.getOutputFormat();
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
 * <p>
 * For API levels 20 and below:
 * The contents of these buffers are represented by the ByteBuffer[] arrays
 * accessible through {@link #getInputBuffers} and {@link #getOutputBuffers}.
 * <p>
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
 * <p>
 * Codec specific data included in the format passed to {@link #configure}
 * (in ByteBuffer entries with keys "csd-0", "csd-1", ...) is automatically
 * submitted to the codec, this data MUST NOT be submitted explicitly by the
 * client.
 * <p>
 * Once the client reaches the end of the input data it signals the end of
 * the input stream by specifying a flag of {@link #BUFFER_FLAG_END_OF_STREAM} in the call to
 * {@link #queueInputBuffer}. The codec will continue to return output buffers
 * until it eventually signals the end of the output stream by specifying
 * the same flag ({@link #BUFFER_FLAG_END_OF_STREAM}) on the BufferInfo returned in
 * {@link #dequeueOutputBuffer}.
 * <p>
 * In order to start decoding data that's not adjacent to previously submitted
 * data (i.e. after a seek) it is necessary to {@link #flush} the decoder.
 * Any input or output buffers the client may own at the point of the flush are
 * immediately revoked, i.e. after a call to {@link #flush} the client does not
 * own any buffers anymore.
 * Note that the format of the data submitted after a flush must not change,
 * flush does not support format discontinuities,
 * for this a full {@link #stop}, {@link #configure}, {@link #start}
 * cycle is necessary.
 *
 * <p> During its life, a codec conceptually exists in one of the following states:
 * Initialized, Configured, Executing, Error, Uninitialized, (omitting transitory states
 * between them). When created by one of the factory methods,
 * the codec is in the Initialized state; {@link #configure} brings it to the
 * Configured state; {@link #start} brings it to the Executing state.
 * In the Executing state, decoding or encoding occurs through the buffer queue
 * manipulation described above. The method {@link #stop}
 * returns the codec to the Initialized state, whereupon it may be configured again,
 * and {@link #release} brings the codec to the terminal Uninitialized state.  When
 * a codec error occurs, the codec moves to the Error state.  Use {@link #reset} to
 * bring the codec back to the Initialized state, or {@link #release} to move it
 * to the Uninitialized state.
 *
 * <p> The factory methods
 * {@link #createByCodecName},
 * {@link #createDecoderByType},
 * and {@link #createEncoderByType}
 * throw {@link java.io.IOException} on failure which
 * the caller must catch or declare to pass up.
 * MediaCodec methods throw {@link java.lang.IllegalStateException}
 * when the method is called from a codec state that does not allow it;
 * this is typically due to incorrect application API usage.
 * Methods involving secure buffers may throw
 * {@link MediaCodec.CryptoException#MediaCodec.CryptoException}, which
 * has further error information obtainable from {@link MediaCodec.CryptoException#getErrorCode}.
 *
 * <p> Internal codec errors result in a {@link MediaCodec.CodecException},
 * which may be due to media content corruption, hardware failure, resource exhaustion,
 * and so forth, even when the application is correctly using the API.
 * The recommended action when receiving a {@link MediaCodec.CodecException} can be determined by
 * calling {@link MediaCodec.CodecException#isRecoverable} and
 * {@link MediaCodec.CodecException#isTransient}.
 * If {@link MediaCodec.CodecException#isRecoverable} returns true,
 * then a {@link #stop}, {@link #configure}, and {@link #start} can be performed to recover.
 * If {@link MediaCodec.CodecException#isTransient} returns true,
 * then resources are temporarily unavailable and the method may be retried at a later time.
 * If both {@link MediaCodec.CodecException#isRecoverable}
 * and {@link MediaCodec.CodecException#isTransient} return false,
 * then the {@link MediaCodec.CodecException} is fatal and the codec must be
 * {@link #reset reset} or {@link #release released}.
 * Both {@link MediaCodec.CodecException#isRecoverable} and
 * {@link MediaCodec.CodecException#isTransient} do not return true at the same time.
 */
final public class MediaCodec {
    /**
     * Per buffer metadata includes an offset and size specifying
     * the range of valid data in the associated codec (output) buffer.
     */
    public final static class BufferInfo {
        /**
         * Update the buffer metadata information.
         *
         * @param newOffset the start-offset of the data in the buffer.
         * @param newSize   the amount of data (in bytes) in the buffer.
         * @param newTimeUs the presentation timestamp in microseconds.
         * @param newFlags  buffer flags associated with the buffer.  This
         * should be a combination of  {@link #BUFFER_FLAG_KEY_FRAME} and
         * {@link #BUFFER_FLAG_END_OF_STREAM}.
         */
        public void set(
                int newOffset, int newSize, long newTimeUs, int newFlags) {
            offset = newOffset;
            size = newSize;
            presentationTimeUs = newTimeUs;
            flags = newFlags;
        }

        /**
         * The start-offset of the data in the buffer.
         */
        public int offset;

        /**
         * The amount of data (in bytes) in the buffer.  If this is {@code 0},
         * the buffer has no data in it and can be discarded.  The only
         * use of a 0-size buffer is to carry the end-of-stream marker.
         */
        public int size;

        /**
         * The presentation timestamp in microseconds for the buffer.
         * This is derived from the presentation timestamp passed in
         * with the corresponding input buffer.  This should be ignored for
         * a 0-sized buffer.
         */
        public long presentationTimeUs;

        /**
         * Buffer flags associated with the buffer.  A combination of
         * {@link #BUFFER_FLAG_KEY_FRAME} and {@link #BUFFER_FLAG_END_OF_STREAM}.
         *
         * <p>Encoded buffers that are key frames are marked with
         * {@link #BUFFER_FLAG_KEY_FRAME}.
         *
         * <p>The last output buffer corresponding to the input buffer
         * marked with {@link #BUFFER_FLAG_END_OF_STREAM} will also be marked
         * with {@link #BUFFER_FLAG_END_OF_STREAM}. In some cases this could
         * be an empty buffer, whose sole purpose is to carry the end-of-stream
         * marker.
         */
        public int flags;
    };

    // The follow flag constants MUST stay in sync with their equivalents
    // in MediaCodec.h !

    /**
     * This indicates that the (encoded) buffer marked as such contains
     * the data for a key frame.
     *
     * @deprecated Use {@link #BUFFER_FLAG_KEY_FRAME} instead.
     */
    public static final int BUFFER_FLAG_SYNC_FRAME = 1;

    /**
     * This indicates that the (encoded) buffer marked as such contains
     * the data for a key frame.
     */
    public static final int BUFFER_FLAG_KEY_FRAME = 1;

    /**
     * This indicated that the buffer marked as such contains codec
     * initialization / codec specific data instead of media data.
     */
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;

    /**
     * This signals the end of stream, i.e. no buffers will be available
     * after this, unless of course, {@link #flush} follows.
     */
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;

    private EventHandler mEventHandler;
    private Callback mCallback;

    private static final int EVENT_CALLBACK = 1;
    private static final int EVENT_SET_CALLBACK = 2;

    private static final int CB_INPUT_AVAILABLE = 1;
    private static final int CB_OUTPUT_AVAILABLE = 2;
    private static final int CB_ERROR = 3;
    private static final int CB_OUTPUT_FORMAT_CHANGE = 4;

    private class EventHandler extends Handler {
        private MediaCodec mCodec;

        public EventHandler(MediaCodec codec, Looper looper) {
            super(looper);
            mCodec = codec;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_CALLBACK:
                {
                    handleCallback(msg);
                    break;
                }
                case EVENT_SET_CALLBACK:
                {
                    mCallback = (MediaCodec.Callback) msg.obj;
                    break;
                }
                default:
                {
                    break;
                }
            }
        }

        private void handleCallback(Message msg) {
            if (mCallback == null) {
                return;
            }

            switch (msg.arg1) {
                case CB_INPUT_AVAILABLE:
                {
                    int index = msg.arg2;
                    synchronized(mBufferLock) {
                        validateInputByteBuffer(mCachedInputBuffers, index);
                    }
                    mCallback.onInputBufferAvailable(mCodec, index);
                    break;
                }

                case CB_OUTPUT_AVAILABLE:
                {
                    int index = msg.arg2;
                    BufferInfo info = (MediaCodec.BufferInfo) msg.obj;
                    synchronized(mBufferLock) {
                        validateOutputByteBuffer(mCachedOutputBuffers, index, info);
                    }
                    mCallback.onOutputBufferAvailable(
                            mCodec, index, info);
                    break;
                }

                case CB_ERROR:
                {
                    mCallback.onError(mCodec, (MediaCodec.CodecException) msg.obj);
                    break;
                }

                case CB_OUTPUT_FORMAT_CHANGE:
                {
                    mCallback.onOutputFormatChanged(mCodec,
                            new MediaFormat((Map<String, Object>) msg.obj));
                    break;
                }

                default:
                {
                    break;
                }
            }
        }
    }

    /**
     * Instantiate a decoder supporting input data of the given mime type.
     *
     * The following is a partial list of defined mime types and their semantics:
     * <ul>
     * <li>"video/x-vnd.on2.vp8" - VP8 video (i.e. video in .webm)
     * <li>"video/x-vnd.on2.vp9" - VP9 video (i.e. video in .webm)
     * <li>"video/avc" - H.264/AVC video
     * <li>"video/hevc" - H.265/HEVC video
     * <li>"video/mp4v-es" - MPEG4 video
     * <li>"video/3gpp" - H.263 video
     * <li>"audio/3gpp" - AMR narrowband audio
     * <li>"audio/amr-wb" - AMR wideband audio
     * <li>"audio/mpeg" - MPEG1/2 audio layer III
     * <li>"audio/mp4a-latm" - AAC audio (note, this is raw AAC packets, not packaged in LATM!)
     * <li>"audio/vorbis" - vorbis audio
     * <li>"audio/g711-alaw" - G.711 alaw audio
     * <li>"audio/g711-mlaw" - G.711 ulaw audio
     * </ul>
     *
     * @param type The mime type of the input data.
     * @throws IOException if the codec cannot be created.
     * @throws IllegalArgumentException if type is not a valid mime type.
     * @throws NullPointerException if type is null.
     */
    public static MediaCodec createDecoderByType(String type)
            throws IOException {
        return new MediaCodec(type, true /* nameIsType */, false /* encoder */);
    }

    /**
     * Instantiate an encoder supporting output data of the given mime type.
     * @param type The desired mime type of the output data.
     * @throws IOException if the codec cannot be created.
     * @throws IllegalArgumentException if type is not a valid mime type.
     * @throws NullPointerException if type is null.
     */
    public static MediaCodec createEncoderByType(String type)
            throws IOException {
        return new MediaCodec(type, true /* nameIsType */, true /* encoder */);
    }

    /**
     * If you know the exact name of the component you want to instantiate
     * use this method to instantiate it. Use with caution.
     * Likely to be used with information obtained from {@link android.media.MediaCodecList}
     * @param name The name of the codec to be instantiated.
     * @throws IOException if the codec cannot be created.
     * @throws IllegalArgumentException if name is not valid.
     * @throws NullPointerException if name is null.
     */
    public static MediaCodec createByCodecName(String name)
            throws IOException {
        return new MediaCodec(
                name, false /* nameIsType */, false /* unused */);
    }

    private MediaCodec(
            String name, boolean nameIsType, boolean encoder) {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }
        mBufferLock = new Object();

        native_setup(name, nameIsType, encoder);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    /**
     * Returns the codec to its initial (Initialized) state.
     *
     * Call this if an {@link MediaCodec.CodecException#isRecoverable unrecoverable}
     * error has occured to reset the codec to its initial state after creation.
     *
     * @throws CodecException if an unrecoverable error has occured and the codec
     * could not be reset.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public final void reset() {
        freeAllTrackedBuffers(); // free buffers first
        native_reset();
    }

    private native final void native_reset();

    /**
     * Make sure you call this when you're done to free up any opened
     * component instance instead of relying on the garbage collector
     * to do this for you at some point in the future.
     */
    public final void release() {
        freeAllTrackedBuffers(); // free buffers first
        native_release();
    }

    private native final void native_release();

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
     * @throws IllegalArgumentException if the surface has been released (or is invalid),
     * or the format is unacceptable (e.g. missing a mandatory key),
     * or the flags are not set properly
     * (e.g. missing {@link #CONFIGURE_FLAG_ENCODE} for an encoder).
     * @throws IllegalStateException if not in the Initialized state.
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

    private native final void native_setCallback(Callback cb);

    private native final void native_configure(
            String[] keys, Object[] values,
            Surface surface, MediaCrypto crypto, int flags);

    /**
     * Requests a Surface to use as the input to an encoder, in place of input buffers.  This
     * may only be called after {@link #configure} and before {@link #start}.
     * <p>
     * The application is responsible for calling release() on the Surface when
     * done.
     * <p>
     * The Surface must be rendered with a hardware-accelerated API, such as OpenGL ES.
     * {@link android.view.Surface#lockCanvas(android.graphics.Rect)} may fail or produce
     * unexpected results.
     * @throws IllegalStateException if not in the Configured state.
     */
    public native final Surface createInputSurface();

    /**
     * After successfully configuring the component, call start. On return
     * you can query the component for its input/output buffers.
     * @throws IllegalStateException if not in the Configured state.
     * @throws MediaCodec.CodecException upon codec error. Note that some codec errors
     * for start may be attributed to future method calls.
     */
    public final void start() {
        native_start();
        synchronized(mBufferLock) {
            cacheBuffers(true /* input */);
            cacheBuffers(false /* input */);
        }
    }
    private native final void native_start();

    /**
     * Finish the decode/encode session, note that the codec instance
     * remains active and ready to be {@link #start}ed again.
     * To ensure that it is available to other client call {@link #release}
     * and don't just rely on garbage collection to eventually do this for you.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public final void stop() {
        native_stop();
        freeAllTrackedBuffers();

        if (mEventHandler != null) {
            mEventHandler.removeMessages(EVENT_CALLBACK);
            mEventHandler.removeMessages(EVENT_SET_CALLBACK);
        }
    }

    private native final void native_stop();

    /**
     * Flush both input and output ports of the component, all indices
     * previously returned in calls to {@link #dequeueInputBuffer} and
     * {@link #dequeueOutputBuffer} become invalid.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final void flush() {
        synchronized(mBufferLock) {
            invalidateByteBuffers(mCachedInputBuffers);
            invalidateByteBuffers(mCachedOutputBuffers);
            invalidateByteBuffers(mDequeuedInputBuffers);
            invalidateByteBuffers(mDequeuedOutputBuffers);
            freeImages(mDequeuedInputImages);
            freeImages(mDequeuedOutputImages);
        }
        native_flush();
    }

    private native final void native_flush();

    /**
     * Thrown when an internal codec error occurs.
     */
    public final static class CodecException extends IllegalStateException {
        public CodecException(int errorCode, int actionCode, String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
            mActionCode = actionCode;
        }

        /**
         * Returns true if the codec exception is a transient issue,
         * perhaps due to resource constraints, and that the method
         * (or encoding/decoding) may be retried at a later time.
         */
        public boolean isTransient() {
            return mActionCode == ACTION_TRANSIENT;
        }

        /**
         * Returns true if the codec cannot proceed further,
         * but can be recovered by stopping, configuring,
         * and starting again.
         */
        public boolean isRecoverable() {
            return mActionCode == ACTION_RECOVERABLE;
        }

        /**
         * Retrieve the error code associated with a CodecException.
         * This is opaque diagnostic information and may depend on
         * hardware or API level.
         */
        public int getErrorCode() {
            return mErrorCode;
        }

        /* Must be in sync with android_media_MediaCodec.cpp */
        private final static int ACTION_TRANSIENT = 1;
        private final static int ACTION_RECOVERABLE = 2;

        private final int mErrorCode;
        private final int mActionCode;
    }

    /**
     * Thrown when a crypto error occurs while queueing a secure input buffer.
     */
    public final static class CryptoException extends RuntimeException {
        public CryptoException(int errorCode, String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
        }

        /**
         * This indicates that no key has been set to perform the requested
         * decrypt operation.
         */
        public static final int ERROR_NO_KEY = 1;

        /**
         * This indicates that the key used for decryption is no longer
         * valid due to license term expiration.
         */
        public static final int ERROR_KEY_EXPIRED = 2;

        /**
         * This indicates that a required crypto resource was not able to be
         * allocated while attempting the requested operation.
         */
        public static final int ERROR_RESOURCE_BUSY = 3;

        /**
         * Retrieve the error code associated with a CryptoException
         */
        public int getErrorCode() {
            return mErrorCode;
        }

        private int mErrorCode;
    }

    /**
     * After filling a range of the input buffer at the specified index
     * submit it to the component. Once an input buffer is queued to
     * the codec, it MUST NOT be used until it is later retrieved by
     * {@link #getInputBuffer} in response to a {@link #dequeueInputBuffer}
     * return value or a {@link Callback#onInputBufferAvailable}
     * callback.
     * <p>
     * Many decoders require the actual compressed data stream to be
     * preceded by "codec specific data", i.e. setup data used to initialize
     * the codec such as PPS/SPS in the case of AVC video or code tables
     * in the case of vorbis audio.
     * The class {@link android.media.MediaExtractor} provides codec
     * specific data as part of
     * the returned track format in entries named "csd-0", "csd-1" ...
     * <p>
     * These buffers can be submitted directly after {@link #start} or
     * {@link #flush} by specifying the flag {@link
     * #BUFFER_FLAG_CODEC_CONFIG}.  However, if you configure the
     * codec with a {@link MediaFormat} containing these keys, they
     * will be automatically submitted by MediaCodec directly after
     * start.  Therefore, the use of {@link
     * #BUFFER_FLAG_CODEC_CONFIG} flag is discouraged and is
     * recommended only for advanced users.
     * <p>
     * To indicate that this is the final piece of input data (or rather that
     * no more input data follows unless the decoder is subsequently flushed)
     * specify the flag {@link #BUFFER_FLAG_END_OF_STREAM}.
     *
     * @param index The index of a client-owned input buffer previously returned
     *              in a call to {@link #dequeueInputBuffer}.
     * @param offset The byte offset into the input buffer at which the data starts.
     * @param size The number of bytes of valid input data.
     * @param presentationTimeUs The presentation timestamp in microseconds for this
     *                           buffer. This is normally the media time at which this
     *                           buffer should be presented (rendered).
     * @param flags A bitmask of flags
     *              {@link #BUFFER_FLAG_CODEC_CONFIG} and {@link #BUFFER_FLAG_END_OF_STREAM}.
     *              While not prohibited, most codecs do not use the
     *              {@link #BUFFER_FLAG_KEY_FRAME} flag for input buffers.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     * @throws CryptoException if a crypto object has been specified in
     *         {@link #configure}
     */
    public final void queueInputBuffer(
            int index,
            int offset, int size, long presentationTimeUs, int flags)
        throws CryptoException {
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedInputBuffers, index);
            updateDequeuedByteBuffer(mDequeuedInputBuffers, index, null);
        }
        native_queueInputBuffer(
                index, offset, size, presentationTimeUs, flags);
    }

    private native final void native_queueInputBuffer(
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

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(numSubSamples + " subsamples, key [");
            String hexdigits = "0123456789abcdef";
            for (int i = 0; i < key.length; i++) {
                builder.append(hexdigits.charAt((key[i] & 0xf0) >> 4));
                builder.append(hexdigits.charAt(key[i] & 0x0f));
            }
            builder.append("], iv [");
            for (int i = 0; i < key.length; i++) {
                builder.append(hexdigits.charAt((iv[i] & 0xf0) >> 4));
                builder.append(hexdigits.charAt(iv[i] & 0x0f));
            }
            builder.append("], clear ");
            builder.append(Arrays.toString(numBytesOfClearData));
            builder.append(", encrypted ");
            builder.append(Arrays.toString(numBytesOfEncryptedData));
            return builder.toString();
        }
    };

    /**
     * Similar to {@link #queueInputBuffer} but submits a buffer that is
     * potentially encrypted.
     * @param index The index of a client-owned input buffer previously returned
     *              in a call to {@link #dequeueInputBuffer}.
     * @param offset The byte offset into the input buffer at which the data starts.
     * @param info Metadata required to facilitate decryption, the object can be
     *             reused immediately after this call returns.
     * @param presentationTimeUs The presentation timestamp in microseconds for this
     *                           buffer. This is normally the media time at which this
     *                           buffer should be presented (rendered).
     * @param flags A bitmask of flags
     *              {@link #BUFFER_FLAG_CODEC_CONFIG} and {@link #BUFFER_FLAG_END_OF_STREAM}.
     *              While not prohibited, most codecs do not use the
     *              {@link #BUFFER_FLAG_KEY_FRAME} flag for input buffers.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     * @throws CryptoException if an error occurs while attempting to decrypt the buffer.
     *              An error code associated with the exception helps identify the
     *              reason for the failure.
     */
    public final void queueSecureInputBuffer(
            int index,
            int offset,
            CryptoInfo info,
            long presentationTimeUs,
            int flags) throws CryptoException {
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedInputBuffers, index);
            updateDequeuedByteBuffer(mDequeuedInputBuffers, index, null);
        }
        native_queueSecureInputBuffer(
                index, offset, info, presentationTimeUs, flags);
    }

    private native final void native_queueSecureInputBuffer(
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
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final int dequeueInputBuffer(long timeoutUs) {
        int res = native_dequeueInputBuffer(timeoutUs);
        if (res >= 0) {
            synchronized(mBufferLock) {
                validateInputByteBuffer(mCachedInputBuffers, res);
            }
        }
        return res;
    }

    private native final int native_dequeueInputBuffer(long timeoutUs);

    /**
     * If a non-negative timeout had been specified in the call
     * to {@link #dequeueOutputBuffer}, indicates that the call timed out.
     */
    public static final int INFO_TRY_AGAIN_LATER        = -1;

    /**
     * The output format has changed, subsequent data will follow the new
     * format. {@link #getOutputFormat()} returns the new format.  Note, that
     * you can also use the new {@link #getOutputFormat(int)} method to
     * get the format for a specific output buffer.  This frees you from
     * having to track output format changes.
     */
    public static final int INFO_OUTPUT_FORMAT_CHANGED  = -2;

    /**
     * The output buffers have changed, the client must refer to the new
     * set of output buffers returned by {@link #getOutputBuffers} from
     * this point on.
     *
     * @deprecated This return value can be ignored as {@link
     * #getOutputBuffers} has been deprecated.  Client should
     * request a current buffer using on of the get-buffer or
     * get-image methods each time one has been dequeued.
     */
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    /**
     * Dequeue an output buffer, block at most "timeoutUs" microseconds.
     * Returns the index of an output buffer that has been successfully
     * decoded or one of the INFO_* constants below.
     * @param info Will be filled with buffer meta data.
     * @param timeoutUs The timeout in microseconds, a negative timeout indicates "infinite".
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final int dequeueOutputBuffer(
            BufferInfo info, long timeoutUs) {
        int res = native_dequeueOutputBuffer(info, timeoutUs);
        synchronized(mBufferLock) {
            if (res == INFO_OUTPUT_BUFFERS_CHANGED) {
                cacheBuffers(false /* input */);
            } else if (res >= 0) {
                validateOutputByteBuffer(mCachedOutputBuffers, res, info);
            }
        }
        return res;
    }

    private native final int native_dequeueOutputBuffer(
            BufferInfo info, long timeoutUs);

    /**
     * If you are done with a buffer, use this call to return the buffer to
     * the codec. If you previously specified a surface when configuring this
     * video decoder you can optionally render the buffer.
     *
     * Once an output buffer is released to the codec, it MUST NOT
     * be used until it is later retrieved by {@link #getOutputBuffer} in response
     * to a {@link #dequeueOutputBuffer} return value or a
     * {@link Callback#onOutputBufferAvailable} callback.
     *
     * @param index The index of a client-owned output buffer previously returned
     *              from a call to {@link #dequeueOutputBuffer}.
     * @param render If a valid surface was specified when configuring the codec,
     *               passing true renders this output buffer to the surface.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final void releaseOutputBuffer(int index, boolean render) {
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            updateDequeuedByteBuffer(mDequeuedOutputBuffers, index, null);
        }
        releaseOutputBuffer(index, render, false /* updatePTS */, 0 /* dummy */);
    }

    /**
     * If you are done with a buffer, use this call to update its surface timestamp
     * and return it to the codec to render it on the output surface. If you
     * have not specified an output surface when configuring this video codec,
     * this call will simply return the buffer to the codec.<p>
     *
     * The timestamp may have special meaning depending on the destination surface.
     *
     * <table>
     * <tr><th>SurfaceView specifics</th></tr>
     * <tr><td>
     * If you render your buffer on a {@link android.view.SurfaceView},
     * you can use the timestamp to render the buffer at a specific time (at the
     * VSYNC at or after the buffer timestamp).  For this to work, the timestamp
     * needs to be <i>reasonably close</i> to the current {@link System#nanoTime}.
     * Currently, this is set as within one (1) second. A few notes:
     *
     * <ul>
     * <li>the buffer will not be returned to the codec until the timestamp
     * has passed and the buffer is no longer used by the {@link android.view.Surface}.
     * <li>buffers are processed sequentially, so you may block subsequent buffers to
     * be displayed on the {@link android.view.Surface}.  This is important if you
     * want to react to user action, e.g. stop the video or seek.
     * <li>if multiple buffers are sent to the {@link android.view.Surface} to be
     * rendered at the same VSYNC, the last one will be shown, and the other ones
     * will be dropped.
     * <li>if the timestamp is <em>not</em> "reasonably close" to the current system
     * time, the {@link android.view.Surface} will ignore the timestamp, and
     * display the buffer at the earliest feasible time.  In this mode it will not
     * drop frames.
     * <li>for best performance and quality, call this method when you are about
     * two VSYNCs' time before the desired render time.  For 60Hz displays, this is
     * about 33 msec.
     * </ul>
     * </td></tr>
     * </table>
     *
     * Once an output buffer is released to the codec, it MUST NOT
     * be used until it is later retrieved by {@link #getOutputBuffer} in response
     * to a {@link #dequeueOutputBuffer} return value or a
     * {@link Callback#onOutputBufferAvailable} callback.
     *
     * @param index The index of a client-owned output buffer previously returned
     *              from a call to {@link #dequeueOutputBuffer}.
     * @param renderTimestampNs The timestamp to associate with this buffer when
     *              it is sent to the Surface.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final void releaseOutputBuffer(int index, long renderTimestampNs) {
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            updateDequeuedByteBuffer(mDequeuedOutputBuffers, index, null);
        }
        releaseOutputBuffer(
                index, true /* render */, true /* updatePTS */, renderTimestampNs);
    }

    private native final void releaseOutputBuffer(
            int index, boolean render, boolean updatePTS, long timeNs);

    /**
     * Signals end-of-stream on input.  Equivalent to submitting an empty buffer with
     * {@link #BUFFER_FLAG_END_OF_STREAM} set.  This may only be used with
     * encoders receiving input from a Surface created by {@link #createInputSurface}.
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public native final void signalEndOfInputStream();

    /**
     * Call this after dequeueOutputBuffer signals a format change by returning
     * {@link #INFO_OUTPUT_FORMAT_CHANGED}.
     * You can also call this after {@link #configure} returns
     * successfully to get the output format initially configured
     * for the codec.  Do this to determine what optional
     * configuration parameters were supported by the codec.
     *
     * @throws IllegalStateException if not in the Executing or
     *                               Configured state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final MediaFormat getOutputFormat() {
        return new MediaFormat(getFormatNative(false /* input */));
    }

    /**
     * Call this after {@link #configure} returns successfully to
     * get the input format accepted by the codec. Do this to
     * determine what optional configuration parameters were
     * supported by the codec.
     *
     * @throws IllegalStateException if not in the Executing or
     *                               Configured state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final MediaFormat getInputFormat() {
        return new MediaFormat(getFormatNative(true /* input */));
    }

    /**
     * Returns the output format for a specific output buffer.
     *
     * @param index The index of a client-owned input buffer previously
     *              returned from a call to {@link #dequeueInputBuffer}.
     *
     * @return the format for the output buffer, or null if the index
     * is not a dequeued output buffer.
     */
    public final MediaFormat getOutputFormat(int index) {
        return new MediaFormat(getOutputFormatNative(index));
    }

    private native final Map<String, Object> getFormatNative(boolean input);

    private native final Map<String, Object> getOutputFormatNative(int index);

    private ByteBuffer[] mCachedInputBuffers;
    private ByteBuffer[] mCachedOutputBuffers;
    private ByteBuffer[] mDequeuedInputBuffers;
    private ByteBuffer[] mDequeuedOutputBuffers;
    private Image[] mDequeuedInputImages;
    private Image[] mDequeuedOutputImages;
    final private Object mBufferLock;

    private final void invalidateByteBuffer(
            ByteBuffer[] buffers, int index) {
        if (index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(false);
            }
        }
    }

    private final void validateInputByteBuffer(
            ByteBuffer[] buffers, int index) {
        if (index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(true);
                buffer.clear();
            }
        }
    }

    private final void validateOutputByteBuffer(
            ByteBuffer[] buffers, int index, BufferInfo info) {
        if (index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(true);
                buffer.limit(info.offset + info.size).position(info.offset);
            }
        }
    }

    private final void invalidateByteBuffers(ByteBuffer[] buffers) {
        if (buffers != null) {
            for (ByteBuffer buffer: buffers) {
                if (buffer != null) {
                    buffer.setAccessible(false);
                }
            }
        }
    }

    private final void freeByteBuffer(ByteBuffer buffer) {
        if (buffer != null /* && buffer.isDirect() */) {
            // all of our ByteBuffers are direct
            java.nio.NioUtils.freeDirectBuffer(buffer);
        }
    }

    private final void freeByteBuffers(ByteBuffer[] buffers) {
        if (buffers != null) {
            for (ByteBuffer buffer: buffers) {
                freeByteBuffer(buffer);
            }
        }
    }

    private final void freeImage(Image image) {
        if (image != null) {
            image.close();
        }
    }

    private final void freeImages(Image[] images) {
        if (images != null) {
            for (Image image: images) {
                freeImage(image);
            }
        }
    }

    private final void freeAllTrackedBuffers() {
        freeByteBuffers(mCachedInputBuffers);
        freeByteBuffers(mCachedOutputBuffers);
        freeImages(mDequeuedInputImages);
        freeImages(mDequeuedOutputImages);
        freeByteBuffers(mDequeuedInputBuffers);
        freeByteBuffers(mDequeuedOutputBuffers);
        mCachedInputBuffers = null;
        mCachedOutputBuffers = null;
        mDequeuedInputImages = null;
        mDequeuedOutputImages = null;
        mDequeuedInputBuffers = null;
        mDequeuedOutputBuffers = null;
    }

    private final void cacheBuffers(boolean input) {
        ByteBuffer[] buffers = getBuffers(input);
        invalidateByteBuffers(buffers);
        if (input) {
            mCachedInputBuffers = buffers;
            mDequeuedInputImages = new Image[buffers.length];
            mDequeuedInputBuffers = new ByteBuffer[buffers.length];
        } else {
            mCachedOutputBuffers = buffers;
            mDequeuedOutputImages = new Image[buffers.length];
            mDequeuedOutputBuffers = new ByteBuffer[buffers.length];
        }
    }

    /**
     * Retrieve the set of input buffers.  Call this after start()
     * returns. After calling this method, any ByteBuffers
     * previously returned by an earlier call to this method MUST no
     * longer be used.
     *
     * @deprecated Use the new {@link #getInputBuffer} method instead
     * each time an input buffer is dequeued.
     *
     * <b>Note:</b>As of API 21, dequeued input buffers are
     * automatically {@link java.nio.Buffer#clear cleared}.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public ByteBuffer[] getInputBuffers() {
        if (mCachedInputBuffers == null) {
            throw new IllegalStateException();
        }
        // FIXME: check codec status
        return mCachedInputBuffers;
    }

    /**
     * Retrieve the set of output buffers.  Call this after start()
     * returns and whenever dequeueOutputBuffer signals an output
     * buffer change by returning {@link
     * #INFO_OUTPUT_BUFFERS_CHANGED}. After calling this method, any
     * ByteBuffers previously returned by an earlier call to this
     * method MUST no longer be used.
     *
     * @deprecated Use the new {@link #getOutputBuffer} method instead
     * each time an output buffer is dequeued.  This method is not
     * supported if codec is configured in asynchronous mode.
     *
     * <b>Note:</b>As of API 21, the position and limit of output
     * buffers that are dequeued will be set to the valid data
     * range.
     *
     * @throws IllegalStateException if not in the Executing state,
     *         or codec is configured in asynchronous mode.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public ByteBuffer[] getOutputBuffers() {
        if (mCachedOutputBuffers == null) {
            throw new IllegalStateException();
        }
        // FIXME: check codec status
        return mCachedOutputBuffers;
    }

    private boolean updateDequeuedByteBuffer(
            ByteBuffer[] buffers, int index, ByteBuffer newBuffer) {
        if (index < 0 || index >= buffers.length) {
            return false;
        }
        freeByteBuffer(buffers[index]);
        buffers[index] = newBuffer;
        return newBuffer != null;
    }

    private boolean updateDequeuedImage(
            Image[] images, int index, Image newImage) {
        if (index < 0 || index >= images.length) {
            return false;
        }
        freeImage(images[index]);
        images[index] = newImage;
        return newImage != null;
    }

    /**
     * Returns a {@link java.nio.Buffer#clear cleared}, writable ByteBuffer
     * object for a dequeued input buffer index to contain the input data.
     *
     * After calling this method any ByteBuffer or Image object
     * previously returned for the same input index MUST no longer
     * be used.
     *
     * @param index The index of a client-owned input buffer previously
     *              returned from a call to {@link #dequeueInputBuffer},
     *              or received via an onInputBufferAvailable callback.
     *
     * @return the input buffer, or null if the index is not a dequeued
     * input buffer, or if the codec is configured for surface input.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public ByteBuffer getInputBuffer(int index) {
        ByteBuffer newBuffer = getBuffer(true /* input */, index);
        synchronized(mBufferLock) {
            if (updateDequeuedByteBuffer(mDequeuedInputBuffers, index, newBuffer)) {
                updateDequeuedImage(mDequeuedInputImages, index, null);
                invalidateByteBuffer(mCachedInputBuffers, index);
                return newBuffer;
            }
        }
        return null;
    }

    /**
     * Returns a writable Image object for a dequeued input buffer
     * index to contain the raw input video frame.
     *
     * After calling this method any ByteBuffer or Image object
     * previously returned for the same input index MUST no longer
     * be used.
     *
     * @param index The index of a client-owned input buffer previously
     *              returned from a call to {@link #dequeueInputBuffer},
     *              or received via an onInputBufferAvailable callback.
     *
     * @return the input image, or null if the index is not a
     * dequeued input buffer, or not a ByteBuffer that contains a
     * raw image.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public Image getInputImage(int index) {
        Image newImage = getImage(true /* input */, index);
        if (updateDequeuedImage(mDequeuedInputImages, index, newImage)) {
            updateDequeuedByteBuffer(mDequeuedInputBuffers, index, null);
            invalidateByteBuffer(mCachedInputBuffers, index);
            return newImage;
        }
        return null;
    }

    /**
     * Returns a read-only ByteBuffer for a dequeued output buffer
     * index. The position and limit of the returned buffer are set
     * to the valid output data.
     *
     * After calling this method, any ByteBuffer or Image object
     * previously returned for the same output index MUST no longer
     * be used.
     *
     * @param index The index of a client-owned output buffer previously
     *              returned from a call to {@link #dequeueOutputBuffer},
     *              or received via an onOutputBufferAvailable callback.
     *
     * @return the output buffer, or null if the index is not a dequeued
     * output buffer, or the codec is configured with an output surface.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public ByteBuffer getOutputBuffer(int index) {
        ByteBuffer newBuffer = getBuffer(false /* input */, index);
        synchronized(mBufferLock) {
            if (updateDequeuedByteBuffer(mDequeuedOutputBuffers, index, newBuffer)) {
                updateDequeuedImage(mDequeuedOutputImages, index, null);
                invalidateByteBuffer(mCachedOutputBuffers, index);
                return newBuffer;
            }
        }
        return null;
    }

    /**
     * Returns a read-only Image object for a dequeued output buffer
     * index that contains the raw video frame.
     *
     * After calling this method, any ByteBuffer or Image object previously
     * returned for the same output index MUST no longer be used.
     *
     * @param index The index of a client-owned output buffer previously
     *              returned from a call to {@link #dequeueOutputBuffer},
     *              or received via an onOutputBufferAvailable callback.
     *
     * @return the output image, or null if the index is not a
     * dequeued output buffer, not a raw video frame, or if the codec
     * was configured with an output surface.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public Image getOutputImage(int index) {
        Image newImage = getImage(false /* input */, index);
        synchronized(mBufferLock) {
            if (updateDequeuedImage(mDequeuedOutputImages, index, newImage)) {
                updateDequeuedByteBuffer(mDequeuedOutputBuffers, index, null);
                invalidateByteBuffer(mCachedOutputBuffers, index);
                return newImage;
            }
        }
        return null;
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
     * @throws IllegalArgumentException if mode is not recognized.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public native final void setVideoScalingMode(int mode);

    /**
     * Get the component name. If the codec was created by createDecoderByType
     * or createEncoderByType, what component is chosen is not known beforehand.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public native final String getName();

    /**
     * Change a video encoder's target bitrate on the fly. The value is an
     * Integer object containing the new bitrate in bps.
     */
    public static final String PARAMETER_KEY_VIDEO_BITRATE = "video-bitrate";

    /**
     * Temporarily suspend/resume encoding of input data. While suspended
     * input data is effectively discarded instead of being fed into the
     * encoder. This parameter really only makes sense to use with an encoder
     * in "surface-input" mode, as the client code has no control over the
     * input-side of the encoder in that case.
     * The value is an Integer object containing the value 1 to suspend
     * or the value 0 to resume.
     */
    public static final String PARAMETER_KEY_SUSPEND = "drop-input-frames";

    /**
     * Request that the encoder produce a sync frame "soon".
     * Provide an Integer with the value 0.
     */
    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME = "request-sync";

    /**
     * Communicate additional parameter changes to the component instance.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public final void setParameters(Bundle params) {
        if (params == null) {
            return;
        }

        String[] keys = new String[params.size()];
        Object[] values = new Object[params.size()];

        int i = 0;
        for (final String key: params.keySet()) {
            keys[i] = key;
            values[i] = params.get(key);
            ++i;
        }

        setParameters(keys, values);
    }

    /**
     * Sets an asynchronous callback for actionable MediaCodec events.
     *
     * If the client intends to use the component in asynchronous mode,
     * a valid callback should be provided before {@link #configure} is called.
     *
     * When asynchronous callback is enabled, the client should not call
     * {@link #dequeueInputBuffer(long)} or {@link #dequeueOutputBuffer(BufferInfo, long)}
     *
     * @param cb The callback that will run.
     */
    public void setCallback(/* MediaCodec. */ Callback cb) {
        if (mEventHandler != null) {
            // set java callback on handler
            Message msg = mEventHandler.obtainMessage(EVENT_SET_CALLBACK, 0, 0, cb);
            mEventHandler.sendMessage(msg);

            // set native handler here, don't post to handler because
            // it may cause the callback to be delayed and set in a wrong state,
            // and MediaCodec is already doing it on looper.
            native_setCallback(cb);
        }
    }

    /**
     * MediaCodec callback interface. Used to notify the user asynchronously
     * of various MediaCodec events.
     */
    public static abstract class Callback {
        /**
         * Called when an input buffer becomes available.
         *
         * @param codec The MediaCodec object.
         * @param index The index of the available input buffer.
         */
        public abstract void onInputBufferAvailable(MediaCodec codec, int index);

        /**
         * Called when an output buffer becomes available.
         *
         * @param codec The MediaCodec object.
         * @param index The index of the available output buffer.
         * @param info Info regarding the available output buffer {@link MediaCodec.BufferInfo}.
         */
        public abstract void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info);

        /**
         * Called when the MediaCodec encountered an error
         *
         * @param codec The MediaCodec object.
         * @param e The {@link MediaCodec.CodecException} object describing the error.
         */
        public abstract void onError(MediaCodec codec, CodecException e);

        /**
         * Called when the output format has changed
         *
         * @param codec The MediaCodec object.
         * @param format The new output format.
         */
        public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format);
    }

    private void postEventFromNative(
            int what, int arg1, int arg2, Object obj) {
        if (mEventHandler != null) {
            Message msg = mEventHandler.obtainMessage(what, arg1, arg2, obj);
            mEventHandler.sendMessage(msg);
        }
    }

    private native final void setParameters(String[] keys, Object[] values);

    /**
     * Get the codec info. If the codec was created by createDecoderByType
     * or createEncoderByType, what component is chosen is not known beforehand,
     * and thus the caller does not have the MediaCodecInfo.
     * @throws IllegalStateException if in the Uninitialized state.
     */
    public MediaCodecInfo getCodecInfo() {
        return MediaCodecList.getCodecInfoAt(
                   MediaCodecList.findCodecByName(getName()));
    }

    private native final ByteBuffer[] getBuffers(boolean input);

    private native final ByteBuffer getBuffer(boolean input, int index);

    private native final Image getImage(boolean input, int index);

    private static native final void native_init();

    private native final void native_setup(
            String name, boolean nameIsType, boolean encoder);

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;
}
