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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.view.Surface;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 MediaCodec class can be used to access low-level media codecs, i.e. encoder/decoder components.
 It is part of the Android low-level multimedia support infrastructure (normally used together
 with {@link MediaExtractor}, {@link MediaSync}, {@link MediaMuxer}, {@link MediaCrypto},
 {@link MediaDrm}, {@link Image}, {@link Surface}, and {@link AudioTrack}.)
 <p>
 <center><object style="width: 540px; height: 205px;" type="image/svg+xml"
   data="../../../images/media/mediacodec_buffers.svg"><img
   src="../../../images/media/mediacodec_buffers.png" style="width: 540px; height: 205px"
   alt="MediaCodec buffer flow diagram"></object></center>
 <p>
 In broad terms, a codec processes input data to generate output data. It processes data
 asynchronously and uses a set of input and output buffers. At a simplistic level, you request
 (or receive) an empty input buffer, fill it up with data and send it to the codec for
 processing. The codec uses up the data and transforms it into one of its empty output buffers.
 Finally, you request (or receive) a filled output buffer, consume its contents and release it
 back to the codec.

 <h3>Data Types</h3>
 <p>
 Codecs operate on three kinds of data: compressed data, raw audio data and raw video data.
 All three kinds of data can be processed using {@link ByteBuffer ByteBuffers}, but you should use
 a {@link Surface} for raw video data to improve codec performance. Surface uses native video
 buffers without mapping or copying them to ByteBuffers; thus, it is much more efficient.
 You normally cannot access the raw video data when using a Surface, but you can use the
 {@link ImageReader} class to access unsecured decoded (raw) video frames. This may still be more
 efficient than using ByteBuffers, as some native buffers may be mapped into {@linkplain
 ByteBuffer#isDirect direct} ByteBuffers. When using ByteBuffer mode, you can access raw video
 frames using the {@link Image} class and {@link #getInputImage getInput}/{@link #getOutputImage
 OutputImage(int)}.

 <h4>Compressed Buffers</h4>
 <p>
 Input buffers (for decoders) and output buffers (for encoders) contain compressed data according
 to the {@linkplain MediaFormat#KEY_MIME format's type}. For video types this is normally a single
 compressed video frame. For audio data this is normally a single access unit (an encoded audio
 segment typically containing a few milliseconds of audio as dictated by the format type), but
 this requirement is slightly relaxed in that a buffer may contain multiple encoded access units
 of audio. In either case, buffers do not start or end on arbitrary byte boundaries, but rather on
 frame/access unit boundaries unless they are flagged with {@link #BUFFER_FLAG_PARTIAL_FRAME}.

 <h4>Raw Audio Buffers</h4>
 <p>
 Raw audio buffers contain entire frames of PCM audio data, which is one sample for each channel
 in channel order. Each sample is a {@linkplain AudioFormat#ENCODING_PCM_16BIT 16-bit signed
 integer in native byte order}.

 <pre class=prettyprint>
 short[] getSamplesForChannel(MediaCodec codec, int bufferId, int channelIx) {
   ByteBuffer outputBuffer = codec.getOutputBuffer(bufferId);
   MediaFormat format = codec.getOutputFormat(bufferId);
   ShortBuffer samples = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
   int numChannels = formet.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
   if (channelIx &lt; 0 || channelIx &gt;= numChannels) {
     return null;
   }
   short[] res = new short[samples.remaining() / numChannels];
   for (int i = 0; i &lt; res.length; ++i) {
     res[i] = samples.get(i * numChannels + channelIx);
   }
   return res;
 }</pre>

 <h4>Raw Video Buffers</h4>
 <p>
 In ByteBuffer mode video buffers are laid out according to their {@linkplain
 MediaFormat#KEY_COLOR_FORMAT color format}. You can get the supported color formats as an array
 from {@link #getCodecInfo}{@code .}{@link MediaCodecInfo#getCapabilitiesForType
 getCapabilitiesForType(&hellip;)}{@code .}{@link CodecCapabilities#colorFormats colorFormats}.
 Video codecs may support three kinds of color formats:
 <ul>
 <li><strong>native raw video format:</strong> This is marked by {@link
 CodecCapabilities#COLOR_FormatSurface} and it can be used with an input or output Surface.</li>
 <li><strong>flexible YUV buffers</strong> (such as {@link
 CodecCapabilities#COLOR_FormatYUV420Flexible}): These can be used with an input/output Surface,
 as well as in ByteBuffer mode, by using {@link #getInputImage getInput}/{@link #getOutputImage
 OutputImage(int)}.</li>
 <li><strong>other, specific formats:</strong> These are normally only supported in ByteBuffer
 mode. Some color formats are vendor specific. Others are defined in {@link CodecCapabilities}.
 For color formats that are equivalent to a flexible format, you can still use {@link
 #getInputImage getInput}/{@link #getOutputImage OutputImage(int)}.</li>
 </ul>
 <p>
 All video codecs support flexible YUV 4:2:0 buffers since {@link
 android.os.Build.VERSION_CODES#LOLLIPOP_MR1}.

 <h4>Accessing Raw Video ByteBuffers on Older Devices</h4>
 <p>
 Prior to {@link android.os.Build.VERSION_CODES#LOLLIPOP} and {@link Image} support, you need to
 use the {@link MediaFormat#KEY_STRIDE} and {@link MediaFormat#KEY_SLICE_HEIGHT} output format
 values to understand the layout of the raw output buffers.
 <p class=note>
 Note that on some devices the slice-height is advertised as 0. This could mean either that the
 slice-height is the same as the frame height, or that the slice-height is the frame height
 aligned to some value (usually a power of 2). Unfortunately, there is no standard and simple way
 to tell the actual slice height in this case. Furthermore, the vertical stride of the {@code U}
 plane in planar formats is also not specified or defined, though usually it is half of the slice
 height.
 <p>
 The {@link MediaFormat#KEY_WIDTH} and {@link MediaFormat#KEY_HEIGHT} keys specify the size of the
 video frames; however, for most encondings the video (picture) only occupies a portion of the
 video frame. This is represented by the 'crop rectangle'.
 <p>
 You need to use the following keys to get the crop rectangle of raw output images from the
 {@linkplain #getOutputFormat output format}. If these keys are not present, the video occupies the
 entire video frame.The crop rectangle is understood in the context of the output frame
 <em>before</em> applying any {@linkplain MediaFormat#KEY_ROTATION rotation}.
 <table style="width: 0%">
  <thead>
   <tr>
    <th>Format Key</th>
    <th>Type</th>
    <th>Description</th>
   </tr>
  </thead>
  <tbody>
   <tr>
    <td>{@code "crop-left"}</td>
    <td>Integer</td>
    <td>The left-coordinate (x) of the crop rectangle</td>
   </tr><tr>
    <td>{@code "crop-top"}</td>
    <td>Integer</td>
    <td>The top-coordinate (y) of the crop rectangle</td>
   </tr><tr>
    <td>{@code "crop-right"}</td>
    <td>Integer</td>
    <td>The right-coordinate (x) <strong>MINUS 1</strong> of the crop rectangle</td>
   </tr><tr>
    <td>{@code "crop-bottom"}</td>
    <td>Integer</td>
    <td>The bottom-coordinate (y) <strong>MINUS 1</strong> of the crop rectangle</td>
   </tr><tr>
    <td colspan=3>
     The right and bottom coordinates can be understood as the coordinates of the right-most
     valid column/bottom-most valid row of the cropped output image.
    </td>
   </tr>
  </tbody>
 </table>
 <p>
 The size of the video frame (before rotation) can be calculated as such:
 <pre class=prettyprint>
 MediaFormat format = decoder.getOutputFormat(&hellip;);
 int width = format.getInteger(MediaFormat.KEY_WIDTH);
 if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
     width = format.getInteger("crop-right") + 1 - format.getInteger("crop-left");
 }
 int height = format.getInteger(MediaFormat.KEY_HEIGHT);
 if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
     height = format.getInteger("crop-bottom") + 1 - format.getInteger("crop-top");
 }
 </pre>
 <p class=note>
 Also note that the meaning of {@link BufferInfo#offset BufferInfo.offset} was not consistent across
 devices. On some devices the offset pointed to the top-left pixel of the crop rectangle, while on
 most devices it pointed to the top-left pixel of the entire frame.

 <h3>States</h3>
 <p>
 During its life a codec conceptually exists in one of three states: Stopped, Executing or
 Released. The Stopped collective state is actually the conglomeration of three states:
 Uninitialized, Configured and Error, whereas the Executing state conceptually progresses through
 three sub-states: Flushed, Running and End-of-Stream.
 <p>
 <center><object style="width: 516px; height: 353px;" type="image/svg+xml"
   data="../../../images/media/mediacodec_states.svg"><img
   src="../../../images/media/mediacodec_states.png" style="width: 519px; height: 356px"
   alt="MediaCodec state diagram"></object></center>
 <p>
 When you create a codec using one of the factory methods, the codec is in the Uninitialized
 state. First, you need to configure it via {@link #configure configure(&hellip;)}, which brings
 it to the Configured state, then call {@link #start} to move it to the Executing state. In this
 state you can process data through the buffer queue manipulation described above.
 <p>
 The Executing state has three sub-states: Flushed, Running and End-of-Stream. Immediately after
 {@link #start} the codec is in the Flushed sub-state, where it holds all the buffers. As soon
 as the first input buffer is dequeued, the codec moves to the Running sub-state, where it spends
 most of its life. When you queue an input buffer with the {@linkplain #BUFFER_FLAG_END_OF_STREAM
 end-of-stream marker}, the codec transitions to the End-of-Stream sub-state. In this state the
 codec no longer accepts further input buffers, but still generates output buffers until the
 end-of-stream is reached on the output. You can move back to the Flushed sub-state at any time
 while in the Executing state using {@link #flush}.
 <p>
 Call {@link #stop} to return the codec to the Uninitialized state, whereupon it may be configured
 again. When you are done using a codec, you must release it by calling {@link #release}.
 <p>
 On rare occasions the codec may encounter an error and move to the Error state. This is
 communicated using an invalid return value from a queuing operation, or sometimes via an
 exception. Call {@link #reset} to make the codec usable again. You can call it from any state to
 move the codec back to the Uninitialized state. Otherwise, call {@link #release} to move to the
 terminal Released state.

 <h3>Creation</h3>
 <p>
 Use {@link MediaCodecList} to create a MediaCodec for a specific {@link MediaFormat}. When
 decoding a file or a stream, you can get the desired format from {@link
 MediaExtractor#getTrackFormat MediaExtractor.getTrackFormat}. Inject any specific features that
 you want to add using {@link MediaFormat#setFeatureEnabled MediaFormat.setFeatureEnabled}, then
 call {@link MediaCodecList#findDecoderForFormat MediaCodecList.findDecoderForFormat} to get the
 name of a codec that can handle that specific media format. Finally, create the codec using
 {@link #createByCodecName}.
 <p class=note>
 <strong>Note:</strong> On {@link android.os.Build.VERSION_CODES#LOLLIPOP}, the format to
 {@code MediaCodecList.findDecoder}/{@code EncoderForFormat} must not contain a {@linkplain
 MediaFormat#KEY_FRAME_RATE frame rate}. Use
 <code class=prettyprint>format.setString(MediaFormat.KEY_FRAME_RATE, null)</code>
 to clear any existing frame rate setting in the format.
 <p>
 You can also create the preferred codec for a specific MIME type using {@link
 #createDecoderByType createDecoder}/{@link #createEncoderByType EncoderByType(String)}.
 This, however, cannot be used to inject features, and may create a codec that cannot handle the
 specific desired media format.

 <h4>Creating secure decoders</h4>
 <p>
 On versions {@link android.os.Build.VERSION_CODES#KITKAT_WATCH} and earlier, secure codecs might
 not be listed in {@link MediaCodecList}, but may still be available on the system. Secure codecs
 that exist can be instantiated by name only, by appending {@code ".secure"} to the name of a
 regular codec (the name of all secure codecs must end in {@code ".secure"}.) {@link
 #createByCodecName} will throw an {@code IOException} if the codec is not present on the system.
 <p>
 From {@link android.os.Build.VERSION_CODES#LOLLIPOP} onwards, you should use the {@link
 CodecCapabilities#FEATURE_SecurePlayback} feature in the media format to create a secure decoder.

 <h3>Initialization</h3>
 <p>
 After creating the codec, you can set a callback using {@link #setCallback setCallback} if you
 want to process data asynchronously. Then, {@linkplain #configure configure} the codec using the
 specific media format. This is when you can specify the output {@link Surface} for video
 producers &ndash; codecs that generate raw video data (e.g. video decoders). This is also when
 you can set the decryption parameters for secure codecs (see {@link MediaCrypto}). Finally, since
 some codecs can operate in multiple modes, you must specify whether you want it to work as a
 decoder or an encoder.
 <p>
 Since {@link android.os.Build.VERSION_CODES#LOLLIPOP}, you can query the resulting input and
 output format in the Configured state. You can use this to verify the resulting configuration,
 e.g. color formats, before starting the codec.
 <p>
 If you want to process raw input video buffers natively with a video consumer &ndash; a codec
 that processes raw video input, such as a video encoder &ndash; create a destination Surface for
 your input data using {@link #createInputSurface} after configuration. Alternately, set up the
 codec to use a previously created {@linkplain #createPersistentInputSurface persistent input
 surface} by calling {@link #setInputSurface}.

 <h4 id=CSD><a name="CSD"></a>Codec-specific Data</h4>
 <p>
 Some formats, notably AAC audio and MPEG4, H.264 and H.265 video formats require the actual data
 to be prefixed by a number of buffers containing setup data, or codec specific data. When
 processing such compressed formats, this data must be submitted to the codec after {@link
 #start} and before any frame data. Such data must be marked using the flag {@link
 #BUFFER_FLAG_CODEC_CONFIG} in a call to {@link #queueInputBuffer queueInputBuffer}.
 <p>
 Codec-specific data can also be included in the format passed to {@link #configure configure} in
 ByteBuffer entries with keys "csd-0", "csd-1", etc. These keys are always included in the track
 {@link MediaFormat} obtained from the {@link MediaExtractor#getTrackFormat MediaExtractor}.
 Codec-specific data in the format is automatically submitted to the codec upon {@link #start};
 you <strong>MUST NOT</strong> submit this data explicitly. If the format did not contain codec
 specific data, you can choose to submit it using the specified number of buffers in the correct
 order, according to the format requirements. In case of H.264 AVC, you can also concatenate all
 codec-specific data and submit it as a single codec-config buffer.
 <p>
 Android uses the following codec-specific data buffers. These are also required to be set in
 the track format for proper {@link MediaMuxer} track configuration. Each parameter set and the
 codec-specific-data sections marked with (<sup>*</sup>) must start with a start code of
 {@code "\x00\x00\x00\x01"}.
 <p>
 <style>td.NA { background: #ccc; } .mid > tr > td { vertical-align: middle; }</style>
 <table>
  <thead>
   <th>Format</th>
   <th>CSD buffer #0</th>
   <th>CSD buffer #1</th>
   <th>CSD buffer #2</th>
  </thead>
  <tbody class=mid>
   <tr>
    <td>AAC</td>
    <td>Decoder-specific information from ESDS<sup>*</sup></td>
    <td class=NA>Not Used</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>VORBIS</td>
    <td>Identification header</td>
    <td>Setup header</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>OPUS</td>
    <td>Identification header</td>
    <td>Pre-skip in nanosecs<br>
        (unsigned 64-bit {@linkplain ByteOrder#nativeOrder native-order} integer.)<br>
        This overrides the pre-skip value in the identification header.</td>
    <td>Seek Pre-roll in nanosecs<br>
        (unsigned 64-bit {@linkplain ByteOrder#nativeOrder native-order} integer.)</td>
   </tr>
   <tr>
    <td>FLAC</td>
    <td>mandatory metadata block (called the STREAMINFO block),<br>
        optionally followed by any number of other metadata blocks</td>
    <td class=NA>Not Used</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>MPEG-4</td>
    <td>Decoder-specific information from ESDS<sup>*</sup></td>
    <td class=NA>Not Used</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>H.264 AVC</td>
    <td>SPS (Sequence Parameter Sets<sup>*</sup>)</td>
    <td>PPS (Picture Parameter Sets<sup>*</sup>)</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>H.265 HEVC</td>
    <td>VPS (Video Parameter Sets<sup>*</sup>) +<br>
     SPS (Sequence Parameter Sets<sup>*</sup>) +<br>
     PPS (Picture Parameter Sets<sup>*</sup>)</td>
    <td class=NA>Not Used</td>
    <td class=NA>Not Used</td>
   </tr>
   <tr>
    <td>VP9</td>
    <td>VP9 <a href="http://wiki.webmproject.org/vp9-codecprivate">CodecPrivate</a> Data
        (optional)</td>
    <td class=NA>Not Used</td>
    <td class=NA>Not Used</td>
   </tr>
  </tbody>
 </table>

 <p class=note>
 <strong>Note:</strong> care must be taken if the codec is flushed immediately or shortly
 after start, before any output buffer or output format change has been returned, as the codec
 specific data may be lost during the flush. You must resubmit the data using buffers marked with
 {@link #BUFFER_FLAG_CODEC_CONFIG} after such flush to ensure proper codec operation.
 <p>
 Encoders (or codecs that generate compressed data) will create and return the codec specific data
 before any valid output buffer in output buffers marked with the {@linkplain
 #BUFFER_FLAG_CODEC_CONFIG codec-config flag}. Buffers containing codec-specific-data have no
 meaningful timestamps.

 <h3>Data Processing</h3>
 <p>
 Each codec maintains a set of input and output buffers that are referred to by a buffer-ID in
 API calls. After a successful call to {@link #start} the client "owns" neither input nor output
 buffers. In synchronous mode, call {@link #dequeueInputBuffer dequeueInput}/{@link
 #dequeueOutputBuffer OutputBuffer(&hellip;)} to obtain (get ownership of) an input or output
 buffer from the codec. In asynchronous mode, you will automatically receive available buffers via
 the {@link Callback#onInputBufferAvailable MediaCodec.Callback.onInput}/{@link
 Callback#onOutputBufferAvailable OutputBufferAvailable(&hellip;)} callbacks.
 <p>
 Upon obtaining an input buffer, fill it with data and submit it to the codec using {@link
 #queueInputBuffer queueInputBuffer} &ndash; or {@link #queueSecureInputBuffer
 queueSecureInputBuffer} if using decryption. Do not submit multiple input buffers with the same
 timestamp (unless it is <a href="#CSD">codec-specific data</a> marked as such).
 <p>
 The codec in turn will return a read-only output buffer via the {@link
 Callback#onOutputBufferAvailable onOutputBufferAvailable} callback in asynchronous mode, or in
 response to a {@link #dequeueOutputBuffer dequeueOutputBuffer} call in synchronous mode. After the
 output buffer has been processed, call one of the {@link #releaseOutputBuffer
 releaseOutputBuffer} methods to return the buffer to the codec.
 <p>
 While you are not required to resubmit/release buffers immediately to the codec, holding onto
 input and/or output buffers may stall the codec, and this behavior is device dependent.
 <strong>Specifically, it is possible that a codec may hold off on generating output buffers until
 <em>all</em> outstanding buffers have been released/resubmitted.</strong> Therefore, try to
 hold onto to available buffers as little as possible.
 <p>
 Depending on the API version, you can process data in three ways:
 <table>
  <thead>
   <tr>
    <th>Processing Mode</th>
    <th>API version <= 20<br>Jelly Bean/KitKat</th>
    <th>API version >= 21<br>Lollipop and later</th>
   </tr>
  </thead>
  <tbody>
   <tr>
    <td>Synchronous API using buffer arrays</td>
    <td>Supported</td>
    <td>Deprecated</td>
   </tr>
   <tr>
    <td>Synchronous API using buffers</td>
    <td class=NA>Not Available</td>
    <td>Supported</td>
   </tr>
   <tr>
    <td>Asynchronous API using buffers</td>
    <td class=NA>Not Available</td>
    <td>Supported</td>
   </tr>
  </tbody>
 </table>

 <h4>Asynchronous Processing using Buffers</h4>
 <p>
 Since {@link android.os.Build.VERSION_CODES#LOLLIPOP}, the preferred method is to process data
 asynchronously by setting a callback before calling {@link #configure configure}. Asynchronous
 mode changes the state transitions slightly, because you must call {@link #start} after {@link
 #flush} to transition the codec to the Running sub-state and start receiving input buffers.
 Similarly, upon an initial call to {@code start} the codec will move directly to the Running
 sub-state and start passing available input buffers via the callback.
 <p>
 <center><object style="width: 516px; height: 353px;" type="image/svg+xml"
   data="../../../images/media/mediacodec_async_states.svg"><img
   src="../../../images/media/mediacodec_async_states.png" style="width: 516px; height: 353px"
   alt="MediaCodec state diagram for asynchronous operation"></object></center>
 <p>
 MediaCodec is typically used like this in asynchronous mode:
 <pre class=prettyprint>
 MediaCodec codec = MediaCodec.createByCodecName(name);
 MediaFormat mOutputFormat; // member variable
 codec.setCallback(new MediaCodec.Callback() {
   {@literal @Override}
   void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
     ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
     // fill inputBuffer with valid data
     &hellip;
     codec.queueInputBuffer(inputBufferId, &hellip;);
   }

   {@literal @Override}
   void onOutputBufferAvailable(MediaCodec mc, int outputBufferId, &hellip;) {
     ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
     MediaFormat bufferFormat = codec.getOutputFormat(outputBufferId); // option A
     // bufferFormat is equivalent to mOutputFormat
     // outputBuffer is ready to be processed or rendered.
     &hellip;
     codec.releaseOutputBuffer(outputBufferId, &hellip;);
   }

   {@literal @Override}
   void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
     // Subsequent data will conform to new format.
     // Can ignore if using getOutputFormat(outputBufferId)
     mOutputFormat = format; // option B
   }

   {@literal @Override}
   void onError(&hellip;) {
     &hellip;
   }
 });
 codec.configure(format, &hellip;);
 mOutputFormat = codec.getOutputFormat(); // option B
 codec.start();
 // wait for processing to complete
 codec.stop();
 codec.release();</pre>

 <h4>Synchronous Processing using Buffers</h4>
 <p>
 Since {@link android.os.Build.VERSION_CODES#LOLLIPOP}, you should retrieve input and output
 buffers using {@link #getInputBuffer getInput}/{@link #getOutputBuffer OutputBuffer(int)} and/or
 {@link #getInputImage getInput}/{@link #getOutputImage OutputImage(int)} even when using the
 codec in synchronous mode. This allows certain optimizations by the framework, e.g. when
 processing dynamic content. This optimization is disabled if you call {@link #getInputBuffers
 getInput}/{@link #getOutputBuffers OutputBuffers()}.

 <p class=note>
 <strong>Note:</strong> do not mix the methods of using buffers and buffer arrays at the same
 time. Specifically, only call {@code getInput}/{@code OutputBuffers} directly after {@link
 #start} or after having dequeued an output buffer ID with the value of {@link
 #INFO_OUTPUT_FORMAT_CHANGED}.
 <p>
 MediaCodec is typically used like this in synchronous mode:
 <pre>
 MediaCodec codec = MediaCodec.createByCodecName(name);
 codec.configure(format, &hellip;);
 MediaFormat outputFormat = codec.getOutputFormat(); // option B
 codec.start();
 for (;;) {
   int inputBufferId = codec.dequeueInputBuffer(timeoutUs);
   if (inputBufferId &gt;= 0) {
     ByteBuffer inputBuffer = codec.getInputBuffer(&hellip;);
     // fill inputBuffer with valid data
     &hellip;
     codec.queueInputBuffer(inputBufferId, &hellip;);
   }
   int outputBufferId = codec.dequeueOutputBuffer(&hellip;);
   if (outputBufferId &gt;= 0) {
     ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
     MediaFormat bufferFormat = codec.getOutputFormat(outputBufferId); // option A
     // bufferFormat is identical to outputFormat
     // outputBuffer is ready to be processed or rendered.
     &hellip;
     codec.releaseOutputBuffer(outputBufferId, &hellip;);
   } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
     // Subsequent data will conform to new format.
     // Can ignore if using getOutputFormat(outputBufferId)
     outputFormat = codec.getOutputFormat(); // option B
   }
 }
 codec.stop();
 codec.release();</pre>

 <h4>Synchronous Processing using Buffer Arrays (deprecated)</h4>
 <p>
 In versions {@link android.os.Build.VERSION_CODES#KITKAT_WATCH} and before, the set of input and
 output buffers are represented by the {@code ByteBuffer[]} arrays. After a successful call to
 {@link #start}, retrieve the buffer arrays using {@link #getInputBuffers getInput}/{@link
 #getOutputBuffers OutputBuffers()}. Use the buffer ID-s as indices into these arrays (when
 non-negative), as demonstrated in the sample below. Note that there is no inherent correlation
 between the size of the arrays and the number of input and output buffers used by the system,
 although the array size provides an upper bound.
 <pre>
 MediaCodec codec = MediaCodec.createByCodecName(name);
 codec.configure(format, &hellip;);
 codec.start();
 ByteBuffer[] inputBuffers = codec.getInputBuffers();
 ByteBuffer[] outputBuffers = codec.getOutputBuffers();
 for (;;) {
   int inputBufferId = codec.dequeueInputBuffer(&hellip;);
   if (inputBufferId &gt;= 0) {
     // fill inputBuffers[inputBufferId] with valid data
     &hellip;
     codec.queueInputBuffer(inputBufferId, &hellip;);
   }
   int outputBufferId = codec.dequeueOutputBuffer(&hellip;);
   if (outputBufferId &gt;= 0) {
     // outputBuffers[outputBufferId] is ready to be processed or rendered.
     &hellip;
     codec.releaseOutputBuffer(outputBufferId, &hellip;);
   } else if (outputBufferId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
     outputBuffers = codec.getOutputBuffers();
   } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
     // Subsequent data will conform to new format.
     MediaFormat format = codec.getOutputFormat();
   }
 }
 codec.stop();
 codec.release();</pre>

 <h4>End-of-stream Handling</h4>
 <p>
 When you reach the end of the input data, you must signal it to the codec by specifying the
 {@link #BUFFER_FLAG_END_OF_STREAM} flag in the call to {@link #queueInputBuffer
 queueInputBuffer}. You can do this on the last valid input buffer, or by submitting an additional
 empty input buffer with the end-of-stream flag set. If using an empty buffer, the timestamp will
 be ignored.
 <p>
 The codec will continue to return output buffers until it eventually signals the end of the
 output stream by specifying the same end-of-stream flag in the {@link BufferInfo} set in {@link
 #dequeueOutputBuffer dequeueOutputBuffer} or returned via {@link Callback#onOutputBufferAvailable
 onOutputBufferAvailable}. This can be set on the last valid output buffer, or on an empty buffer
 after the last valid output buffer. The timestamp of such empty buffer should be ignored.
 <p>
 Do not submit additional input buffers after signaling the end of the input stream, unless the
 codec has been flushed, or stopped and restarted.

 <h4>Using an Output Surface</h4>
 <p>
 The data processing is nearly identical to the ByteBuffer mode when using an output {@link
 Surface}; however, the output buffers will not be accessible, and are represented as {@code null}
 values. E.g. {@link #getOutputBuffer getOutputBuffer}/{@link #getOutputImage Image(int)} will
 return {@code null} and {@link #getOutputBuffers} will return an array containing only {@code
 null}-s.
 <p>
 When using an output Surface, you can select whether or not to render each output buffer on the
 surface. You have three choices:
 <ul>
 <li><strong>Do not render the buffer:</strong> Call {@link #releaseOutputBuffer(int, boolean)
 releaseOutputBuffer(bufferId, false)}.</li>
 <li><strong>Render the buffer with the default timestamp:</strong> Call {@link
 #releaseOutputBuffer(int, boolean) releaseOutputBuffer(bufferId, true)}.</li>
 <li><strong>Render the buffer with a specific timestamp:</strong> Call {@link
 #releaseOutputBuffer(int, long) releaseOutputBuffer(bufferId, timestamp)}.</li>
 </ul>
 <p>
 Since {@link android.os.Build.VERSION_CODES#M}, the default timestamp is the {@linkplain
 BufferInfo#presentationTimeUs presentation timestamp} of the buffer (converted to nanoseconds).
 It was not defined prior to that.
 <p>
 Also since {@link android.os.Build.VERSION_CODES#M}, you can change the output Surface
 dynamically using {@link #setOutputSurface setOutputSurface}.

 <h4>Transformations When Rendering onto Surface</h4>

 If the codec is configured into Surface mode, any crop rectangle, {@linkplain
 MediaFormat#KEY_ROTATION rotation} and {@linkplain #setVideoScalingMode video scaling
 mode} will be automatically applied with one exception:
 <p class=note>
 Prior to the {@link android.os.Build.VERSION_CODES#M} release, software decoders may not
 have applied the rotation when being rendered onto a Surface. Unfortunately, there is no standard
 and simple way to identify software decoders, or if they apply the rotation other than by trying
 it out.
 <p>
 There are also some caveats.
 <p class=note>
 Note that the pixel aspect ratio is not considered when displaying the output onto the
 Surface. This means that if you are using {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT} mode, you
 must position the output Surface so that it has the proper final display aspect ratio. Conversely,
 you can only use {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING} mode for content with
 square pixels (pixel aspect ratio or 1:1).
 <p class=note>
 Note also that as of {@link android.os.Build.VERSION_CODES#N} release, {@link
 #VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING} mode may not work correctly for videos rotated
 by 90 or 270 degrees.
 <p class=note>
 When setting the video scaling mode, note that it must be reset after each time the output
 buffers change. Since the {@link #INFO_OUTPUT_BUFFERS_CHANGED} event is deprecated, you can
 do this after each time the output format changes.

 <h4>Using an Input Surface</h4>
 <p>
 When using an input Surface, there are no accessible input buffers, as buffers are automatically
 passed from the input surface to the codec. Calling {@link #dequeueInputBuffer
 dequeueInputBuffer} will throw an {@code IllegalStateException}, and {@link #getInputBuffers}
 returns a bogus {@code ByteBuffer[]} array that <strong>MUST NOT</strong> be written into.
 <p>
 Call {@link #signalEndOfInputStream} to signal end-of-stream. The input surface will stop
 submitting data to the codec immediately after this call.
 <p>

 <h3>Seeking &amp; Adaptive Playback Support</h3>
 <p>
 Video decoders (and in general codecs that consume compressed video data) behave differently
 regarding seek and format change whether or not they support and are configured for adaptive
 playback. You can check if a decoder supports {@linkplain
 CodecCapabilities#FEATURE_AdaptivePlayback adaptive playback} via {@link
 CodecCapabilities#isFeatureSupported CodecCapabilities.isFeatureSupported(String)}. Adaptive
 playback support for video decoders is only activated if you configure the codec to decode onto a
 {@link Surface}.

 <h4 id=KeyFrames><a name="KeyFrames"></a>Stream Boundary and Key Frames</h4>
 <p>
 It is important that the input data after {@link #start} or {@link #flush} starts at a suitable
 stream boundary: the first frame must a key frame. A <em>key frame</em> can be decoded
 completely on its own (for most codecs this means an I-frame), and no frames that are to be
 displayed after a key frame refer to frames before the key frame.
 <p>
 The following table summarizes suitable key frames for various video formats.
 <table>
  <thead>
   <tr>
    <th>Format</th>
    <th>Suitable key frame</th>
   </tr>
  </thead>
  <tbody class=mid>
   <tr>
    <td>VP9/VP8</td>
    <td>a suitable intraframe where no subsequent frames refer to frames prior to this frame.<br>
      <i>(There is no specific name for such key frame.)</i></td>
   </tr>
   <tr>
    <td>H.265 HEVC</td>
    <td>IDR or CRA</td>
   </tr>
   <tr>
    <td>H.264 AVC</td>
    <td>IDR</td>
   </tr>
   <tr>
    <td>MPEG-4<br>H.263<br>MPEG-2</td>
    <td>a suitable I-frame where no subsequent frames refer to frames prior to this frame.<br>
      <i>(There is no specific name for such key frame.)</td>
   </tr>
  </tbody>
 </table>

 <h4>For decoders that do not support adaptive playback (including when not decoding onto a
 Surface)</h4>
 <p>
 In order to start decoding data that is not adjacent to previously submitted data (i.e. after a
 seek) you <strong>MUST</strong> flush the decoder. Since all output buffers are immediately
 revoked at the point of the flush, you may want to first signal then wait for the end-of-stream
 before you call {@code flush}. It is important that the input data after a flush starts at a
 suitable stream boundary/key frame.
 <p class=note>
 <strong>Note:</strong> the format of the data submitted after a flush must not change; {@link
 #flush} does not support format discontinuities; for that, a full {@link #stop} - {@link
 #configure configure(&hellip;)} - {@link #start} cycle is necessary.

 <p class=note>
 <strong>Also note:</strong> if you flush the codec too soon after {@link #start} &ndash;
 generally, before the first output buffer or output format change is received &ndash; you
 will need to resubmit the codec-specific-data to the codec. See the <a
 href="#CSD">codec-specific-data section</a> for more info.

 <h4>For decoders that support and are configured for adaptive playback</h4>
 <p>
 In order to start decoding data that is not adjacent to previously submitted data (i.e. after a
 seek) it is <em>not necessary</em> to flush the decoder; however, input data after the
 discontinuity must start at a suitable stream boundary/key frame.
 <p>
 For some video formats - namely H.264, H.265, VP8 and VP9 - it is also possible to change the
 picture size or configuration mid-stream. To do this you must package the entire new
 codec-specific configuration data together with the key frame into a single buffer (including
 any start codes), and submit it as a <strong>regular</strong> input buffer.
 <p>
 You will receive an {@link #INFO_OUTPUT_FORMAT_CHANGED} return value from {@link
 #dequeueOutputBuffer dequeueOutputBuffer} or a {@link Callback#onOutputBufferAvailable
 onOutputFormatChanged} callback just after the picture-size change takes place and before any
 frames with the new size have been returned.
 <p class=note>
 <strong>Note:</strong> just as the case for codec-specific data, be careful when calling
 {@link #flush} shortly after you have changed the picture size. If you have not received
 confirmation of the picture size change, you will need to repeat the request for the new picture
 size.

 <h3>Error handling</h3>
 <p>
 The factory methods {@link #createByCodecName createByCodecName} and {@link #createDecoderByType
 createDecoder}/{@link #createEncoderByType EncoderByType} throw {@code IOException} on failure
 which you must catch or declare to pass up. MediaCodec methods throw {@code
 IllegalStateException} when the method is called from a codec state that does not allow it; this
 is typically due to incorrect application API usage. Methods involving secure buffers may throw
 {@link CryptoException}, which has further error information obtainable from {@link
 CryptoException#getErrorCode}.
 <p>
 Internal codec errors result in a {@link CodecException}, which may be due to media content
 corruption, hardware failure, resource exhaustion, and so forth, even when the application is
 correctly using the API. The recommended action when receiving a {@code CodecException}
 can be determined by calling {@link CodecException#isRecoverable} and {@link
 CodecException#isTransient}:
 <ul>
 <li><strong>recoverable errors:</strong> If {@code isRecoverable()} returns true, then call
 {@link #stop}, {@link #configure configure(&hellip;)}, and {@link #start} to recover.</li>
 <li><strong>transient errors:</strong> If {@code isTransient()} returns true, then resources are
 temporarily unavailable and the method may be retried at a later time.</li>
 <li><strong>fatal errors:</strong> If both {@code isRecoverable()} and {@code isTransient()}
 return false, then the {@code CodecException} is fatal and the codec must be {@linkplain #reset
 reset} or {@linkplain #release released}.</li>
 </ul>
 <p>
 Both {@code isRecoverable()} and {@code isTransient()} do not return true at the same time.

 <h2 id=History><a name="History"></a>Valid API Calls and API History</h2>
 <p>
 This sections summarizes the valid API calls in each state and the API history of the MediaCodec
 class. For API version numbers, see {@link android.os.Build.VERSION_CODES}.

 <style>
 .api > tr > th, .api > tr > td { text-align: center; padding: 4px 4px; }
 .api > tr > th     { vertical-align: bottom; }
 .api > tr > td     { vertical-align: middle; }
 .sml > tr > th, .sml > tr > td { text-align: center; padding: 2px 4px; }
 .fn { text-align: left; }
 .fn > code > a { font: 14px/19px Roboto Condensed, sans-serif; }
 .deg45 {
   white-space: nowrap; background: none; border: none; vertical-align: bottom;
   width: 30px; height: 83px;
 }
 .deg45 > div {
   transform: skew(-45deg, 0deg) translate(1px, -67px);
   transform-origin: bottom left 0;
   width: 30px; height: 20px;
 }
 .deg45 > div > div { border: 1px solid #ddd; background: #999; height: 90px; width: 42px; }
 .deg45 > div > div > div { transform: skew(45deg, 0deg) translate(-55px, 55px) rotate(-45deg); }
 </style>

 <table align="right" style="width: 0%">
  <thead>
   <tr><th>Symbol</th><th>Meaning</th></tr>
  </thead>
  <tbody class=sml>
   <tr><td>&#9679;</td><td>Supported</td></tr>
   <tr><td>&#8277;</td><td>Semantics changed</td></tr>
   <tr><td>&#9675;</td><td>Experimental support</td></tr>
   <tr><td>[ ]</td><td>Deprecated</td></tr>
   <tr><td>&#9099;</td><td>Restricted to surface input mode</td></tr>
   <tr><td>&#9094;</td><td>Restricted to surface output mode</td></tr>
   <tr><td>&#9639;</td><td>Restricted to ByteBuffer input mode</td></tr>
   <tr><td>&#8617;</td><td>Restricted to synchronous mode</td></tr>
   <tr><td>&#8644;</td><td>Restricted to asynchronous mode</td></tr>
   <tr><td>( )</td><td>Can be called, but shouldn't</td></tr>
  </tbody>
 </table>

 <table style="width: 100%;">
  <thead class=api>
   <tr>
    <th class=deg45><div><div style="background:#4285f4"><div>Uninitialized</div></div></div></th>
    <th class=deg45><div><div style="background:#f4b400"><div>Configured</div></div></div></th>
    <th class=deg45><div><div style="background:#e67c73"><div>Flushed</div></div></div></th>
    <th class=deg45><div><div style="background:#0f9d58"><div>Running</div></div></div></th>
    <th class=deg45><div><div style="background:#f7cb4d"><div>End of Stream</div></div></div></th>
    <th class=deg45><div><div style="background:#db4437"><div>Error</div></div></div></th>
    <th class=deg45><div><div style="background:#666"><div>Released</div></div></div></th>
    <th></th>
    <th colspan="8">SDK Version</th>
   </tr>
   <tr>
    <th colspan="7">State</th>
    <th>Method</th>
    <th>16</th>
    <th>17</th>
    <th>18</th>
    <th>19</th>
    <th>20</th>
    <th>21</th>
    <th>22</th>
    <th>23</th>
   </tr>
  </thead>
  <tbody class=api>
   <tr>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td class=fn>{@link #createByCodecName createByCodecName}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td class=fn>{@link #createDecoderByType createDecoderByType}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td class=fn>{@link #createEncoderByType createEncoderByType}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td class=fn>{@link #createPersistentInputSurface createPersistentInputSurface}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #configure configure}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>18+</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #createInputSurface createInputSurface}</td>
    <td></td>
    <td></td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>(16+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #dequeueInputBuffer dequeueInputBuffer}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9639;</td>
    <td>&#9639;</td>
    <td>&#9639;</td>
    <td>&#8277;&#9639;&#8617;</td>
    <td>&#9639;&#8617;</td>
    <td>&#9639;&#8617;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #dequeueOutputBuffer dequeueOutputBuffer}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;&#8617;</td>
    <td>&#8617;</td>
    <td>&#8617;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #flush flush}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>-</td>
    <td class=fn>{@link #getCodecInfo getCodecInfo}</td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>(21+)</td>
    <td>21+</td>
    <td>(21+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getInputBuffer getInputBuffer}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>(16+)</td>
    <td>(16+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getInputBuffers getInputBuffers}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>[&#8277;&#8617;]</td>
    <td>[&#8617;]</td>
    <td>[&#8617;]</td>
   </tr>
   <tr>
    <td>-</td>
    <td>21+</td>
    <td>(21+)</td>
    <td>(21+)</td>
    <td>(21+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getInputFormat getInputFormat}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>(21+)</td>
    <td>21+</td>
    <td>(21+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getInputImage getInputImage}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9675;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>18+</td>
    <td>-</td>
    <td class=fn>{@link #getName getName}</td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>(21+)</td>
    <td>21+</td>
    <td>21+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getOutputBuffer getOutputBuffer}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getOutputBuffers getOutputBuffers}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>[&#8277;&#8617;]</td>
    <td>[&#8617;]</td>
    <td>[&#8617;]</td>
   </tr>
   <tr>
    <td>-</td>
    <td>21+</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getOutputFormat()}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>(21+)</td>
    <td>21+</td>
    <td>21+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getOutputFormat(int)}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>(21+)</td>
    <td>21+</td>
    <td>21+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #getOutputImage getOutputImage}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9675;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>(16+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #queueInputBuffer queueInputBuffer}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>(16+)</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #queueSecureInputBuffer queueSecureInputBuffer}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td class=fn>{@link #release release}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #releaseOutputBuffer(int, boolean)}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>21+</td>
    <td>21+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #releaseOutputBuffer(int, long)}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
   </tr>
   <tr>
    <td>21+</td>
    <td>21+</td>
    <td>21+</td>
    <td>21+</td>
    <td>21+</td>
    <td>21+</td>
    <td>-</td>
    <td class=fn>{@link #reset reset}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>21+</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #setCallback(Callback) setCallback}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>{@link #setCallback(Callback, Handler) &#8277;}</td>
   </tr>
   <tr>
    <td>-</td>
    <td>23+</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #setInputSurface setInputSurface}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9099;</td>
   </tr>
   <tr>
    <td>23+</td>
    <td>23+</td>
    <td>23+</td>
    <td>23+</td>
    <td>23+</td>
    <td>(23+)</td>
    <td>(23+)</td>
    <td class=fn>{@link #setOnFrameRenderedListener setOnFrameRenderedListener}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9675; &#9094;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>23+</td>
    <td>23+</td>
    <td>23+</td>
    <td>23+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #setOutputSurface setOutputSurface}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9094;</td>
   </tr>
   <tr>
    <td>19+</td>
    <td>19+</td>
    <td>19+</td>
    <td>19+</td>
    <td>19+</td>
    <td>(19+)</td>
    <td>-</td>
    <td class=fn>{@link #setParameters setParameters}</td>
    <td></td>
    <td></td>
    <td></td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>(16+)</td>
    <td>(16+)</td>
    <td>16+</td>
    <td>(16+)</td>
    <td>(16+)</td>
    <td>-</td>
    <td class=fn>{@link #setVideoScalingMode setVideoScalingMode}</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
    <td>&#9094;</td>
   </tr>
   <tr>
    <td>(29+)</td>
    <td>29+</td>
    <td>29+</td>
    <td>29+</td>
    <td>(29+)</td>
    <td>(29+)</td>
    <td>-</td>
    <td class=fn>{@link #setAudioPresentation setAudioPresentation}</td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>18+</td>
    <td>18+</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #signalEndOfInputStream signalEndOfInputStream}</td>
    <td></td>
    <td></td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
    <td>&#9099;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>16+</td>
    <td>21+(&#8644;)</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #start start}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#8277;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
   <tr>
    <td>-</td>
    <td>-</td>
    <td>16+</td>
    <td>16+</td>
    <td>16+</td>
    <td>-</td>
    <td>-</td>
    <td class=fn>{@link #stop stop}</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
    <td>&#9679;</td>
   </tr>
  </tbody>
 </table>
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
                int newOffset, int newSize, long newTimeUs, @BufferFlag int newFlags) {
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
        @BufferFlag
        public int flags;

        /** @hide */
        @NonNull
        public BufferInfo dup() {
            BufferInfo copy = new BufferInfo();
            copy.set(offset, size, presentationTimeUs, flags);
            return copy;
        }
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

    /**
     * This indicates that the buffer only contains part of a frame,
     * and the decoder should batch the data until a buffer without
     * this flag appears before decoding the frame.
     */
    public static final int BUFFER_FLAG_PARTIAL_FRAME = 8;

    /**
     * This indicates that the buffer contains non-media data for the
     * muxer to process.
     *
     * All muxer data should start with a FOURCC header that determines the type of data.
     *
     * For example, when it contains Exif data sent to a MediaMuxer track of
     * {@link MediaFormat#MIMETYPE_IMAGE_ANDROID_HEIC} type, the data must start with
     * Exif header ("Exif\0\0"), followed by the TIFF header (See JEITA CP-3451C Section 4.5.2.)
     *
     * @hide
     */
    public static final int BUFFER_FLAG_MUXER_DATA = 16;

    /** @hide */
    @IntDef(
        flag = true,
        value = {
            BUFFER_FLAG_SYNC_FRAME,
            BUFFER_FLAG_KEY_FRAME,
            BUFFER_FLAG_CODEC_CONFIG,
            BUFFER_FLAG_END_OF_STREAM,
            BUFFER_FLAG_PARTIAL_FRAME,
            BUFFER_FLAG_MUXER_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BufferFlag {}

    private EventHandler mEventHandler;
    private EventHandler mOnFrameRenderedHandler;
    private EventHandler mCallbackHandler;
    private Callback mCallback;
    private OnFrameRenderedListener mOnFrameRenderedListener;
    private final Object mListenerLock = new Object();
    private MediaCodecInfo mCodecInfo;
    private final Object mCodecInfoLock = new Object();

    private static final int EVENT_CALLBACK = 1;
    private static final int EVENT_SET_CALLBACK = 2;
    private static final int EVENT_FRAME_RENDERED = 3;

    private static final int CB_INPUT_AVAILABLE = 1;
    private static final int CB_OUTPUT_AVAILABLE = 2;
    private static final int CB_ERROR = 3;
    private static final int CB_OUTPUT_FORMAT_CHANGE = 4;

    private class EventHandler extends Handler {
        private MediaCodec mCodec;

        public EventHandler(@NonNull MediaCodec codec, @NonNull Looper looper) {
            super(looper);
            mCodec = codec;
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
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
                case EVENT_FRAME_RENDERED:
                    synchronized (mListenerLock) {
                        Map<String, Object> map = (Map<String, Object>)msg.obj;
                        for (int i = 0; ; ++i) {
                            Object mediaTimeUs = map.get(i + "-media-time-us");
                            Object systemNano = map.get(i + "-system-nano");
                            if (mediaTimeUs == null || systemNano == null
                                    || mOnFrameRenderedListener == null) {
                                break;
                            }
                            mOnFrameRenderedListener.onFrameRendered(
                                    mCodec, (long)mediaTimeUs, (long)systemNano);
                        }
                        break;
                    }
                default:
                {
                    break;
                }
            }
        }

        private void handleCallback(@NonNull Message msg) {
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

    private boolean mHasSurface = false;

    /**
     * Instantiate the preferred decoder supporting input data of the given mime type.
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
     * <strong>Note:</strong> It is preferred to use {@link MediaCodecList#findDecoderForFormat}
     * and {@link #createByCodecName} to ensure that the resulting codec can handle a
     * given format.
     *
     * @param type The mime type of the input data.
     * @throws IOException if the codec cannot be created.
     * @throws IllegalArgumentException if type is not a valid mime type.
     * @throws NullPointerException if type is null.
     */
    @NonNull
    public static MediaCodec createDecoderByType(@NonNull String type)
            throws IOException {
        return new MediaCodec(type, true /* nameIsType */, false /* encoder */);
    }

    /**
     * Instantiate the preferred encoder supporting output data of the given mime type.
     *
     * <strong>Note:</strong> It is preferred to use {@link MediaCodecList#findEncoderForFormat}
     * and {@link #createByCodecName} to ensure that the resulting codec can handle a
     * given format.
     *
     * @param type The desired mime type of the output data.
     * @throws IOException if the codec cannot be created.
     * @throws IllegalArgumentException if type is not a valid mime type.
     * @throws NullPointerException if type is null.
     */
    @NonNull
    public static MediaCodec createEncoderByType(@NonNull String type)
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
    @NonNull
    public static MediaCodec createByCodecName(@NonNull String name)
            throws IOException {
        return new MediaCodec(
                name, false /* nameIsType */, false /* unused */);
    }

    private MediaCodec(
            @NonNull String name, boolean nameIsType, boolean encoder) {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }
        mCallbackHandler = mEventHandler;
        mOnFrameRenderedHandler = mEventHandler;

        mBufferLock = new Object();

        // save name used at creation
        mNameAtCreation = nameIsType ? null : name;

        native_setup(name, nameIsType, encoder);
    }

    private String mNameAtCreation;

    @Override
    protected void finalize() {
        native_finalize();
    }

    /**
     * Returns the codec to its initial (Uninitialized) state.
     *
     * Call this if an {@link MediaCodec.CodecException#isRecoverable unrecoverable}
     * error has occured to reset the codec to its initial state after creation.
     *
     * @throws CodecException if an unrecoverable error has occured and the codec
     * could not be reset.
     * @throws IllegalStateException if in the Released state.
     */
    public final void reset() {
        freeAllTrackedBuffers(); // free buffers first
        native_reset();
    }

    private native final void native_reset();

    /**
     * Free up resources used by the codec instance.
     *
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

    /** @hide */
    @IntDef(flag = true, value = { CONFIGURE_FLAG_ENCODE })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConfigureFlag {}

    /**
     * Configures a component.
     *
     * @param format The format of the input data (decoder) or the desired
     *               format of the output data (encoder). Passing {@code null}
     *               as {@code format} is equivalent to passing an
     *               {@link MediaFormat#MediaFormat an empty mediaformat}.
     * @param surface Specify a surface on which to render the output of this
     *                decoder. Pass {@code null} as {@code surface} if the
     *                codec does not generate raw video output (e.g. not a video
     *                decoder) and/or if you want to configure the codec for
     *                {@link ByteBuffer} output.
     * @param crypto  Specify a crypto object to facilitate secure decryption
     *                of the media data. Pass {@code null} as {@code crypto} for
     *                non-secure codecs.
     * @param flags   Specify {@link #CONFIGURE_FLAG_ENCODE} to configure the
     *                component as an encoder.
     * @throws IllegalArgumentException if the surface has been released (or is invalid),
     * or the format is unacceptable (e.g. missing a mandatory key),
     * or the flags are not set properly
     * (e.g. missing {@link #CONFIGURE_FLAG_ENCODE} for an encoder).
     * @throws IllegalStateException if not in the Uninitialized state.
     * @throws CryptoException upon DRM error.
     * @throws CodecException upon codec error.
     */
    public void configure(
            @Nullable MediaFormat format,
            @Nullable Surface surface, @Nullable MediaCrypto crypto,
            @ConfigureFlag int flags) {
        configure(format, surface, crypto, null, flags);
    }

    /**
     * Configure a component to be used with a descrambler.
     * @param format The format of the input data (decoder) or the desired
     *               format of the output data (encoder). Passing {@code null}
     *               as {@code format} is equivalent to passing an
     *               {@link MediaFormat#MediaFormat an empty mediaformat}.
     * @param surface Specify a surface on which to render the output of this
     *                decoder. Pass {@code null} as {@code surface} if the
     *                codec does not generate raw video output (e.g. not a video
     *                decoder) and/or if you want to configure the codec for
     *                {@link ByteBuffer} output.
     * @param flags   Specify {@link #CONFIGURE_FLAG_ENCODE} to configure the
     *                component as an encoder.
     * @param descrambler Specify a descrambler object to facilitate secure
     *                descrambling of the media data, or null for non-secure codecs.
     * @throws IllegalArgumentException if the surface has been released (or is invalid),
     * or the format is unacceptable (e.g. missing a mandatory key),
     * or the flags are not set properly
     * (e.g. missing {@link #CONFIGURE_FLAG_ENCODE} for an encoder).
     * @throws IllegalStateException if not in the Uninitialized state.
     * @throws CryptoException upon DRM error.
     * @throws CodecException upon codec error.
     */
    public void configure(
            @Nullable MediaFormat format, @Nullable Surface surface,
            @ConfigureFlag int flags, @Nullable MediaDescrambler descrambler) {
        configure(format, surface, null,
                descrambler != null ? descrambler.getBinder() : null, flags);
    }

    private void configure(
            @Nullable MediaFormat format, @Nullable Surface surface,
            @Nullable MediaCrypto crypto, @Nullable IHwBinder descramblerBinder,
            @ConfigureFlag int flags) {
        if (crypto != null && descramblerBinder != null) {
            throw new IllegalArgumentException("Can't use crypto and descrambler together!");
        }

        String[] keys = null;
        Object[] values = null;

        if (format != null) {
            Map<String, Object> formatMap = format.getMap();
            keys = new String[formatMap.size()];
            values = new Object[formatMap.size()];

            int i = 0;
            for (Map.Entry<String, Object> entry: formatMap.entrySet()) {
                if (entry.getKey().equals(MediaFormat.KEY_AUDIO_SESSION_ID)) {
                    int sessionId = 0;
                    try {
                        sessionId = (Integer)entry.getValue();
                    }
                    catch (Exception e) {
                        throw new IllegalArgumentException("Wrong Session ID Parameter!");
                    }
                    keys[i] = "audio-hw-sync";
                    values[i] = AudioSystem.getAudioHwSyncForSession(sessionId);
                } else {
                    keys[i] = entry.getKey();
                    values[i] = entry.getValue();
                }
                ++i;
            }
        }

        mHasSurface = surface != null;

        native_configure(keys, values, surface, crypto, descramblerBinder, flags);
    }

    /**
     *  Dynamically sets the output surface of a codec.
     *  <p>
     *  This can only be used if the codec was configured with an output surface.  The
     *  new output surface should have a compatible usage type to the original output surface.
     *  E.g. codecs may not support switching from a SurfaceTexture (GPU readable) output
     *  to ImageReader (software readable) output.
     *  @param surface the output surface to use. It must not be {@code null}.
     *  @throws IllegalStateException if the codec does not support setting the output
     *            surface in the current state.
     *  @throws IllegalArgumentException if the new surface is not of a suitable type for the codec.
     */
    public void setOutputSurface(@NonNull Surface surface) {
        if (!mHasSurface) {
            throw new IllegalStateException("codec was not configured for an output surface");
        }
        native_setSurface(surface);
    }

    private native void native_setSurface(@NonNull Surface surface);

    /**
     * Create a persistent input surface that can be used with codecs that normally have an input
     * surface, such as video encoders. A persistent input can be reused by subsequent
     * {@link MediaCodec} or {@link MediaRecorder} instances, but can only be used by at
     * most one codec or recorder instance concurrently.
     * <p>
     * The application is responsible for calling release() on the Surface when done.
     *
     * @return an input surface that can be used with {@link #setInputSurface}.
     */
    @NonNull
    public static Surface createPersistentInputSurface() {
        return native_createPersistentInputSurface();
    }

    static class PersistentSurface extends Surface {
        @SuppressWarnings("unused")
        PersistentSurface() {} // used by native

        @Override
        public void release() {
            native_releasePersistentInputSurface(this);
            super.release();
        }

        private long mPersistentObject;
    };

    /**
     * Configures the codec (e.g. encoder) to use a persistent input surface in place of input
     * buffers.  This may only be called after {@link #configure} and before {@link #start}, in
     * lieu of {@link #createInputSurface}.
     * @param surface a persistent input surface created by {@link #createPersistentInputSurface}
     * @throws IllegalStateException if not in the Configured state or does not require an input
     *           surface.
     * @throws IllegalArgumentException if the surface was not created by
     *           {@link #createPersistentInputSurface}.
     */
    public void setInputSurface(@NonNull Surface surface) {
        if (!(surface instanceof PersistentSurface)) {
            throw new IllegalArgumentException("not a PersistentSurface");
        }
        native_setInputSurface(surface);
    }

    @NonNull
    private static native final PersistentSurface native_createPersistentInputSurface();
    private static native final void native_releasePersistentInputSurface(@NonNull Surface surface);
    private native final void native_setInputSurface(@NonNull Surface surface);

    private native final void native_setCallback(@Nullable Callback cb);

    private native final void native_configure(
            @Nullable String[] keys, @Nullable Object[] values,
            @Nullable Surface surface, @Nullable MediaCrypto crypto,
            @Nullable IHwBinder descramblerBinder, @ConfigureFlag int flags);

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
    @NonNull
    public native final Surface createInputSurface();

    /**
     * After successfully configuring the component, call {@code start}.
     * <p>
     * Call {@code start} also if the codec is configured in asynchronous mode,
     * and it has just been flushed, to resume requesting input buffers.
     * @throws IllegalStateException if not in the Configured state
     *         or just after {@link #flush} for a codec that is configured
     *         in asynchronous mode.
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
     * @throws IllegalStateException if in the Released state.
     */
    public final void stop() {
        native_stop();
        freeAllTrackedBuffers();

        synchronized (mListenerLock) {
            if (mCallbackHandler != null) {
                mCallbackHandler.removeMessages(EVENT_SET_CALLBACK);
                mCallbackHandler.removeMessages(EVENT_CALLBACK);
            }
            if (mOnFrameRenderedHandler != null) {
                mOnFrameRenderedHandler.removeMessages(EVENT_FRAME_RENDERED);
            }
        }
    }

    private native final void native_stop();

    /**
     * Flush both input and output ports of the component.
     * <p>
     * Upon return, all indices previously returned in calls to {@link #dequeueInputBuffer
     * dequeueInputBuffer} and {@link #dequeueOutputBuffer dequeueOutputBuffer} &mdash; or obtained
     * via {@link Callback#onInputBufferAvailable onInputBufferAvailable} or
     * {@link Callback#onOutputBufferAvailable onOutputBufferAvailable} callbacks &mdash; become
     * invalid, and all buffers are owned by the codec.
     * <p>
     * If the codec is configured in asynchronous mode, call {@link #start}
     * after {@code flush} has returned to resume codec operations. The codec
     * will not request input buffers until this has happened.
     * <strong>Note, however, that there may still be outstanding {@code onOutputBufferAvailable}
     * callbacks that were not handled prior to calling {@code flush}.
     * The indices returned via these callbacks also become invalid upon calling {@code flush} and
     * should be discarded.</strong>
     * <p>
     * If the codec is configured in synchronous mode, codec will resume
     * automatically if it is configured with an input surface.  Otherwise, it
     * will resume when {@link #dequeueInputBuffer dequeueInputBuffer} is called.
     *
     * @throws IllegalStateException if not in the Executing state.
     * @throws MediaCodec.CodecException upon codec error.
     */
    public final void flush() {
        synchronized(mBufferLock) {
            invalidateByteBuffers(mCachedInputBuffers);
            invalidateByteBuffers(mCachedOutputBuffers);
            mDequeuedInputBuffers.clear();
            mDequeuedOutputBuffers.clear();
        }
        native_flush();
    }

    private native final void native_flush();

    /**
     * Thrown when an internal codec error occurs.
     */
    public final static class CodecException extends IllegalStateException {
        @UnsupportedAppUsage
        CodecException(int errorCode, int actionCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
            mActionCode = actionCode;

            // TODO get this from codec
            final String sign = errorCode < 0 ? "neg_" : "";
            mDiagnosticInfo =
                "android.media.MediaCodec.error_" + sign + Math.abs(errorCode);
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
         * Retrieve the error code associated with a CodecException
         */
        public int getErrorCode() {
            return mErrorCode;
        }

        /**
         * Retrieve a developer-readable diagnostic information string
         * associated with the exception. Do not show this to end-users,
         * since this string will not be localized or generally
         * comprehensible to end-users.
         */
        public @NonNull String getDiagnosticInfo() {
            return mDiagnosticInfo;
        }

        /**
         * This indicates required resource was not able to be allocated.
         */
        public static final int ERROR_INSUFFICIENT_RESOURCE = 1100;

        /**
         * This indicates the resource manager reclaimed the media resource used by the codec.
         * <p>
         * With this exception, the codec must be released, as it has moved to terminal state.
         */
        public static final int ERROR_RECLAIMED = 1101;

        /** @hide */
        @IntDef({
            ERROR_INSUFFICIENT_RESOURCE,
            ERROR_RECLAIMED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ReasonCode {}

        /* Must be in sync with android_media_MediaCodec.cpp */
        private final static int ACTION_TRANSIENT = 1;
        private final static int ACTION_RECOVERABLE = 2;

        private final String mDiagnosticInfo;
        private final int mErrorCode;
        private final int mActionCode;
    }

    /**
     * Thrown when a crypto error occurs while queueing a secure input buffer.
     */
    public final static class CryptoException extends RuntimeException {
        public CryptoException(int errorCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
        }

        /**
         * This indicates that the requested key was not found when trying to
         * perform a decrypt operation.  The operation can be retried after adding
         * the correct decryption key.
         */
        public static final int ERROR_NO_KEY = 1;

        /**
         * This indicates that the key used for decryption is no longer
         * valid due to license term expiration.  The operation can be retried
         * after updating the expired keys.
         */
        public static final int ERROR_KEY_EXPIRED = 2;

        /**
         * This indicates that a required crypto resource was not able to be
         * allocated while attempting the requested operation.  The operation
         * can be retried if the app is able to release resources.
         */
        public static final int ERROR_RESOURCE_BUSY = 3;

        /**
         * This indicates that the output protection levels supported by the
         * device are not sufficient to meet the requirements set by the
         * content owner in the license policy.
         */
        public static final int ERROR_INSUFFICIENT_OUTPUT_PROTECTION = 4;

        /**
         * This indicates that decryption was attempted on a session that is
         * not opened, which could be due to a failure to open the session,
         * closing the session prematurely, or the session being reclaimed
         * by the resource manager.
         */
        public static final int ERROR_SESSION_NOT_OPENED = 5;

        /**
         * This indicates that an operation was attempted that could not be
         * supported by the crypto system of the device in its current
         * configuration.  It may occur when the license policy requires
         * device security features that aren't supported by the device,
         * or due to an internal error in the crypto system that prevents
         * the specified security policy from being met.
         */
        public static final int ERROR_UNSUPPORTED_OPERATION = 6;

        /**
         * This indicates that the security level of the device is not
         * sufficient to meet the requirements set by the content owner
         * in the license policy.
         */
        public static final int ERROR_INSUFFICIENT_SECURITY = 7;

        /**
         * This indicates that the video frame being decrypted exceeds
         * the size of the device's protected output buffers. When
         * encountering this error the app should try playing content
         * of a lower resolution.
         */
        public static final int ERROR_FRAME_TOO_LARGE = 8;

        /**
         * This error indicates that session state has been
         * invalidated. It can occur on devices that are not capable
         * of retaining crypto session state across device
         * suspend/resume. The session must be closed and a new
         * session opened to resume operation.
         */
        public static final int ERROR_LOST_STATE = 9;

        /** @hide */
        @IntDef({
            ERROR_NO_KEY,
            ERROR_KEY_EXPIRED,
            ERROR_RESOURCE_BUSY,
            ERROR_INSUFFICIENT_OUTPUT_PROTECTION,
            ERROR_SESSION_NOT_OPENED,
            ERROR_UNSUPPORTED_OPERATION,
            ERROR_INSUFFICIENT_SECURITY,
            ERROR_FRAME_TOO_LARGE,
            ERROR_LOST_STATE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface CryptoErrorCode {}

        /**
         * Retrieve the error code associated with a CryptoException
         */
        @CryptoErrorCode
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
     * <p class=note>
     * <strong>Note:</strong> Prior to {@link android.os.Build.VERSION_CODES#M},
     * {@code presentationTimeUs} was not propagated to the frame timestamp of (rendered)
     * Surface output buffers, and the resulting frame timestamp was undefined.
     * Use {@link #releaseOutputBuffer(int, long)} to ensure a specific frame timestamp is set.
     * Similarly, since frame timestamps can be used by the destination surface for rendering
     * synchronization, <strong>care must be taken to normalize presentationTimeUs so as to not be
     * mistaken for a system time. (See {@linkplain #releaseOutputBuffer(int, long)
     * SurfaceView specifics}).</strong>
     *
     * @param index The index of a client-owned input buffer previously returned
     *              in a call to {@link #dequeueInputBuffer}.
     * @param offset The byte offset into the input buffer at which the data starts.
     * @param size The number of bytes of valid input data.
     * @param presentationTimeUs The presentation timestamp in microseconds for this
     *                           buffer. This is normally the media time at which this
     *                           buffer should be presented (rendered). When using an output
     *                           surface, this will be propagated as the {@link
     *                           SurfaceTexture#getTimestamp timestamp} for the frame (after
     *                           conversion to nanoseconds).
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
            mDequeuedInputBuffers.remove(index);
        }
        try {
            native_queueInputBuffer(
                    index, offset, size, presentationTimeUs, flags);
        } catch (CryptoException | IllegalStateException e) {
            revalidateByteBuffer(mCachedInputBuffers, index);
            throw e;
        }
    }

    private native final void native_queueInputBuffer(
            int index,
            int offset, int size, long presentationTimeUs, int flags)
        throws CryptoException;

    public static final int CRYPTO_MODE_UNENCRYPTED = 0;
    public static final int CRYPTO_MODE_AES_CTR     = 1;
    public static final int CRYPTO_MODE_AES_CBC     = 2;

    /**
     * Metadata describing the structure of an encrypted input sample.
     * <p>
     * A buffer's data is considered to be partitioned into "subSamples". Each subSample starts with
     * a run of plain, unencrypted bytes followed by a run of encrypted bytes. Either of these runs
     * may be empty. If pattern encryption applies, each of the encrypted runs is encrypted only
     * partly, according to a repeating pattern of "encrypt" and "skip" blocks.
     * {@link #numBytesOfClearData} can be null to indicate that all data is encrypted, and
     * {@link #numBytesOfEncryptedData} can be null to indicate that all data is clear. At least one
     * of {@link #numBytesOfClearData} and {@link #numBytesOfEncryptedData} must be non-null.
     * <p>
     * This information encapsulates per-sample metadata as outlined in ISO/IEC FDIS 23001-7:2016
     * "Common encryption in ISO base media file format files".
     * <p>
     * <h3>ISO-CENC Schemes</h3>
     * ISO/IEC FDIS 23001-7:2016 defines four possible schemes by which media may be encrypted,
     * corresponding to each possible combination of an AES mode with the presence or absence of
     * patterned encryption.
     *
     * <table style="width: 0%">
     *   <thead>
     *     <tr>
     *       <th>&nbsp;</th>
     *       <th>AES-CTR</th>
     *       <th>AES-CBC</th>
     *     </tr>
     *   </thead>
     *   <tbody>
     *     <tr>
     *       <th>Without Patterns</th>
     *       <td>cenc</td>
     *       <td>cbc1</td>
     *     </tr><tr>
     *       <th>With Patterns</th>
     *       <td>cens</td>
     *       <td>cbcs</td>
     *     </tr>
     *   </tbody>
     * </table>
     *
     * For {@code CryptoInfo}, the scheme is selected implicitly by the combination of the
     * {@link #mode} field and the value set with {@link #setPattern}. For the pattern, setting the
     * pattern to all zeroes (that is, both {@code blocksToEncrypt} and {@code blocksToSkip} are
     * zero) is interpreted as turning patterns off completely. A scheme that does not use patterns
     * will be selected, either cenc or cbc1. Setting the pattern to any nonzero value will choose
     * one of the pattern-supporting schemes, cens or cbcs. The default pattern if
     * {@link #setPattern} is never called is all zeroes.
     * <p>
     * <h4>HLS SAMPLE-AES Audio</h4>
     * HLS SAMPLE-AES audio is encrypted in a manner compatible with the cbcs scheme, except that it
     * does not use patterned encryption. However, if {@link #setPattern} is used to set the pattern
     * to all zeroes, this will be interpreted as selecting the cbc1 scheme. The cbc1 scheme cannot
     * successfully decrypt HLS SAMPLE-AES audio because of differences in how the IVs are handled.
     * For this reason, it is recommended that a pattern of {@code 1} encrypted block and {@code 0}
     * skip blocks be used with HLS SAMPLE-AES audio. This will trigger decryption to use cbcs mode
     * while still decrypting every block.
     */
    public final static class CryptoInfo {
        /**
         * The number of subSamples that make up the buffer's contents.
         */
        public int numSubSamples;
        /**
         * The number of leading unencrypted bytes in each subSample. If null, all bytes are treated
         * as encrypted and {@link #numBytesOfEncryptedData} must be specified.
         */
        public int[] numBytesOfClearData;
        /**
         * The number of trailing encrypted bytes in each subSample. If null, all bytes are treated
         * as clear and {@link #numBytesOfClearData} must be specified.
         */
        public int[] numBytesOfEncryptedData;
        /**
         * A 16-byte key id
         */
        public byte[] key;
        /**
         * A 16-byte initialization vector
         */
        public byte[] iv;
        /**
         * The type of encryption that has been applied,
         * see {@link #CRYPTO_MODE_UNENCRYPTED}, {@link #CRYPTO_MODE_AES_CTR}
         * and {@link #CRYPTO_MODE_AES_CBC}
         */
        public int mode;

        /**
         * Metadata describing an encryption pattern for the protected bytes in a subsample.  An
         * encryption pattern consists of a repeating sequence of crypto blocks comprised of a
         * number of encrypted blocks followed by a number of unencrypted, or skipped, blocks.
         */
        public final static class Pattern {
            /**
             * Number of blocks to be encrypted in the pattern. If both this and
             * {@link #mSkipBlocks} are zero, pattern encryption is inoperative.
             */
            private int mEncryptBlocks;

            /**
             * Number of blocks to be skipped (left clear) in the pattern. If both this and
             * {@link #mEncryptBlocks} are zero, pattern encryption is inoperative.
             */
            private int mSkipBlocks;

            /**
             * Construct a sample encryption pattern given the number of blocks to encrypt and skip
             * in the pattern. If both parameters are zero, pattern encryption is inoperative.
             */
            public Pattern(int blocksToEncrypt, int blocksToSkip) {
                set(blocksToEncrypt, blocksToSkip);
            }

            /**
             * Set the number of blocks to encrypt and skip in a sample encryption pattern. If both
             * parameters are zero, pattern encryption is inoperative.
             */
            public void set(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }

            /**
             * Return the number of blocks to skip in a sample encryption pattern.
             */
            public int getSkipBlocks() {
                return mSkipBlocks;
            }

            /**
             * Return the number of blocks to encrypt in a sample encryption pattern.
             */
            public int getEncryptBlocks() {
                return mEncryptBlocks;
            }
        };

        private final Pattern zeroPattern = new Pattern(0, 0);

        /**
         * The pattern applicable to the protected data in each subsample.
         */
        private Pattern pattern;

        /**
         * Set the subsample count, clear/encrypted sizes, key, IV and mode fields of
         * a {@link MediaCodec.CryptoInfo} instance.
         */
        public void set(
                int newNumSubSamples,
                @NonNull int[] newNumBytesOfClearData,
                @NonNull int[] newNumBytesOfEncryptedData,
                @NonNull byte[] newKey,
                @NonNull byte[] newIV,
                int newMode) {
            numSubSamples = newNumSubSamples;
            numBytesOfClearData = newNumBytesOfClearData;
            numBytesOfEncryptedData = newNumBytesOfEncryptedData;
            key = newKey;
            iv = newIV;
            mode = newMode;
            pattern = zeroPattern;
        }

        /**
         * Set the encryption pattern on a {@link MediaCodec.CryptoInfo} instance.
         * See {@link MediaCodec.CryptoInfo.Pattern}.
         */
        public void setPattern(Pattern newPattern) {
            pattern = newPattern;
        }

        private void setPattern(int blocksToEncrypt, int blocksToSkip) {
            pattern = new Pattern(blocksToEncrypt, blocksToSkip);
        }

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
     * Similar to {@link #queueInputBuffer queueInputBuffer} but submits a buffer that is
     * potentially encrypted.
     * <strong>Check out further notes at {@link #queueInputBuffer queueInputBuffer}.</strong>
     *
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
            @NonNull CryptoInfo info,
            long presentationTimeUs,
            int flags) throws CryptoException {
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedInputBuffers, index);
            mDequeuedInputBuffers.remove(index);
        }
        try {
            native_queueSecureInputBuffer(
                    index, offset, info, presentationTimeUs, flags);
        } catch (CryptoException | IllegalStateException e) {
            revalidateByteBuffer(mCachedInputBuffers, index);
            throw e;
        }
    }

    private native final void native_queueSecureInputBuffer(
            int index,
            int offset,
            @NonNull CryptoInfo info,
            long presentationTimeUs,
            int flags) throws CryptoException;

    /**
     * Returns the index of an input buffer to be filled with valid data
     * or -1 if no such buffer is currently available.
     * This method will return immediately if timeoutUs == 0, wait indefinitely
     * for the availability of an input buffer if timeoutUs &lt; 0 or wait up
     * to "timeoutUs" microseconds if timeoutUs &gt; 0.
     * @param timeoutUs The timeout in microseconds, a negative timeout indicates "infinite".
     * @throws IllegalStateException if not in the Executing state,
     *         or codec is configured in asynchronous mode.
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
     * <p>Additionally, this event signals that the video scaling mode
     * may have been reset to the default.</p>
     *
     * @deprecated This return value can be ignored as {@link
     * #getOutputBuffers} has been deprecated.  Client should
     * request a current buffer using on of the get-buffer or
     * get-image methods each time one has been dequeued.
     */
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    /** @hide */
    @IntDef({
        INFO_TRY_AGAIN_LATER,
        INFO_OUTPUT_FORMAT_CHANGED,
        INFO_OUTPUT_BUFFERS_CHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OutputBufferInfo {}

    /**
     * Dequeue an output buffer, block at most "timeoutUs" microseconds.
     * Returns the index of an output buffer that has been successfully
     * decoded or one of the INFO_* constants.
     * @param info Will be filled with buffer meta data.
     * @param timeoutUs The timeout in microseconds, a negative timeout indicates "infinite".
     * @throws IllegalStateException if not in the Executing state,
     *         or codec is configured in asynchronous mode.
     * @throws MediaCodec.CodecException upon codec error.
     */
    @OutputBufferInfo
    public final int dequeueOutputBuffer(
            @NonNull BufferInfo info, long timeoutUs) {
        int res = native_dequeueOutputBuffer(info, timeoutUs);
        synchronized(mBufferLock) {
            if (res == INFO_OUTPUT_BUFFERS_CHANGED) {
                cacheBuffers(false /* input */);
            } else if (res >= 0) {
                validateOutputByteBuffer(mCachedOutputBuffers, res, info);
                if (mHasSurface) {
                    mDequeuedOutputInfos.put(res, info.dup());
                }
            }
        }
        return res;
    }

    private native final int native_dequeueOutputBuffer(
            @NonNull BufferInfo info, long timeoutUs);

    /**
     * If you are done with a buffer, use this call to return the buffer to the codec
     * or to render it on the output surface. If you configured the codec with an
     * output surface, setting {@code render} to {@code true} will first send the buffer
     * to that output surface. The surface will release the buffer back to the codec once
     * it is no longer used/displayed.
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
        BufferInfo info = null;
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            mDequeuedOutputBuffers.remove(index);
            if (mHasSurface) {
                info = mDequeuedOutputInfos.remove(index);
            }
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
        BufferInfo info = null;
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            mDequeuedOutputBuffers.remove(index);
            if (mHasSurface) {
                info = mDequeuedOutputInfos.remove(index);
            }
        }
        releaseOutputBuffer(
                index, true /* render */, true /* updatePTS */, renderTimestampNs);
    }

    @UnsupportedAppUsage
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
    @NonNull
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
    @NonNull
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
    @NonNull
    public final MediaFormat getOutputFormat(int index) {
        return new MediaFormat(getOutputFormatNative(index));
    }

    @NonNull
    private native final Map<String, Object> getFormatNative(boolean input);

    @NonNull
    private native final Map<String, Object> getOutputFormatNative(int index);

    // used to track dequeued buffers
    private static class BufferMap {
        // various returned representations of the codec buffer
        private static class CodecBuffer {
            private Image mImage;
            private ByteBuffer mByteBuffer;

            public void free() {
                if (mByteBuffer != null) {
                    // all of our ByteBuffers are direct
                    java.nio.NioUtils.freeDirectBuffer(mByteBuffer);
                    mByteBuffer = null;
                }
                if (mImage != null) {
                    mImage.close();
                    mImage = null;
                }
            }

            public void setImage(@Nullable Image image) {
                free();
                mImage = image;
            }

            public void setByteBuffer(@Nullable ByteBuffer buffer) {
                free();
                mByteBuffer = buffer;
            }
        }

        private final Map<Integer, CodecBuffer> mMap =
            new HashMap<Integer, CodecBuffer>();

        public void remove(int index) {
            CodecBuffer buffer = mMap.get(index);
            if (buffer != null) {
                buffer.free();
                mMap.remove(index);
            }
        }

        public void put(int index, @Nullable ByteBuffer newBuffer) {
            CodecBuffer buffer = mMap.get(index);
            if (buffer == null) { // likely
                buffer = new CodecBuffer();
                mMap.put(index, buffer);
            }
            buffer.setByteBuffer(newBuffer);
        }

        public void put(int index, @Nullable Image newImage) {
            CodecBuffer buffer = mMap.get(index);
            if (buffer == null) { // likely
                buffer = new CodecBuffer();
                mMap.put(index, buffer);
            }
            buffer.setImage(newImage);
        }

        public void clear() {
            for (CodecBuffer buffer: mMap.values()) {
                buffer.free();
            }
            mMap.clear();
        }
    }

    private ByteBuffer[] mCachedInputBuffers;
    private ByteBuffer[] mCachedOutputBuffers;
    private final BufferMap mDequeuedInputBuffers = new BufferMap();
    private final BufferMap mDequeuedOutputBuffers = new BufferMap();
    private final Map<Integer, BufferInfo> mDequeuedOutputInfos =
        new HashMap<Integer, BufferInfo>();
    final private Object mBufferLock;

    private final void invalidateByteBuffer(
            @Nullable ByteBuffer[] buffers, int index) {
        if (buffers != null && index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(false);
            }
        }
    }

    private final void validateInputByteBuffer(
            @Nullable ByteBuffer[] buffers, int index) {
        if (buffers != null && index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(true);
                buffer.clear();
            }
        }
    }

    private final void revalidateByteBuffer(
            @Nullable ByteBuffer[] buffers, int index) {
        synchronized(mBufferLock) {
            if (buffers != null && index >= 0 && index < buffers.length) {
                ByteBuffer buffer = buffers[index];
                if (buffer != null) {
                    buffer.setAccessible(true);
                }
            }
        }
    }

    private final void validateOutputByteBuffer(
            @Nullable ByteBuffer[] buffers, int index, @NonNull BufferInfo info) {
        if (buffers != null && index >= 0 && index < buffers.length) {
            ByteBuffer buffer = buffers[index];
            if (buffer != null) {
                buffer.setAccessible(true);
                buffer.limit(info.offset + info.size).position(info.offset);
            }
        }
    }

    private final void invalidateByteBuffers(@Nullable ByteBuffer[] buffers) {
        if (buffers != null) {
            for (ByteBuffer buffer: buffers) {
                if (buffer != null) {
                    buffer.setAccessible(false);
                }
            }
        }
    }

    private final void freeByteBuffer(@Nullable ByteBuffer buffer) {
        if (buffer != null /* && buffer.isDirect() */) {
            // all of our ByteBuffers are direct
            java.nio.NioUtils.freeDirectBuffer(buffer);
        }
    }

    private final void freeByteBuffers(@Nullable ByteBuffer[] buffers) {
        if (buffers != null) {
            for (ByteBuffer buffer: buffers) {
                freeByteBuffer(buffer);
            }
        }
    }

    private final void freeAllTrackedBuffers() {
        synchronized(mBufferLock) {
            freeByteBuffers(mCachedInputBuffers);
            freeByteBuffers(mCachedOutputBuffers);
            mCachedInputBuffers = null;
            mCachedOutputBuffers = null;
            mDequeuedInputBuffers.clear();
            mDequeuedOutputBuffers.clear();
        }
    }

    private final void cacheBuffers(boolean input) {
        ByteBuffer[] buffers = null;
        try {
            buffers = getBuffers(input);
            invalidateByteBuffers(buffers);
        } catch (IllegalStateException e) {
            // we don't get buffers in async mode
        }
        if (input) {
            mCachedInputBuffers = buffers;
        } else {
            mCachedOutputBuffers = buffers;
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
     * <b>Note:</b> As of API 21, dequeued input buffers are
     * automatically {@link java.nio.Buffer#clear cleared}.
     *
     * <em>Do not use this method if using an input surface.</em>
     *
     * @throws IllegalStateException if not in the Executing state,
     *         or codec is configured in asynchronous mode.
     * @throws MediaCodec.CodecException upon codec error.
     */
    @NonNull
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
     * <b>Note:</b> As of API 21, the position and limit of output
     * buffers that are dequeued will be set to the valid data
     * range.
     *
     * <em>Do not use this method if using an output surface.</em>
     *
     * @throws IllegalStateException if not in the Executing state,
     *         or codec is configured in asynchronous mode.
     * @throws MediaCodec.CodecException upon codec error.
     */
    @NonNull
    public ByteBuffer[] getOutputBuffers() {
        if (mCachedOutputBuffers == null) {
            throw new IllegalStateException();
        }
        // FIXME: check codec status
        return mCachedOutputBuffers;
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
    @Nullable
    public ByteBuffer getInputBuffer(int index) {
        ByteBuffer newBuffer = getBuffer(true /* input */, index);
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedInputBuffers, index);
            mDequeuedInputBuffers.put(index, newBuffer);
        }
        return newBuffer;
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
    @Nullable
    public Image getInputImage(int index) {
        Image newImage = getImage(true /* input */, index);
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedInputBuffers, index);
            mDequeuedInputBuffers.put(index, newImage);
        }
        return newImage;
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
    @Nullable
    public ByteBuffer getOutputBuffer(int index) {
        ByteBuffer newBuffer = getBuffer(false /* input */, index);
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            mDequeuedOutputBuffers.put(index, newBuffer);
        }
        return newBuffer;
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
    @Nullable
    public Image getOutputImage(int index) {
        Image newImage = getImage(false /* input */, index);
        synchronized(mBufferLock) {
            invalidateByteBuffer(mCachedOutputBuffers, index);
            mDequeuedOutputBuffers.put(index, newImage);
        }
        return newImage;
    }

    /**
     * The content is scaled to the surface dimensions
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT               = 1;

    /**
     * The content is scaled, maintaining its aspect ratio, the whole
     * surface area is used, content may be cropped.
     * <p class=note>
     * This mode is only suitable for content with 1:1 pixel aspect ratio as you cannot
     * configure the pixel aspect ratio for a {@link Surface}.
     * <p class=note>
     * As of {@link android.os.Build.VERSION_CODES#N} release, this mode may not work if
     * the video is {@linkplain MediaFormat#KEY_ROTATION rotated} by 90 or 270 degrees.
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;

    /** @hide */
    @IntDef({
        VIDEO_SCALING_MODE_SCALE_TO_FIT,
        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VideoScalingMode {}

    /**
     * If a surface has been specified in a previous call to {@link #configure}
     * specifies the scaling mode to use. The default is "scale to fit".
     * <p class=note>
     * The scaling mode may be reset to the <strong>default</strong> each time an
     * {@link #INFO_OUTPUT_BUFFERS_CHANGED} event is received from the codec; therefore, the client
     * must call this method after every buffer change event (and before the first output buffer is
     * released for rendering) to ensure consistent scaling mode.
     * <p class=note>
     * Since the {@link #INFO_OUTPUT_BUFFERS_CHANGED} event is deprecated, this can also be done
     * after each {@link #INFO_OUTPUT_FORMAT_CHANGED} event.
     *
     * @throws IllegalArgumentException if mode is not recognized.
     * @throws IllegalStateException if in the Released state.
     */
    public native final void setVideoScalingMode(@VideoScalingMode int mode);

    /**
     * Sets the audio presentation.
     * @param presentation see {@link AudioPresentation}. In particular, id should be set.
     */
    public void setAudioPresentation(@NonNull AudioPresentation presentation) {
        if (presentation == null) {
            throw new NullPointerException("audio presentation is null");
        }
        native_setAudioPresentation(presentation.getPresentationId(), presentation.getProgramId());
    }

    private native void native_setAudioPresentation(int presentationId, int programId);

    /**
     * Retrieve the codec name.
     *
     * If the codec was created by createDecoderByType or createEncoderByType, what component is
     * chosen is not known beforehand. This method returns the name of the codec that was
     * selected by the platform.
     *
     * <strong>Note:</strong> Implementations may provide multiple aliases (codec
     * names) for the same underlying codec, any of which can be used to instantiate the same
     * underlying codec in {@link MediaCodec#createByCodecName}. This method returns the
     * name used to create the codec in this case.
     *
     * @throws IllegalStateException if in the Released state.
     */
    @NonNull
    public final String getName() {
        // get canonical name to handle exception
        String canonicalName = getCanonicalName();
        return mNameAtCreation != null ? mNameAtCreation : canonicalName;
    }

    /**
     * Retrieve the underlying codec name.
     *
     * This method is similar to {@link #getName}, except that it returns the underlying component
     * name even if an alias was used to create this MediaCodec object by name,
     *
     * @throws IllegalStateException if in the Released state.
     */
    @NonNull
    public native final String getCanonicalName();

    /**
     *  Return Metrics data about the current codec instance.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being handled by this instance of MediaCodec
     * The attributes are descibed in {@link MetricsConstants}.
     *
     * Additional vendor-specific fields may also be present in
     * the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();

    /**
     * Change a video encoder's target bitrate on the fly. The value is an
     * Integer object containing the new bitrate in bps.
     *
     * @see #setParameters(Bundle)
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
     *
     * @see #setParameters(Bundle)
     */
    public static final String PARAMETER_KEY_SUSPEND = "drop-input-frames";

    /**
     * When {@link #PARAMETER_KEY_SUSPEND} is present, the client can also
     * optionally use this key to specify the timestamp (in micro-second)
     * at which the suspend/resume operation takes effect.
     *
     * Note that the specified timestamp must be greater than or equal to the
     * timestamp of any previously queued suspend/resume operations.
     *
     * The value is a long int, indicating the timestamp to suspend/resume.
     *
     * @see #setParameters(Bundle)
     */
    public static final String PARAMETER_KEY_SUSPEND_TIME = "drop-start-time-us";

    /**
     * Specify an offset (in micro-second) to be added on top of the timestamps
     * onward. A typical use case is to apply an adjust to the timestamps after
     * a period of pause by the user.
     *
     * This parameter can only be used on an encoder in "surface-input" mode.
     *
     * The value is a long int, indicating the timestamp offset to be applied.
     *
     * @see #setParameters(Bundle)
     */
    public static final String PARAMETER_KEY_OFFSET_TIME = "time-offset-us";

    /**
     * Request that the encoder produce a sync frame "soon".
     * Provide an Integer with the value 0.
     *
     * @see #setParameters(Bundle)
     */
    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME = "request-sync";

    /**
     * Set the HDR10+ metadata on the next queued input frame.
     *
     * Provide a byte array of data that's conforming to the
     * user_data_registered_itu_t_t35() syntax of SEI message for ST 2094-40.
     *<p>
     * For decoders:
     *<p>
     * When a decoder is configured for one of the HDR10+ profiles that uses
     * out-of-band metadata (such as {@link
     * MediaCodecInfo.CodecProfileLevel#VP9Profile2HDR10Plus} or {@link
     * MediaCodecInfo.CodecProfileLevel#VP9Profile3HDR10Plus}), this
     * parameter sets the HDR10+ metadata on the next input buffer queued
     * to the decoder. A decoder supporting these profiles must propagate
     * the metadata to the format of the output buffer corresponding to this
     * particular input buffer (under key {@link MediaFormat#KEY_HDR10_PLUS_INFO}).
     * The metadata should be applied to that output buffer and the buffers
     * following it (in display order), until the next output buffer (in
     * display order) upon which an HDR10+ metadata is set.
     *<p>
     * This parameter shouldn't be set if the decoder is not configured for
     * an HDR10+ profile that uses out-of-band metadata. In particular,
     * it shouldn't be set for HDR10+ profiles that uses in-band metadata
     * where the metadata is embedded in the input buffers, for example
     * {@link MediaCodecInfo.CodecProfileLevel#HEVCProfileMain10HDR10Plus}.
     *<p>
     * For encoders:
     *<p>
     * When an encoder is configured for one of the HDR10+ profiles and the
     * operates in byte buffer input mode (instead of surface input mode),
     * this parameter sets the HDR10+ metadata on the next input buffer queued
     * to the encoder. For the HDR10+ profiles that uses out-of-band metadata
     * (such as {@link MediaCodecInfo.CodecProfileLevel#VP9Profile2HDR10Plus},
     * or {@link MediaCodecInfo.CodecProfileLevel#VP9Profile3HDR10Plus}),
     * the metadata must be propagated to the format of the output buffer
     * corresponding to this particular input buffer (under key {@link
     * MediaFormat#KEY_HDR10_PLUS_INFO}). For the HDR10+ profiles that uses
     * in-band metadata (such as {@link
     * MediaCodecInfo.CodecProfileLevel#HEVCProfileMain10HDR10Plus}), the
     * metadata info must be embedded in the corresponding output buffer itself.
     *<p>
     * This parameter shouldn't be set if the encoder is not configured for
     * an HDR10+ profile, or if it's operating in surface input mode.
     *<p>
     *
     * @see MediaFormat#KEY_HDR10_PLUS_INFO
     */
    public static final String PARAMETER_KEY_HDR10_PLUS_INFO = MediaFormat.KEY_HDR10_PLUS_INFO;

    /**
     * Communicate additional parameter changes to the component instance.
     * <b>Note:</b> Some of these parameter changes may silently fail to apply.
     *
     * @param params The bundle of parameters to set.
     * @throws IllegalStateException if in the Released state.
     */
    public final void setParameters(@Nullable Bundle params) {
        if (params == null) {
            return;
        }

        String[] keys = new String[params.size()];
        Object[] values = new Object[params.size()];

        int i = 0;
        for (final String key: params.keySet()) {
            keys[i] = key;
            Object value = params.get(key);

            // Bundle's byte array is a byte[], JNI layer only takes ByteBuffer
            if (value instanceof byte[]) {
                values[i] = ByteBuffer.wrap((byte[])value);
            } else {
                values[i] = value;
            }
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
     * {@link #getInputBuffers}, {@link #getOutputBuffers},
     * {@link #dequeueInputBuffer(long)} or {@link #dequeueOutputBuffer(BufferInfo, long)}.
     * <p>
     * Also, {@link #flush} behaves differently in asynchronous mode.  After calling
     * {@code flush}, you must call {@link #start} to "resume" receiving input buffers,
     * even if an input surface was created.
     *
     * @param cb The callback that will run.  Use {@code null} to clear a previously
     *           set callback (before {@link #configure configure} is called and run
     *           in synchronous mode).
     * @param handler Callbacks will happen on the handler's thread. If {@code null},
     *           callbacks are done on the default thread (the caller's thread or the
     *           main thread.)
     */
    public void setCallback(@Nullable /* MediaCodec. */ Callback cb, @Nullable Handler handler) {
        if (cb != null) {
            synchronized (mListenerLock) {
                EventHandler newHandler = getEventHandlerOn(handler, mCallbackHandler);
                // NOTE: there are no callbacks on the handler at this time, but check anyways
                // even if we were to extend this to be callable dynamically, it must
                // be called when codec is flushed, so no messages are pending.
                if (newHandler != mCallbackHandler) {
                    mCallbackHandler.removeMessages(EVENT_SET_CALLBACK);
                    mCallbackHandler.removeMessages(EVENT_CALLBACK);
                    mCallbackHandler = newHandler;
                }
            }
        } else if (mCallbackHandler != null) {
            mCallbackHandler.removeMessages(EVENT_SET_CALLBACK);
            mCallbackHandler.removeMessages(EVENT_CALLBACK);
        }

        if (mCallbackHandler != null) {
            // set java callback on main handler
            Message msg = mCallbackHandler.obtainMessage(EVENT_SET_CALLBACK, 0, 0, cb);
            mCallbackHandler.sendMessage(msg);

            // set native handler here, don't post to handler because
            // it may cause the callback to be delayed and set in a wrong state.
            // Note that native codec may start sending events to the callback
            // handler after this returns.
            native_setCallback(cb);
        }
    }

    /**
     * Sets an asynchronous callback for actionable MediaCodec events on the default
     * looper.
     * <p>
     * Same as {@link #setCallback(Callback, Handler)} with handler set to null.
     * @param cb The callback that will run.  Use {@code null} to clear a previously
     *           set callback (before {@link #configure configure} is called and run
     *           in synchronous mode).
     * @see #setCallback(Callback, Handler)
     */
    public void setCallback(@Nullable /* MediaCodec. */ Callback cb) {
        setCallback(cb, null /* handler */);
    }

    /**
     * Listener to be called when an output frame has rendered on the output surface
     *
     * @see MediaCodec#setOnFrameRenderedListener
     */
    public interface OnFrameRenderedListener {

        /**
         * Called when an output frame has rendered on the output surface.
         * <p>
         * <strong>Note:</strong> This callback is for informational purposes only: to get precise
         * render timing samples, and can be significantly delayed and batched. Some frames may have
         * been rendered even if there was no callback generated.
         *
         * @param codec the MediaCodec instance
         * @param presentationTimeUs the presentation time (media time) of the frame rendered.
         *          This is usually the same as specified in {@link #queueInputBuffer}; however,
         *          some codecs may alter the media time by applying some time-based transformation,
         *          such as frame rate conversion. In that case, presentation time corresponds
         *          to the actual output frame rendered.
         * @param nanoTime The system time when the frame was rendered.
         *
         * @see System#nanoTime
         */
        public void onFrameRendered(
                @NonNull MediaCodec codec, long presentationTimeUs, long nanoTime);
    }

    /**
     * Registers a callback to be invoked when an output frame is rendered on the output surface.
     * <p>
     * This method can be called in any codec state, but will only have an effect in the
     * Executing state for codecs that render buffers to the output surface.
     * <p>
     * <strong>Note:</strong> This callback is for informational purposes only: to get precise
     * render timing samples, and can be significantly delayed and batched. Some frames may have
     * been rendered even if there was no callback generated.
     *
     * @param listener the callback that will be run
     * @param handler the callback will be run on the handler's thread. If {@code null},
     *           the callback will be run on the default thread, which is the looper
     *           from which the codec was created, or a new thread if there was none.
     */
    public void setOnFrameRenderedListener(
            @Nullable OnFrameRenderedListener listener, @Nullable Handler handler) {
        synchronized (mListenerLock) {
            mOnFrameRenderedListener = listener;
            if (listener != null) {
                EventHandler newHandler = getEventHandlerOn(handler, mOnFrameRenderedHandler);
                if (newHandler != mOnFrameRenderedHandler) {
                    mOnFrameRenderedHandler.removeMessages(EVENT_FRAME_RENDERED);
                }
                mOnFrameRenderedHandler = newHandler;
            } else if (mOnFrameRenderedHandler != null) {
                mOnFrameRenderedHandler.removeMessages(EVENT_FRAME_RENDERED);
            }
            native_enableOnFrameRenderedListener(listener != null);
        }
    }

    private native void native_enableOnFrameRenderedListener(boolean enable);

    private EventHandler getEventHandlerOn(
            @Nullable Handler handler, @NonNull EventHandler lastHandler) {
        if (handler == null) {
            return mEventHandler;
        } else {
            Looper looper = handler.getLooper();
            if (lastHandler.getLooper() == looper) {
                return lastHandler;
            } else {
                return new EventHandler(this, looper);
            }
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
        public abstract void onInputBufferAvailable(@NonNull MediaCodec codec, int index);

        /**
         * Called when an output buffer becomes available.
         *
         * @param codec The MediaCodec object.
         * @param index The index of the available output buffer.
         * @param info Info regarding the available output buffer {@link MediaCodec.BufferInfo}.
         */
        public abstract void onOutputBufferAvailable(
                @NonNull MediaCodec codec, int index, @NonNull BufferInfo info);

        /**
         * Called when the MediaCodec encountered an error
         *
         * @param codec The MediaCodec object.
         * @param e The {@link MediaCodec.CodecException} object describing the error.
         */
        public abstract void onError(@NonNull MediaCodec codec, @NonNull CodecException e);

        /**
         * Called when the output format has changed
         *
         * @param codec The MediaCodec object.
         * @param format The new output format.
         */
        public abstract void onOutputFormatChanged(
                @NonNull MediaCodec codec, @NonNull MediaFormat format);
    }

    private void postEventFromNative(
            int what, int arg1, int arg2, @Nullable Object obj) {
        synchronized (mListenerLock) {
            EventHandler handler = mEventHandler;
            if (what == EVENT_CALLBACK) {
                handler = mCallbackHandler;
            } else if (what == EVENT_FRAME_RENDERED) {
                handler = mOnFrameRenderedHandler;
            }
            if (handler != null) {
                Message msg = handler.obtainMessage(what, arg1, arg2, obj);
                handler.sendMessage(msg);
            }
        }
    }

    @UnsupportedAppUsage
    private native final void setParameters(@NonNull String[] keys, @NonNull Object[] values);

    /**
     * Get the codec info. If the codec was created by createDecoderByType
     * or createEncoderByType, what component is chosen is not known beforehand,
     * and thus the caller does not have the MediaCodecInfo.
     * @throws IllegalStateException if in the Released state.
     */
    @NonNull
    public MediaCodecInfo getCodecInfo() {
        // Get the codec name first. If the codec is already released,
        // IllegalStateException will be thrown here.
        String name = getName();
        synchronized (mCodecInfoLock) {
            if (mCodecInfo == null) {
                // Get the codec info for this codec itself first. Only initialize
                // the full codec list if this somehow fails because it can be slow.
                mCodecInfo = getOwnCodecInfo();
                if (mCodecInfo == null) {
                    mCodecInfo = MediaCodecList.getInfoFor(name);
                }
            }
            return mCodecInfo;
        }
    }

    @NonNull
    private native final MediaCodecInfo getOwnCodecInfo();

    @NonNull
    @UnsupportedAppUsage
    private native final ByteBuffer[] getBuffers(boolean input);

    @Nullable
    private native final ByteBuffer getBuffer(boolean input, int index);

    @Nullable
    private native final Image getImage(boolean input, int index);

    private static native final void native_init();

    private native final void native_setup(
            @NonNull String name, boolean nameIsType, boolean encoder);

    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private long mNativeContext = 0;
    private final Lock mNativeContextLock = new ReentrantLock();

    private final long lockAndGetContext() {
        mNativeContextLock.lock();
        return mNativeContext;
    }

    private final void setAndUnlockContext(long context) {
        mNativeContext = context;
        mNativeContextLock.unlock();
    }

    /** @hide */
    public static class MediaImage extends Image {
        private final boolean mIsReadOnly;
        private final int mWidth;
        private final int mHeight;
        private final int mFormat;
        private long mTimestamp;
        private final Plane[] mPlanes;
        private final ByteBuffer mBuffer;
        private final ByteBuffer mInfo;
        private final int mXOffset;
        private final int mYOffset;

        private final static int TYPE_YUV = 1;

        private final int mTransform = 0; //Default no transform
        private final int mScalingMode = 0; //Default frozen scaling mode

        @Override
        public int getFormat() {
            throwISEIfImageIsInvalid();
            return mFormat;
        }

        @Override
        public int getHeight() {
            throwISEIfImageIsInvalid();
            return mHeight;
        }

        @Override
        public int getWidth() {
            throwISEIfImageIsInvalid();
            return mWidth;
        }

        @Override
        public int getTransform() {
            throwISEIfImageIsInvalid();
            return mTransform;
        }

        @Override
        public int getScalingMode() {
            throwISEIfImageIsInvalid();
            return mScalingMode;
        }

        @Override
        public long getTimestamp() {
            throwISEIfImageIsInvalid();
            return mTimestamp;
        }

        @Override
        @NonNull
        public Plane[] getPlanes() {
            throwISEIfImageIsInvalid();
            return Arrays.copyOf(mPlanes, mPlanes.length);
        }

        @Override
        public void close() {
            if (mIsImageValid) {
                java.nio.NioUtils.freeDirectBuffer(mBuffer);
                mIsImageValid = false;
            }
        }

        /**
         * Set the crop rectangle associated with this frame.
         * <p>
         * The crop rectangle specifies the region of valid pixels in the image,
         * using coordinates in the largest-resolution plane.
         */
        @Override
        public void setCropRect(@Nullable Rect cropRect) {
            if (mIsReadOnly) {
                throw new ReadOnlyBufferException();
            }
            super.setCropRect(cropRect);
        }


        public MediaImage(
                @NonNull ByteBuffer buffer, @NonNull ByteBuffer info, boolean readOnly,
                long timestamp, int xOffset, int yOffset, @Nullable Rect cropRect) {
            mFormat = ImageFormat.YUV_420_888;
            mTimestamp = timestamp;
            mIsImageValid = true;
            mIsReadOnly = buffer.isReadOnly();
            mBuffer = buffer.duplicate();

            // save offsets and info
            mXOffset = xOffset;
            mYOffset = yOffset;
            mInfo = info;

            // read media-info.  See MediaImage2
            if (info.remaining() == 104) {
                int type = info.getInt();
                if (type != TYPE_YUV) {
                    throw new UnsupportedOperationException("unsupported type: " + type);
                }
                int numPlanes = info.getInt();
                if (numPlanes != 3) {
                    throw new RuntimeException("unexpected number of planes: " + numPlanes);
                }
                mWidth = info.getInt();
                mHeight = info.getInt();
                if (mWidth < 1 || mHeight < 1) {
                    throw new UnsupportedOperationException(
                            "unsupported size: " + mWidth + "x" + mHeight);
                }
                int bitDepth = info.getInt();
                if (bitDepth != 8) {
                    throw new UnsupportedOperationException("unsupported bit depth: " + bitDepth);
                }
                int bitDepthAllocated = info.getInt();
                if (bitDepthAllocated != 8) {
                    throw new UnsupportedOperationException(
                            "unsupported allocated bit depth: " + bitDepthAllocated);
                }
                mPlanes = new MediaPlane[numPlanes];
                for (int ix = 0; ix < numPlanes; ix++) {
                    int planeOffset = info.getInt();
                    int colInc = info.getInt();
                    int rowInc = info.getInt();
                    int horiz = info.getInt();
                    int vert = info.getInt();
                    if (horiz != vert || horiz != (ix == 0 ? 1 : 2)) {
                        throw new UnsupportedOperationException("unexpected subsampling: "
                                + horiz + "x" + vert + " on plane " + ix);
                    }
                    if (colInc < 1 || rowInc < 1) {
                        throw new UnsupportedOperationException("unexpected strides: "
                                + colInc + " pixel, " + rowInc + " row on plane " + ix);
                    }

                    buffer.clear();
                    buffer.position(mBuffer.position() + planeOffset
                            + (xOffset / horiz) * colInc + (yOffset / vert) * rowInc);
                    buffer.limit(buffer.position() + Utils.divUp(bitDepth, 8)
                            + (mHeight / vert - 1) * rowInc + (mWidth / horiz - 1) * colInc);
                    mPlanes[ix] = new MediaPlane(buffer.slice(), rowInc, colInc);
                }
            } else {
                throw new UnsupportedOperationException(
                        "unsupported info length: " + info.remaining());
            }

            if (cropRect == null) {
                cropRect = new Rect(0, 0, mWidth, mHeight);
            }
            cropRect.offset(-xOffset, -yOffset);
            super.setCropRect(cropRect);
        }

        private class MediaPlane extends Plane {
            public MediaPlane(@NonNull ByteBuffer buffer, int rowInc, int colInc) {
                mData = buffer;
                mRowInc = rowInc;
                mColInc = colInc;
            }

            @Override
            public int getRowStride() {
                throwISEIfImageIsInvalid();
                return mRowInc;
            }

            @Override
            public int getPixelStride() {
                throwISEIfImageIsInvalid();
                return mColInc;
            }

            @Override
            @NonNull
            public ByteBuffer getBuffer() {
                throwISEIfImageIsInvalid();
                return mData;
            }

            private final int mRowInc;
            private final int mColInc;
            private final ByteBuffer mData;
        }
    }

    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        /**
         * Key to extract the codec being used
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String.
         */
        public static final String CODEC = "android.media.mediacodec.codec";

        /**
         * Key to extract the MIME type
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String.
         */
        public static final String MIME_TYPE = "android.media.mediacodec.mime";

        /**
         * Key to extract what the codec mode
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String. Values will be one of the constants
         * {@link #MODE_AUDIO} or {@link #MODE_VIDEO}.
         */
        public static final String MODE = "android.media.mediacodec.mode";

        /**
         * The value returned for the key {@link #MODE} when the
         * codec is a audio codec.
         */
        public static final String MODE_AUDIO = "audio";

        /**
         * The value returned for the key {@link #MODE} when the
         * codec is a video codec.
         */
        public static final String MODE_VIDEO = "video";

        /**
         * Key to extract the flag indicating whether the codec is running
         * as an encoder or decoder from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         * A 0 indicates decoder; 1 indicates encoder.
         */
        public static final String ENCODER = "android.media.mediacodec.encoder";

        /**
         * Key to extract the flag indicating whether the codec is running
         * in secure (DRM) mode from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String SECURE = "android.media.mediacodec.secure";

        /**
         * Key to extract the width (in pixels) of the video track
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String WIDTH = "android.media.mediacodec.width";

        /**
         * Key to extract the height (in pixels) of the video track
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String HEIGHT = "android.media.mediacodec.height";

        /**
         * Key to extract the rotation (in degrees) to properly orient the video
         * from the {@link MediaCodec#getMetrics} return.
         * The value is a integer.
         */
        public static final String ROTATION = "android.media.mediacodec.rotation";

    }
}
