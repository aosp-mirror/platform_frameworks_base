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

import android.media.Crypto;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * MediaCodec class can be used to access low-level media codec, i.e.
 * encoder/decoder components.
 * @hide
*/
final public class MediaCodec {
    /** Per buffer metadata includes an offset and size specifying
        the range of valid data in the associated codec buffer.
    */
    public final static class BufferInfo {
        public void set(
                int offset, int size, long timeUs, int flags) {
            mOffset = offset;
            mSize = size;
            mPresentationTimeUs = timeUs;
            mFlags = flags;
        }

        public int mOffset;
        public int mSize;
        public long mPresentationTimeUs;
        public int mFlags;
    };

    // The follow flag constants MUST stay in sync with their equivalents
    // in MediaCodec.h !
    public static int FLAG_SYNCFRAME   = 1;
    public static int FLAG_CODECCONFIG = 2;
    public static int FLAG_EOS         = 4;

    // The following mode constants MUST stay in sync with their equivalents
    // in media/hardware/CryptoAPI.h !
    public static int MODE_UNENCRYPTED = 0;
    public static int MODE_AES_CTR     = 1;

    /** Instantiate a codec component by mime type. For decoder components
        this is the mime type of media that this decoder should be able to
        decoder, for encoder components it's the type of media this encoder
        should encode _to_.
    */
    public static MediaCodec CreateByType(String type, boolean encoder) {
        return new MediaCodec(type, true /* nameIsType */, encoder);
    }

    /** If you know the exact name of the component you want to instantiate
        use this method to instantiate it. Use with caution.
    */
    public static MediaCodec CreateByComponentName(String name) {
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

    // Make sure you call this when you're done to free up any opened
    // component instance instead of relying on the garbage collector
    // to do this for you at some point in the future.
    public native final void release();

    public static int CONFIGURE_FLAG_ENCODE = 1;

    /** Configures a component.
     *  @param format A map of string/value pairs describing the input format
     *                (decoder) or the desired output format.
     *
     *                Video formats have the following fields:
     *                  "mime"          - String
     *                  "width"         - Integer
     *                  "height"        - Integer
     *                  optional "max-input-size"       - Integer
     *
     *                Audio formats have the following fields:
     *                  "mime"          - String
     *                  "channel-count" - Integer
     *                  "sample-rate"   - Integer
     *                  optional "max-input-size"       - Integer
     *
     *                If the format is used to configure an encoder, additional
     *                fields must be included:
     *                  "bitrate" - Integer (in bits/sec)
     *
     *                for video formats:
     *                  "color-format"          - Integer
     *                  "frame-rate"            - Integer or Float
     *                  "i-frame-interval"      - Integer
     *                  optional "stride"       - Integer, defaults to "width"
     *                  optional "slice-height" - Integer, defaults to "height"
     *
     *  @param surface Specify a surface on which to render the output of this
     *                 decoder.
     *  @param crypto  Specify a crypto object to facilitate secure decryption
     *                 of the media data.
     *  @param flags   Specify {@link #CONFIGURE_FLAG_ENCODE} to configure the
     *                 component as an encoder.
    */
    public void configure(
            Map<String, Object> format,
            Surface surface, Crypto crypto, int flags) {
        String[] keys = null;
        Object[] values = null;

        if (format != null) {
            keys = new String[format.size()];
            values = new Object[format.size()];

            int i = 0;
            for (Map.Entry<String, Object> entry: format.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                ++i;
            }
        }

        native_configure(keys, values, surface, crypto, flags);
    }

    private native final void native_configure(
            String[] keys, Object[] values,
            Surface surface, Crypto crypto, int flags);

    /** After successfully configuring the component, call start. On return
     *  you can query the component for its input/output buffers.
    */
    public native final void start();

    public native final void stop();

    /** Flush both input and output ports of the component, all indices
     *  previously returned in calls to dequeueInputBuffer and
     *  dequeueOutputBuffer become invalid.
    */
    public native final void flush();

    /** After filling a range of the input buffer at the specified index
     *  submit it to the component.
     *
     *  Many decoders require the actual compressed data stream to be
     *  preceded by "codec specific data", i.e. setup data used to initialize
     *  the codec such as PPS/SPS in the case of AVC video or code tables
     *  in the case of vorbis audio.
     *  The class MediaExtractor provides codec specific data as part of
     *  the returned track format in entries named "csd-0", "csd-1" ...
     *
     *  These buffers should be submitted using the flag {@link #FLAG_CODECCONFIG}.
     *
     *  To indicate that this is the final piece of input data (or rather that
     *  no more input data follows unless the decoder is subsequently flushed)
     *  specify the flag {@link FLAG_EOS}.
    */
    public native final void queueInputBuffer(
            int index,
            int offset, int size, long presentationTimeUs, int flags);

    /** Similar to {@link queueInputBuffer} but submits a buffer that is
     *  potentially encrypted. The buffer's data is considered to be
     *  partitioned into "subSamples", each subSample starts with a
     *  (potentially empty) run of plain, unencrypted bytes followed
     *  by a (also potentially empty) run of encrypted bytes.
     *  @param numBytesOfClearData The number of leading unencrypted bytes in
     *                             each subSample.
     *  @param numBytesOfEncryptedData The number of trailing encrypted bytes
     *                             in each subSample.
     *  @param numSubSamples    The number of subSamples that make up the
     *                          buffer's contents.
     *  @param key              A 16-byte opaque key
     *  @param iv               A 16-byte initialization vector
     *  @param mode             The type of encryption that has been applied
     *
     *  Either numBytesOfClearData or numBytesOfEncryptedData (but not both)
     *  can be null to indicate that all respective sizes are 0.
     */
    public native final void queueSecureInputBuffer(
            int index,
            int offset,
            int[] numBytesOfClearData,
            int[] numBytesOfEncryptedData,
            int numSubSamples,
            byte[] key,
            byte[] iv,
            int mode,
            long presentationTimeUs,
            int flags);

    // Returns the index of an input buffer to be filled with valid data
    // or -1 if no such buffer is currently available.
    // This method will return immediately if timeoutUs == 0, wait indefinitely
    // for the availability of an input buffer if timeoutUs < 0 or wait up
    // to "timeoutUs" microseconds if timeoutUs > 0.
    public native final int dequeueInputBuffer(long timeoutUs);

    // Returns the index of an output buffer that has been successfully
    // decoded or one of the INFO_* constants below.
    // The provided "info" will be filled with buffer meta data.
    public static final int INFO_TRY_AGAIN_LATER        = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED  = -2;
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    /** Dequeue an output buffer, block at most "timeoutUs" microseconds. */
    public native final int dequeueOutputBuffer(
            BufferInfo info, long timeoutUs);

    // If you are done with a buffer, use this call to return the buffer to
    // the codec. If you previously specified a surface when configuring this
    // video decoder you can optionally render the buffer.
    public native final void releaseOutputBuffer(int index, boolean render);

    /** Call this after dequeueOutputBuffer signals a format change by returning
     *  {@link #INFO_OUTPUT_FORMAT_CHANGED}
     */
    public native final Map<String, Object> getOutputFormat();

    /** Call this after start() returns.
     */
    public ByteBuffer[] getInputBuffers() {
        return getBuffers(true /* input */);
    }

    /** Call this after start() returns and whenever dequeueOutputBuffer
     *  signals an output buffer change by returning
     *  {@link #INFO_OUTPUT_BUFFERS_CHANGED}
     */
    public ByteBuffer[] getOutputBuffers() {
        return getBuffers(false /* input */);
    }

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
