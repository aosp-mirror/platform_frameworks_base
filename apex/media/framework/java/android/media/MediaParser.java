/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.media.MediaCodec.CryptoInfo;
import android.media.metrics.LogSessionId;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.extractor.ChunkIndex;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap.SeekPoints;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.amr.AmrExtractor;
import com.google.android.exoplayer2.extractor.flac.FlacExtractor;
import com.google.android.exoplayer2.extractor.flv.FlvExtractor;
import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer2.extractor.ogg.OggExtractor;
import com.google.android.exoplayer2.extractor.ts.Ac3Extractor;
import com.google.android.exoplayer2.extractor.ts.Ac4Extractor;
import com.google.android.exoplayer2.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.PsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.wav.WavExtractor;
import com.google.android.exoplayer2.upstream.DataReader;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.ColorInfo;

import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Parses media container formats and extracts contained media samples and metadata.
 *
 * <p>This class provides access to a battery of low-level media container parsers. Each instance of
 * this class is associated to a specific media parser implementation which is suitable for
 * extraction from a specific media container format. The media parser implementation assignment
 * depends on the factory method (see {@link #create} and {@link #createByName}) used to create the
 * instance.
 *
 * <p>Users must implement the following to use this class.
 *
 * <ul>
 *   <li>{@link InputReader}: Provides the media container's bytes to parse.
 *   <li>{@link OutputConsumer}: Provides a sink for all extracted data and metadata.
 * </ul>
 *
 * <p>The following code snippet includes a usage example:
 *
 * <pre>
 * MyOutputConsumer myOutputConsumer = new MyOutputConsumer();
 * MyInputReader myInputReader = new MyInputReader("www.example.com");
 * MediaParser mediaParser = MediaParser.create(myOutputConsumer);
 *
 * while (mediaParser.advance(myInputReader)) {}
 *
 * mediaParser.release();
 * mediaParser = null;
 * </pre>
 *
 * <p>The following code snippet provides a rudimentary {@link OutputConsumer} sample implementation
 * which extracts and publishes all video samples:
 *
 * <pre>
 * class VideoOutputConsumer implements MediaParser.OutputConsumer {
 *
 *     private byte[] sampleDataBuffer = new byte[4096];
 *     private byte[] discardedDataBuffer = new byte[4096];
 *     private int videoTrackIndex = -1;
 *     private int bytesWrittenCount = 0;
 *
 *     &#64;Override
 *     public void onSeekMapFound(int i, &#64;NonNull MediaFormat mediaFormat) {
 *       // Do nothing.
 *     }
 *
 *     &#64;Override
 *     public void onTrackDataFound(int i, &#64;NonNull TrackData trackData) {
 *       MediaFormat mediaFormat = trackData.mediaFormat;
 *       if (videoTrackIndex == -1 &amp;&amp;
 *           mediaFormat
 *               .getString(MediaFormat.KEY_MIME, &#47;* defaultValue= *&#47; "")
 *               .startsWith("video/")) {
 *         videoTrackIndex = i;
 *       }
 *     }
 *
 *     &#64;Override
 *     public void onSampleDataFound(int trackIndex, &#64;NonNull InputReader inputReader)
 *         throws IOException {
 *       int numberOfBytesToRead = (int) inputReader.getLength();
 *       if (videoTrackIndex != trackIndex) {
 *         // Discard contents.
 *         inputReader.read(
 *             discardedDataBuffer,
 *             &#47;* offset= *&#47; 0,
 *             Math.min(discardDataBuffer.length, numberOfBytesToRead));
 *       } else {
 *         ensureSpaceInBuffer(numberOfBytesToRead);
 *         int bytesRead = inputReader.read(
 *             sampleDataBuffer, bytesWrittenCount, numberOfBytesToRead);
 *         bytesWrittenCount += bytesRead;
 *       }
 *     }
 *
 *     &#64;Override
 *     public void onSampleCompleted(
 *         int trackIndex,
 *         long timeMicros,
 *         int flags,
 *         int size,
 *         int offset,
 *         &#64;Nullable CryptoInfo cryptoData) {
 *       if (videoTrackIndex != trackIndex) {
 *         return; // It's not the video track. Ignore.
 *       }
 *       byte[] sampleData = new byte[size];
 *       int sampleStartOffset = bytesWrittenCount - size - offset;
 *       System.arraycopy(
 *           sampleDataBuffer,
 *           sampleStartOffset,
 *           sampleData,
 *           &#47;* destPos= *&#47; 0,
 *           size);
 *       // Place trailing bytes at the start of the buffer.
 *       System.arraycopy(
 *           sampleDataBuffer,
 *           bytesWrittenCount - offset,
 *           sampleDataBuffer,
 *           &#47;* destPos= *&#47; 0,
 *           &#47;* size= *&#47; offset);
 *       bytesWrittenCount = bytesWrittenCount - offset;
 *       publishSample(sampleData, timeMicros, flags);
 *     }
 *
 *    private void ensureSpaceInBuffer(int numberOfBytesToRead) {
 *      int requiredLength = bytesWrittenCount + numberOfBytesToRead;
 *      if (requiredLength &gt; sampleDataBuffer.length) {
 *        sampleDataBuffer = Arrays.copyOf(sampleDataBuffer, requiredLength);
 *      }
 *    }
 *
 *   }
 *
 * </pre>
 */
public final class MediaParser {

    /**
     * Maps seek positions to {@link SeekPoint SeekPoints} in the stream.
     *
     * <p>A {@link SeekPoint} is a position in the stream from which a player may successfully start
     * playing media samples.
     */
    public static final class SeekMap {

        /** Returned by {@link #getDurationMicros()} when the duration is unknown. */
        public static final int UNKNOWN_DURATION = Integer.MIN_VALUE;

        /**
         * For each {@link #getSeekPoints} call, returns a single {@link SeekPoint} whose {@link
         * SeekPoint#timeMicros} matches the requested timestamp, and whose {@link
         * SeekPoint#position} is 0.
         *
         * @hide
         */
        public static final SeekMap DUMMY = new SeekMap(new DummyExoPlayerSeekMap());

        private final com.google.android.exoplayer2.extractor.SeekMap mExoPlayerSeekMap;

        private SeekMap(com.google.android.exoplayer2.extractor.SeekMap exoplayerSeekMap) {
            mExoPlayerSeekMap = exoplayerSeekMap;
        }

        /** Returns whether seeking is supported. */
        public boolean isSeekable() {
            return mExoPlayerSeekMap.isSeekable();
        }

        /**
         * Returns the duration of the stream in microseconds or {@link #UNKNOWN_DURATION} if the
         * duration is unknown.
         */
        public long getDurationMicros() {
            long durationUs = mExoPlayerSeekMap.getDurationUs();
            return durationUs != C.TIME_UNSET ? durationUs : UNKNOWN_DURATION;
        }

        /**
         * Obtains {@link SeekPoint SeekPoints} for the specified seek time in microseconds.
         *
         * <p>{@code getSeekPoints(timeMicros).first} contains the latest seek point for samples
         * with timestamp equal to or smaller than {@code timeMicros}.
         *
         * <p>{@code getSeekPoints(timeMicros).second} contains the earliest seek point for samples
         * with timestamp equal to or greater than {@code timeMicros}. If a seek point exists for
         * {@code timeMicros}, the returned pair will contain the same {@link SeekPoint} twice.
         *
         * @param timeMicros A seek time in microseconds.
         * @return The corresponding {@link SeekPoint SeekPoints}.
         */
        @NonNull
        public Pair<SeekPoint, SeekPoint> getSeekPoints(long timeMicros) {
            SeekPoints seekPoints = mExoPlayerSeekMap.getSeekPoints(timeMicros);
            return new Pair<>(toSeekPoint(seekPoints.first), toSeekPoint(seekPoints.second));
        }
    }

    /** Holds information associated with a track. */
    public static final class TrackData {

        /** Holds {@link MediaFormat} information for the track. */
        @NonNull public final MediaFormat mediaFormat;

        /**
         * Holds {@link DrmInitData} necessary to acquire keys associated with the track, or null if
         * the track has no encryption data.
         */
        @Nullable public final DrmInitData drmInitData;

        private TrackData(MediaFormat mediaFormat, DrmInitData drmInitData) {
            this.mediaFormat = mediaFormat;
            this.drmInitData = drmInitData;
        }
    }

    /** Defines a seek point in a media stream. */
    public static final class SeekPoint {

        /** A {@link SeekPoint} whose time and byte offset are both set to 0. */
        @NonNull public static final SeekPoint START = new SeekPoint(0, 0);

        /** The time of the seek point, in microseconds. */
        public final long timeMicros;

        /** The byte offset of the seek point. */
        public final long position;

        /**
         * @param timeMicros The time of the seek point, in microseconds.
         * @param position The byte offset of the seek point.
         */
        private SeekPoint(long timeMicros, long position) {
            this.timeMicros = timeMicros;
            this.position = position;
        }

        @Override
        @NonNull
        public String toString() {
            return "[timeMicros=" + timeMicros + ", position=" + position + "]";
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            SeekPoint other = (SeekPoint) obj;
            return timeMicros == other.timeMicros && position == other.position;
        }

        @Override
        public int hashCode() {
            int result = (int) timeMicros;
            result = 31 * result + (int) position;
            return result;
        }
    }

    /** Provides input data to {@link MediaParser}. */
    public interface InputReader {

        /**
         * Reads up to {@code readLength} bytes of data and stores them into {@code buffer},
         * starting at index {@code offset}.
         *
         * <p>This method blocks until at least one byte is read, the end of input is detected, or
         * an exception is thrown. The read position advances to the first unread byte.
         *
         * @param buffer The buffer into which the read data should be stored.
         * @param offset The start offset into {@code buffer} at which data should be written.
         * @param readLength The maximum number of bytes to read.
         * @return The non-zero number of bytes read, or -1 if no data is available because the end
         *     of the input has been reached.
         * @throws java.io.IOException If an error occurs reading from the source.
         */
        int read(@NonNull byte[] buffer, int offset, int readLength) throws IOException;

        /** Returns the current read position (byte offset) in the stream. */
        long getPosition();

        /** Returns the length of the input in bytes, or -1 if the length is unknown. */
        long getLength();
    }

    /** {@link InputReader} that allows setting the read position. */
    public interface SeekableInputReader extends InputReader {

        /**
         * Sets the read position at the given {@code position}.
         *
         * <p>{@link #advance} will immediately return after calling this method.
         *
         * @param position The position to seek to, in bytes.
         */
        void seekToPosition(long position);
    }

    /** Receives extracted media sample data and metadata from {@link MediaParser}. */
    public interface OutputConsumer {

        /**
         * Called when a {@link SeekMap} has been extracted from the stream.
         *
         * <p>This method is called at least once before any samples are {@link #onSampleCompleted
         * complete}. May be called multiple times after that in order to add {@link SeekPoint
         * SeekPoints}.
         *
         * @param seekMap The extracted {@link SeekMap}.
         */
        void onSeekMapFound(@NonNull SeekMap seekMap);

        /**
         * Called when the number of tracks is found.
         *
         * @param numberOfTracks The number of tracks in the stream.
         */
        void onTrackCountFound(int numberOfTracks);

        /**
         * Called when new {@link TrackData} is found in the stream.
         *
         * @param trackIndex The index of the track for which the {@link TrackData} was extracted.
         * @param trackData The extracted {@link TrackData}.
         */
        void onTrackDataFound(int trackIndex, @NonNull TrackData trackData);

        /**
         * Called when sample data is found in the stream.
         *
         * <p>If the invocation of this method returns before the entire {@code inputReader} {@link
         * InputReader#getLength() length} is consumed, the method will be called again for the
         * implementer to read the remaining data. Implementers should surface any thrown {@link
         * IOException} caused by reading from {@code input}.
         *
         * @param trackIndex The index of the track to which the sample data corresponds.
         * @param inputReader The {@link InputReader} from which to read the data.
         * @throws IOException If an exception occurs while reading from {@code inputReader}.
         */
        void onSampleDataFound(int trackIndex, @NonNull InputReader inputReader) throws IOException;

        /**
         * Called once all the data of a sample has been passed to {@link #onSampleDataFound}.
         *
         * <p>Includes sample metadata, like presentation timestamp and flags.
         *
         * @param trackIndex The index of the track to which the sample corresponds.
         * @param timeMicros The media timestamp associated with the sample, in microseconds.
         * @param flags Flags associated with the sample. See the {@code SAMPLE_FLAG_*} constants.
         * @param size The size of the sample data, in bytes.
         * @param offset The number of bytes that have been consumed by {@code
         *     onSampleDataFound(int, MediaParser.InputReader)} for the specified track, since the
         *     last byte belonging to the sample whose metadata is being passed.
         * @param cryptoInfo Encryption data required to decrypt the sample. May be null for
         *     unencrypted samples. Implementors should treat any output {@link CryptoInfo}
         *     instances as immutable. MediaParser will not modify any output {@code cryptoInfos}
         *     and implementors should not modify them either.
         */
        void onSampleCompleted(
                int trackIndex,
                long timeMicros,
                @SampleFlags int flags,
                int size,
                int offset,
                @Nullable CryptoInfo cryptoInfo);
    }

    /**
     * Thrown if all parser implementations provided to {@link #create} failed to sniff the input
     * content.
     */
    public static final class UnrecognizedInputFormatException extends IOException {

        /**
         * Creates a new instance which signals that the parsers with the given names failed to
         * parse the input.
         */
        @NonNull
        @CheckResult
        private static UnrecognizedInputFormatException createForExtractors(
                @NonNull String... extractorNames) {
            StringBuilder builder = new StringBuilder();
            builder.append("None of the available parsers ( ");
            builder.append(extractorNames[0]);
            for (int i = 1; i < extractorNames.length; i++) {
                builder.append(", ");
                builder.append(extractorNames[i]);
            }
            builder.append(") could read the stream.");
            return new UnrecognizedInputFormatException(builder.toString());
        }

        private UnrecognizedInputFormatException(String extractorNames) {
            super(extractorNames);
        }
    }

    /** Thrown when an error occurs while parsing a media stream. */
    public static final class ParsingException extends IOException {

        private ParsingException(ParserException cause) {
            super(cause);
        }
    }

    // Sample flags.

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            flag = true,
            value = {
                SAMPLE_FLAG_KEY_FRAME,
                SAMPLE_FLAG_HAS_SUPPLEMENTAL_DATA,
                SAMPLE_FLAG_LAST_SAMPLE,
                SAMPLE_FLAG_ENCRYPTED,
                SAMPLE_FLAG_DECODE_ONLY
            })
    public @interface SampleFlags {}
    /** Indicates that the sample holds a synchronization sample. */
    public static final int SAMPLE_FLAG_KEY_FRAME = MediaCodec.BUFFER_FLAG_KEY_FRAME;
    /**
     * Indicates that the sample has supplemental data.
     *
     * <p>Samples will not have this flag set unless the {@code
     * "android.media.mediaparser.includeSupplementalData"} parameter is set to {@code true} via
     * {@link #setParameter}.
     *
     * <p>Samples with supplemental data have the following sample data format:
     *
     * <ul>
     *   <li>If the {@code "android.media.mediaparser.inBandCryptoInfo"} parameter is set, all
     *       encryption information.
     *   <li>(4 bytes) {@code sample_data_size}: The size of the actual sample data, not including
     *       supplemental data or encryption information.
     *   <li>({@code sample_data_size} bytes): The media sample data.
     *   <li>(remaining bytes) The supplemental data.
     * </ul>
     */
    public static final int SAMPLE_FLAG_HAS_SUPPLEMENTAL_DATA = 1 << 28;
    /** Indicates that the sample is known to contain the last media sample of the stream. */
    public static final int SAMPLE_FLAG_LAST_SAMPLE = 1 << 29;
    /** Indicates that the sample is (at least partially) encrypted. */
    public static final int SAMPLE_FLAG_ENCRYPTED = 1 << 30;
    /** Indicates that the sample should be decoded but not rendered. */
    public static final int SAMPLE_FLAG_DECODE_ONLY = 1 << 31;

    // Parser implementation names.

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(
            prefix = {"PARSER_NAME_"},
            value = {
                PARSER_NAME_UNKNOWN,
                PARSER_NAME_MATROSKA,
                PARSER_NAME_FMP4,
                PARSER_NAME_MP4,
                PARSER_NAME_MP3,
                PARSER_NAME_ADTS,
                PARSER_NAME_AC3,
                PARSER_NAME_TS,
                PARSER_NAME_FLV,
                PARSER_NAME_OGG,
                PARSER_NAME_PS,
                PARSER_NAME_WAV,
                PARSER_NAME_AMR,
                PARSER_NAME_AC4,
                PARSER_NAME_FLAC
            })
    public @interface ParserName {}

    /** Parser name returned by {@link #getParserName()} when no parser has been selected yet. */
    public static final String PARSER_NAME_UNKNOWN = "android.media.mediaparser.UNKNOWN";
    /**
     * Parser for the Matroska container format, as defined in the <a
     * href="https://matroska.org/technical/specs/">spec</a>.
     */
    public static final String PARSER_NAME_MATROSKA = "android.media.mediaparser.MatroskaParser";
    /**
     * Parser for fragmented files using the MP4 container format, as defined in ISO/IEC 14496-12.
     */
    public static final String PARSER_NAME_FMP4 = "android.media.mediaparser.FragmentedMp4Parser";
    /**
     * Parser for non-fragmented files using the MP4 container format, as defined in ISO/IEC
     * 14496-12.
     */
    public static final String PARSER_NAME_MP4 = "android.media.mediaparser.Mp4Parser";
    /** Parser for the MP3 container format, as defined in ISO/IEC 11172-3. */
    public static final String PARSER_NAME_MP3 = "android.media.mediaparser.Mp3Parser";
    /** Parser for the ADTS container format, as defined in ISO/IEC 13818-7. */
    public static final String PARSER_NAME_ADTS = "android.media.mediaparser.AdtsParser";
    /**
     * Parser for the AC-3 container format, as defined in Digital Audio Compression Standard
     * (AC-3).
     */
    public static final String PARSER_NAME_AC3 = "android.media.mediaparser.Ac3Parser";
    /** Parser for the TS container format, as defined in ISO/IEC 13818-1. */
    public static final String PARSER_NAME_TS = "android.media.mediaparser.TsParser";
    /**
     * Parser for the FLV container format, as defined in Adobe Flash Video File Format
     * Specification.
     */
    public static final String PARSER_NAME_FLV = "android.media.mediaparser.FlvParser";
    /** Parser for the OGG container format, as defined in RFC 3533. */
    public static final String PARSER_NAME_OGG = "android.media.mediaparser.OggParser";
    /** Parser for the PS container format, as defined in ISO/IEC 11172-1. */
    public static final String PARSER_NAME_PS = "android.media.mediaparser.PsParser";
    /**
     * Parser for the WAV container format, as defined in Multimedia Programming Interface and Data
     * Specifications.
     */
    public static final String PARSER_NAME_WAV = "android.media.mediaparser.WavParser";
    /** Parser for the AMR container format, as defined in RFC 4867. */
    public static final String PARSER_NAME_AMR = "android.media.mediaparser.AmrParser";
    /**
     * Parser for the AC-4 container format, as defined by Dolby AC-4: Audio delivery for
     * Next-Generation Entertainment Services.
     */
    public static final String PARSER_NAME_AC4 = "android.media.mediaparser.Ac4Parser";
    /**
     * Parser for the FLAC container format, as defined in the <a
     * href="https://xiph.org/flac/">spec</a>.
     */
    public static final String PARSER_NAME_FLAC = "android.media.mediaparser.FlacParser";

    // MediaParser parameters.

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(
            prefix = {"PARAMETER_"},
            value = {
                PARAMETER_ADTS_ENABLE_CBR_SEEKING,
                PARAMETER_AMR_ENABLE_CBR_SEEKING,
                PARAMETER_FLAC_DISABLE_ID3,
                PARAMETER_MP4_IGNORE_EDIT_LISTS,
                PARAMETER_MP4_IGNORE_TFDT_BOX,
                PARAMETER_MP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES,
                PARAMETER_MATROSKA_DISABLE_CUES_SEEKING,
                PARAMETER_MP3_DISABLE_ID3,
                PARAMETER_MP3_ENABLE_CBR_SEEKING,
                PARAMETER_MP3_ENABLE_INDEX_SEEKING,
                PARAMETER_TS_MODE,
                PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES,
                PARAMETER_TS_IGNORE_AAC_STREAM,
                PARAMETER_TS_IGNORE_AVC_STREAM,
                PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM,
                PARAMETER_TS_DETECT_ACCESS_UNITS,
                PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                PARAMETER_IN_BAND_CRYPTO_INFO,
                PARAMETER_INCLUDE_SUPPLEMENTAL_DATA
            })
    public @interface ParameterName {}

    /**
     * Sets whether constant bitrate seeking should be enabled for ADTS parsing. {@code boolean}
     * expected. Default value is {@code false}.
     */
    public static final String PARAMETER_ADTS_ENABLE_CBR_SEEKING =
            "android.media.mediaparser.adts.enableCbrSeeking";
    /**
     * Sets whether constant bitrate seeking should be enabled for AMR. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_AMR_ENABLE_CBR_SEEKING =
            "android.media.mediaparser.amr.enableCbrSeeking";
    /**
     * Sets whether the ID3 track should be disabled for FLAC. {@code boolean} expected. Default
     * value is {@code false}.
     */
    public static final String PARAMETER_FLAC_DISABLE_ID3 =
            "android.media.mediaparser.flac.disableId3";
    /**
     * Sets whether MP4 parsing should ignore edit lists. {@code boolean} expected. Default value is
     * {@code false}.
     */
    public static final String PARAMETER_MP4_IGNORE_EDIT_LISTS =
            "android.media.mediaparser.mp4.ignoreEditLists";
    /**
     * Sets whether MP4 parsing should ignore the tfdt box. {@code boolean} expected. Default value
     * is {@code false}.
     */
    public static final String PARAMETER_MP4_IGNORE_TFDT_BOX =
            "android.media.mediaparser.mp4.ignoreTfdtBox";
    /**
     * Sets whether MP4 parsing should treat all video frames as key frames. {@code boolean}
     * expected. Default value is {@code false}.
     */
    public static final String PARAMETER_MP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES =
            "android.media.mediaparser.mp4.treatVideoFramesAsKeyframes";
    /**
     * Sets whether Matroska parsing should avoid seeking to the cues element. {@code boolean}
     * expected. Default value is {@code false}.
     *
     * <p>If this flag is enabled and the cues element occurs after the first cluster, then the
     * media is treated as unseekable.
     */
    public static final String PARAMETER_MATROSKA_DISABLE_CUES_SEEKING =
            "android.media.mediaparser.matroska.disableCuesSeeking";
    /**
     * Sets whether the ID3 track should be disabled for MP3. {@code boolean} expected. Default
     * value is {@code false}.
     */
    public static final String PARAMETER_MP3_DISABLE_ID3 =
            "android.media.mediaparser.mp3.disableId3";
    /**
     * Sets whether constant bitrate seeking should be enabled for MP3. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_MP3_ENABLE_CBR_SEEKING =
            "android.media.mediaparser.mp3.enableCbrSeeking";
    /**
     * Sets whether MP3 parsing should generate a time-to-byte mapping. {@code boolean} expected.
     * Default value is {@code false}.
     *
     * <p>Enabling this flag may require to scan a significant portion of the file to compute a seek
     * point. Therefore, it should only be used if:
     *
     * <ul>
     *   <li>the file is small, or
     *   <li>the bitrate is variable (or the type of bitrate is unknown) and the seeking metadata
     *       provided in the file is not precise enough (or is not present).
     * </ul>
     */
    public static final String PARAMETER_MP3_ENABLE_INDEX_SEEKING =
            "android.media.mediaparser.mp3.enableIndexSeeking";
    /**
     * Sets the operation mode for TS parsing. {@code String} expected. Valid values are {@code
     * "single_pmt"}, {@code "multi_pmt"}, and {@code "hls"}. Default value is {@code "single_pmt"}.
     *
     * <p>The operation modes alter the way TS behaves so that it can handle certain kinds of
     * commonly-occurring malformed media.
     *
     * <ul>
     *   <li>{@code "single_pmt"}: Only the first found PMT is parsed. Others are ignored, even if
     *       more PMTs are declared in the PAT.
     *   <li>{@code "multi_pmt"}: Behave as described in ISO/IEC 13818-1.
     *   <li>{@code "hls"}: Enable {@code "single_pmt"} mode, and ignore continuity counters.
     * </ul>
     */
    public static final String PARAMETER_TS_MODE = "android.media.mediaparser.ts.mode";
    /**
     * Sets whether TS should treat samples consisting of non-IDR I slices as synchronization
     * samples (key-frames). {@code boolean} expected. Default value is {@code false}.
     */
    public static final String PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES =
            "android.media.mediaparser.ts.allowNonIdrAvcKeyframes";
    /**
     * Sets whether TS parsing should ignore AAC elementary streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_AAC_STREAM =
            "android.media.mediaparser.ts.ignoreAacStream";
    /**
     * Sets whether TS parsing should ignore AVC elementary streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_AVC_STREAM =
            "android.media.mediaparser.ts.ignoreAvcStream";
    /**
     * Sets whether TS parsing should ignore splice information streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM =
            "android.media.mediaparser.ts.ignoreSpliceInfoStream";
    /**
     * Sets whether TS parsing should split AVC stream into access units based on slice headers.
     * {@code boolean} expected. Default value is {@code false}.
     *
     * <p>This flag should be left disabled if the stream contains access units delimiters in order
     * to avoid unnecessary computational costs.
     */
    public static final String PARAMETER_TS_DETECT_ACCESS_UNITS =
            "android.media.mediaparser.ts.ignoreDetectAccessUnits";
    /**
     * Sets whether TS parsing should handle HDMV DTS audio streams. {@code boolean} expected.
     * Default value is {@code false}.
     *
     * <p>Enabling this flag will disable the detection of SCTE subtitles.
     */
    public static final String PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS =
            "android.media.mediaparser.ts.enableHdmvDtsAudioStreams";
    /**
     * Sets whether encryption data should be sent in-band with the sample data, as per {@link
     * OutputConsumer#onSampleDataFound}. {@code boolean} expected. Default value is {@code false}.
     *
     * <p>If this parameter is set, encrypted samples' data will be prefixed with the encryption
     * information bytes. The format for in-band encryption information is:
     *
     * <ul>
     *   <li>(1 byte) {@code encryption_signal_byte}: Most significant bit signals whether the
     *       encryption data contains subsample encryption data. The remaining bits contain {@code
     *       initialization_vector_size}.
     *   <li>({@code initialization_vector_size} bytes) Initialization vector.
     *   <li>If subsample encryption data is present, as per {@code encryption_signal_byte}, the
     *       encryption data also contains:
     *       <ul>
     *         <li>(2 bytes) {@code subsample_encryption_data_length}.
     *         <li>({@code subsample_encryption_data_length * 6} bytes) Subsample encryption data
     *             (repeated {@code subsample_encryption_data_length} times):
     *             <ul>
     *               <li>(2 bytes) Size of a clear section in sample.
     *               <li>(4 bytes) Size of an encryption section in sample.
     *             </ul>
     *       </ul>
     * </ul>
     *
     * @hide
     */
    public static final String PARAMETER_IN_BAND_CRYPTO_INFO =
            "android.media.mediaparser.inBandCryptoInfo";
    /**
     * Sets whether supplemental data should be included as part of the sample data. {@code boolean}
     * expected. Default value is {@code false}. See {@link #SAMPLE_FLAG_HAS_SUPPLEMENTAL_DATA} for
     * information about the sample data format.
     *
     * @hide
     */
    public static final String PARAMETER_INCLUDE_SUPPLEMENTAL_DATA =
            "android.media.mediaparser.includeSupplementalData";
    /**
     * Sets whether sample timestamps may start from non-zero offsets. {@code boolean} expected.
     * Default value is {@code false}.
     *
     * <p>When set to true, sample timestamps will not be offset to start from zero, and the media
     * provided timestamps will be used instead. For example, transport stream sample timestamps
     * will not be converted to a zero-based timebase.
     *
     * @hide
     */
    public static final String PARAMETER_IGNORE_TIMESTAMP_OFFSET =
            "android.media.mediaparser.ignoreTimestampOffset";
    /**
     * Sets whether each track type should be eagerly exposed. {@code boolean} expected. Default
     * value is {@code false}.
     *
     * <p>When set to true, each track type will be eagerly exposed through a call to {@link
     * OutputConsumer#onTrackDataFound} containing a single-value {@link MediaFormat}. The key for
     * the track type is {@code "track-type-string"}, and the possible values are {@code "video"},
     * {@code "audio"}, {@code "text"}, {@code "metadata"}, and {@code "unknown"}.
     *
     * @hide
     */
    public static final String PARAMETER_EAGERLY_EXPOSE_TRACKTYPE =
            "android.media.mediaparser.eagerlyExposeTrackType";
    /**
     * Sets whether a dummy {@link SeekMap} should be exposed before starting extraction. {@code
     * boolean} expected. Default value is {@code false}.
     *
     * <p>For each {@link SeekMap#getSeekPoints} call, the dummy {@link SeekMap} returns a single
     * {@link SeekPoint} whose {@link SeekPoint#timeMicros} matches the requested timestamp, and
     * whose {@link SeekPoint#position} is 0.
     *
     * @hide
     */
    public static final String PARAMETER_EXPOSE_DUMMY_SEEKMAP =
            "android.media.mediaparser.exposeDummySeekMap";

    /**
     * Sets whether chunk indices available in the extracted media should be exposed as {@link
     * MediaFormat MediaFormats}. {@code boolean} expected. Default value is {@link false}.
     *
     * <p>When set to true, any information about media segmentation will be exposed as a {@link
     * MediaFormat} (with track index 0) containing four {@link ByteBuffer} elements under the
     * following keys:
     *
     * <ul>
     *   <li>"chunk-index-int-sizes": Contains {@code ints} representing the sizes in bytes of each
     *       of the media segments.
     *   <li>"chunk-index-long-offsets": Contains {@code longs} representing the byte offsets of
     *       each segment in the stream.
     *   <li>"chunk-index-long-us-durations": Contains {@code longs} representing the media duration
     *       of each segment, in microseconds.
     *   <li>"chunk-index-long-us-times": Contains {@code longs} representing the start time of each
     *       segment, in microseconds.
     * </ul>
     *
     * @hide
     */
    public static final String PARAMETER_EXPOSE_CHUNK_INDEX_AS_MEDIA_FORMAT =
            "android.media.mediaParser.exposeChunkIndexAsMediaFormat";
    /**
     * Sets a list of closed-caption {@link MediaFormat MediaFormats} that should be exposed as part
     * of the extracted media. {@code List<MediaFormat>} expected. Default value is an empty list.
     *
     * <p>Expected keys in the {@link MediaFormat} are:
     *
     * <ul>
     *   <p>{@link MediaFormat#KEY_MIME}: Determine the type of captions (for example,
     *   application/cea-608). Mandatory.
     *   <p>{@link MediaFormat#KEY_CAPTION_SERVICE_NUMBER}: Determine the channel on which the
     *   captions are transmitted. Optional.
     * </ul>
     *
     * @hide
     */
    public static final String PARAMETER_EXPOSE_CAPTION_FORMATS =
            "android.media.mediaParser.exposeCaptionFormats";
    /**
     * Sets whether the value associated with {@link #PARAMETER_EXPOSE_CAPTION_FORMATS} should
     * override any in-band caption service declarations. {@code boolean} expected. Default value is
     * {@link false}.
     *
     * <p>When {@code false}, any present in-band caption services information will override the
     * values associated with {@link #PARAMETER_EXPOSE_CAPTION_FORMATS}.
     *
     * @hide
     */
    public static final String PARAMETER_OVERRIDE_IN_BAND_CAPTION_DECLARATIONS =
            "android.media.mediaParser.overrideInBandCaptionDeclarations";
    /**
     * Sets whether a track for EMSG events should be exposed in case of parsing a container that
     * supports them. {@code boolean} expected. Default value is {@link false}.
     *
     * @hide
     */
    public static final String PARAMETER_EXPOSE_EMSG_TRACK =
            "android.media.mediaParser.exposeEmsgTrack";

    // Private constants.

    private static final String TAG = "MediaParser";
    private static final String JNI_LIBRARY_NAME = "mediaparser-jni";
    private static final Map<String, ExtractorFactory> EXTRACTOR_FACTORIES_BY_NAME;
    private static final Map<String, Class> EXPECTED_TYPE_BY_PARAMETER_NAME;
    private static final String TS_MODE_SINGLE_PMT = "single_pmt";
    private static final String TS_MODE_MULTI_PMT = "multi_pmt";
    private static final String TS_MODE_HLS = "hls";
    private static final int BYTES_PER_SUBSAMPLE_ENCRYPTION_ENTRY = 6;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final String MEDIAMETRICS_ELEMENT_SEPARATOR = "|";
    private static final int MEDIAMETRICS_MAX_STRING_SIZE = 200;
    private static final int MEDIAMETRICS_PARAMETER_LIST_MAX_LENGTH;
    /**
     * Intentional error introduced to reported metrics to prevent identification of the parsed
     * media. Note: Increasing this value may cause older hostside CTS tests to fail.
     */
    private static final float MEDIAMETRICS_DITHER = .02f;

    @IntDef(
            value = {
                STATE_READING_SIGNAL_BYTE,
                STATE_READING_INIT_VECTOR,
                STATE_READING_SUBSAMPLE_ENCRYPTION_SIZE,
                STATE_READING_SUBSAMPLE_ENCRYPTION_DATA
            })
    private @interface EncryptionDataReadState {}

    private static final int STATE_READING_SIGNAL_BYTE = 0;
    private static final int STATE_READING_INIT_VECTOR = 1;
    private static final int STATE_READING_SUBSAMPLE_ENCRYPTION_SIZE = 2;
    private static final int STATE_READING_SUBSAMPLE_ENCRYPTION_DATA = 3;

    // Instance creation methods.

    /**
     * Creates an instance backed by the parser with the given {@code name}. The returned instance
     * will attempt parsing without sniffing the content.
     *
     * @param name The name of the parser that will be associated with the created instance.
     * @param outputConsumer The {@link OutputConsumer} to which track data and samples are pushed.
     * @return A new instance.
     * @throws IllegalArgumentException If an invalid name is provided.
     */
    @NonNull
    public static MediaParser createByName(
            @NonNull @ParserName String name, @NonNull OutputConsumer outputConsumer) {
        String[] nameAsArray = new String[] {name};
        assertValidNames(nameAsArray);
        return new MediaParser(outputConsumer, /* createdByName= */ true, name);
    }

    /**
     * Creates an instance whose backing parser will be selected by sniffing the content during the
     * first {@link #advance} call. Parser implementations will sniff the content in order of
     * appearance in {@code parserNames}.
     *
     * @param outputConsumer The {@link OutputConsumer} to which extracted data is output.
     * @param parserNames The names of the parsers to sniff the content with. If empty, a default
     *     array of names is used.
     * @return A new instance.
     */
    @NonNull
    public static MediaParser create(
            @NonNull OutputConsumer outputConsumer, @NonNull @ParserName String... parserNames) {
        assertValidNames(parserNames);
        if (parserNames.length == 0) {
            parserNames = EXTRACTOR_FACTORIES_BY_NAME.keySet().toArray(new String[0]);
        }
        return new MediaParser(outputConsumer, /* createdByName= */ false, parserNames);
    }

    // Misc static methods.

    /**
     * Returns an immutable list with the names of the parsers that are suitable for container
     * formats with the given {@link MediaFormat}.
     *
     * <p>A parser supports a {@link MediaFormat} if the mime type associated with {@link
     * MediaFormat#KEY_MIME} corresponds to the supported container format.
     *
     * @param mediaFormat The {@link MediaFormat} to check support for.
     * @return The parser names that support the given {@code mediaFormat}, or the list of all
     *     parsers available if no container specific format information is provided.
     */
    @NonNull
    @ParserName
    public static List<String> getParserNames(@NonNull MediaFormat mediaFormat) {
        String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        mimeType = mimeType == null ? null : Util.toLowerInvariant(mimeType.trim());
        if (TextUtils.isEmpty(mimeType)) {
            // No MIME type provided. Return all.
            return Collections.unmodifiableList(
                    new ArrayList<>(EXTRACTOR_FACTORIES_BY_NAME.keySet()));
        }
        ArrayList<String> result = new ArrayList<>();
        switch (mimeType) {
            case "video/x-matroska":
            case "audio/x-matroska":
            case "video/x-webm":
            case "audio/x-webm":
                result.add(PARSER_NAME_MATROSKA);
                break;
            case "video/mp4":
            case "audio/mp4":
            case "application/mp4":
                result.add(PARSER_NAME_MP4);
                result.add(PARSER_NAME_FMP4);
                break;
            case "audio/mpeg":
                result.add(PARSER_NAME_MP3);
                break;
            case "audio/aac":
                result.add(PARSER_NAME_ADTS);
                break;
            case "audio/ac3":
                result.add(PARSER_NAME_AC3);
                break;
            case "video/mp2t":
            case "audio/mp2t":
                result.add(PARSER_NAME_TS);
                break;
            case "video/x-flv":
                result.add(PARSER_NAME_FLV);
                break;
            case "video/ogg":
            case "audio/ogg":
            case "application/ogg":
                result.add(PARSER_NAME_OGG);
                break;
            case "video/mp2p":
            case "video/mp1s":
                result.add(PARSER_NAME_PS);
                break;
            case "audio/vnd.wave":
            case "audio/wav":
            case "audio/wave":
            case "audio/x-wav":
                result.add(PARSER_NAME_WAV);
                break;
            case "audio/amr":
                result.add(PARSER_NAME_AMR);
                break;
            case "audio/ac4":
                result.add(PARSER_NAME_AC4);
                break;
            case "audio/flac":
            case "audio/x-flac":
                result.add(PARSER_NAME_FLAC);
                break;
            default:
                // No parsers support the given mime type. Do nothing.
                break;
        }
        return Collections.unmodifiableList(result);
    }

    // Private fields.

    private final Map<String, Object> mParserParameters;
    private final OutputConsumer mOutputConsumer;
    private final String[] mParserNamesPool;
    private final PositionHolder mPositionHolder;
    private final InputReadingDataReader mExoDataReader;
    private final DataReaderAdapter mScratchDataReaderAdapter;
    private final ParsableByteArrayAdapter mScratchParsableByteArrayAdapter;
    @Nullable private final Constructor<DrmInitData.SchemeInitData> mSchemeInitDataConstructor;
    private final ArrayList<Format> mMuxedCaptionFormats;
    private boolean mInBandCryptoInfo;
    private boolean mIncludeSupplementalData;
    private boolean mIgnoreTimestampOffset;
    private boolean mEagerlyExposeTrackType;
    private boolean mExposeDummySeekMap;
    private boolean mExposeChunkIndexAsMediaFormat;
    private String mParserName;
    private Extractor mExtractor;
    private ExtractorInput mExtractorInput;
    private boolean mPendingExtractorInit;
    private long mPendingSeekPosition;
    private long mPendingSeekTimeMicros;
    private boolean mLoggedSchemeInitDataCreationException;
    private boolean mReleased;

    // MediaMetrics fields.
    @Nullable private LogSessionId mLogSessionId;
    private final boolean mCreatedByName;
    private final SparseArray<Format> mTrackFormats;
    private String mLastObservedExceptionName;
    private long mDurationMillis;
    private long mResourceByteCount;

    // Public methods.

    /**
     * Sets parser-specific parameters which allow customizing behavior.
     *
     * <p>Must be called before the first call to {@link #advance}.
     *
     * @param parameterName The name of the parameter to set. See {@code PARAMETER_*} constants for
     *     documentation on possible values.
     * @param value The value to set for the given {@code parameterName}. See {@code PARAMETER_*}
     *     constants for documentation on the expected types.
     * @return This instance, for convenience.
     * @throws IllegalStateException If called after calling {@link #advance} on the same instance.
     */
    @NonNull
    public MediaParser setParameter(
            @NonNull @ParameterName String parameterName, @NonNull Object value) {
        if (mExtractor != null) {
            throw new IllegalStateException(
                    "setParameters() must be called before the first advance() call.");
        }
        Class expectedType = EXPECTED_TYPE_BY_PARAMETER_NAME.get(parameterName);
        // Ignore parameter names that are not contained in the map, in case the client is passing
        // a parameter that is being added in a future version of this library.
        if (expectedType != null && !expectedType.isInstance(value)) {
            throw new IllegalArgumentException(
                    parameterName
                            + " expects a "
                            + expectedType.getSimpleName()
                            + " but a "
                            + value.getClass().getSimpleName()
                            + " was passed.");
        }
        if (PARAMETER_TS_MODE.equals(parameterName)
                && !TS_MODE_SINGLE_PMT.equals(value)
                && !TS_MODE_HLS.equals(value)
                && !TS_MODE_MULTI_PMT.equals(value)) {
            throw new IllegalArgumentException(PARAMETER_TS_MODE + " does not accept: " + value);
        }
        if (PARAMETER_IN_BAND_CRYPTO_INFO.equals(parameterName)) {
            mInBandCryptoInfo = (boolean) value;
        }
        if (PARAMETER_INCLUDE_SUPPLEMENTAL_DATA.equals(parameterName)) {
            mIncludeSupplementalData = (boolean) value;
        }
        if (PARAMETER_IGNORE_TIMESTAMP_OFFSET.equals(parameterName)) {
            mIgnoreTimestampOffset = (boolean) value;
        }
        if (PARAMETER_EAGERLY_EXPOSE_TRACKTYPE.equals(parameterName)) {
            mEagerlyExposeTrackType = (boolean) value;
        }
        if (PARAMETER_EXPOSE_DUMMY_SEEKMAP.equals(parameterName)) {
            mExposeDummySeekMap = (boolean) value;
        }
        if (PARAMETER_EXPOSE_CHUNK_INDEX_AS_MEDIA_FORMAT.equals(parameterName)) {
            mExposeChunkIndexAsMediaFormat = (boolean) value;
        }
        if (PARAMETER_EXPOSE_CAPTION_FORMATS.equals(parameterName)) {
            setMuxedCaptionFormats((List<MediaFormat>) value);
        }
        mParserParameters.put(parameterName, value);
        return this;
    }

    /**
     * Returns whether the given {@code parameterName} is supported by this parser.
     *
     * @param parameterName The parameter name to check support for. One of the {@code PARAMETER_*}
     *     constants.
     * @return Whether the given {@code parameterName} is supported.
     */
    public boolean supportsParameter(@NonNull @ParameterName String parameterName) {
        return EXPECTED_TYPE_BY_PARAMETER_NAME.containsKey(parameterName);
    }

    /**
     * Returns the name of the backing parser implementation.
     *
     * <p>If this instance was creating using {@link #createByName}, the provided name is returned.
     * If this instance was created using {@link #create}, this method will return {@link
     * #PARSER_NAME_UNKNOWN} until the first call to {@link #advance}, after which the name of the
     * backing parser implementation is returned.
     *
     * @return The name of the backing parser implementation, or null if the backing parser
     *     implementation has not yet been selected.
     */
    @NonNull
    @ParserName
    public String getParserName() {
        return mParserName;
    }

    /**
     * Makes progress in the extraction of the input media stream, unless the end of the input has
     * been reached.
     *
     * <p>This method will block until some progress has been made.
     *
     * <p>If this instance was created using {@link #create}, the first call to this method will
     * sniff the content using the selected parser implementations.
     *
     * @param seekableInputReader The {@link SeekableInputReader} from which to obtain the media
     *     container data.
     * @return Whether there is any data left to extract. Returns false if the end of input has been
     *     reached.
     * @throws IOException If an error occurs while reading from the {@link SeekableInputReader}.
     * @throws UnrecognizedInputFormatException If the format cannot be recognized by any of the
     *     underlying parser implementations.
     */
    public boolean advance(@NonNull SeekableInputReader seekableInputReader) throws IOException {
        if (mExtractorInput == null) {
            // TODO: For efficiency, the same implementation should be used, by providing a
            // clearBuffers() method, or similar.
            long resourceLength = seekableInputReader.getLength();
            if (mResourceByteCount == 0) {
                // For resource byte count metric collection, we only take into account the length
                // of the first provided input reader.
                mResourceByteCount = resourceLength;
            }
            mExtractorInput =
                    new DefaultExtractorInput(
                            mExoDataReader, seekableInputReader.getPosition(), resourceLength);
        }
        mExoDataReader.mInputReader = seekableInputReader;

        if (mExtractor == null) {
            mPendingExtractorInit = true;
            if (!mParserName.equals(PARSER_NAME_UNKNOWN)) {
                mExtractor = createExtractor(mParserName);
            } else {
                for (String parserName : mParserNamesPool) {
                    Extractor extractor = createExtractor(parserName);
                    try {
                        if (extractor.sniff(mExtractorInput)) {
                            mParserName = parserName;
                            mExtractor = extractor;
                            mPendingExtractorInit = true;
                            break;
                        }
                    } catch (EOFException e) {
                        // Do nothing.
                    } finally {
                        mExtractorInput.resetPeekPosition();
                    }
                }
                if (mExtractor == null) {
                    UnrecognizedInputFormatException exception =
                            UnrecognizedInputFormatException.createForExtractors(mParserNamesPool);
                    mLastObservedExceptionName = exception.getClass().getName();
                    throw exception;
                }
                return true;
            }
        }

        if (mPendingExtractorInit) {
            if (mExposeDummySeekMap) {
                // We propagate the dummy seek map before initializing the extractor, in case the
                // extractor initialization outputs a seek map.
                mOutputConsumer.onSeekMapFound(SeekMap.DUMMY);
            }
            mExtractor.init(new ExtractorOutputAdapter());
            mPendingExtractorInit = false;
            // We return after initialization to allow clients use any output information before
            // starting actual extraction.
            return true;
        }

        if (isPendingSeek()) {
            mExtractor.seek(mPendingSeekPosition, mPendingSeekTimeMicros);
            removePendingSeek();
        }

        mPositionHolder.position = seekableInputReader.getPosition();
        int result;
        try {
            result = mExtractor.read(mExtractorInput, mPositionHolder);
        } catch (Exception e) {
            mLastObservedExceptionName = e.getClass().getName();
            if (e instanceof ParserException) {
                throw new ParsingException((ParserException) e);
            } else {
                throw e;
            }
        }
        if (result == Extractor.RESULT_END_OF_INPUT) {
            mExtractorInput = null;
            return false;
        }
        if (result == Extractor.RESULT_SEEK) {
            mExtractorInput = null;
            seekableInputReader.seekToPosition(mPositionHolder.position);
        }
        return true;
    }

    /**
     * Seeks within the media container being extracted.
     *
     * <p>{@link SeekPoint SeekPoints} can be obtained from the {@link SeekMap} passed to {@link
     * OutputConsumer#onSeekMapFound(SeekMap)}.
     *
     * <p>Following a call to this method, the {@link InputReader} passed to the next invocation of
     * {@link #advance} must provide data starting from {@link SeekPoint#position} in the stream.
     *
     * @param seekPoint The {@link SeekPoint} to seek to.
     */
    public void seek(@NonNull SeekPoint seekPoint) {
        if (mExtractor == null) {
            mPendingSeekPosition = seekPoint.position;
            mPendingSeekTimeMicros = seekPoint.timeMicros;
        } else {
            mExtractor.seek(seekPoint.position, seekPoint.timeMicros);
        }
    }

    /**
     * Releases any acquired resources.
     *
     * <p>After calling this method, this instance becomes unusable and no other methods should be
     * invoked.
     */
    public void release() {
        mExtractorInput = null;
        mExtractor = null;
        if (mReleased) {
            // Nothing to do.
            return;
        }
        mReleased = true;

        String trackMimeTypes = buildMediaMetricsString(format -> format.sampleMimeType);
        String trackCodecs = buildMediaMetricsString(format -> format.codecs);
        int videoWidth = -1;
        int videoHeight = -1;
        for (int i = 0; i < mTrackFormats.size(); i++) {
            Format format = mTrackFormats.valueAt(i);
            if (format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
                videoWidth = format.width;
                videoHeight = format.height;
                break;
            }
        }

        String alteredParameters =
                String.join(
                        MEDIAMETRICS_ELEMENT_SEPARATOR,
                        mParserParameters.keySet().toArray(new String[0]));
        alteredParameters =
                alteredParameters.substring(
                        0,
                        Math.min(
                                alteredParameters.length(),
                                MEDIAMETRICS_PARAMETER_LIST_MAX_LENGTH));

        nativeSubmitMetrics(
                SdkLevel.isAtLeastS() ? getLogSessionIdStringV31() : "",
                mParserName,
                mCreatedByName,
                String.join(MEDIAMETRICS_ELEMENT_SEPARATOR, mParserNamesPool),
                mLastObservedExceptionName,
                addDither(mResourceByteCount),
                addDither(mDurationMillis),
                trackMimeTypes,
                trackCodecs,
                alteredParameters,
                videoWidth,
                videoHeight);
    }

    @RequiresApi(31)
    public void setLogSessionId(@NonNull LogSessionId logSessionId) {
        this.mLogSessionId = Objects.requireNonNull(logSessionId);
    }

    @RequiresApi(31)
    @NonNull
    public LogSessionId getLogSessionId() {
        return mLogSessionId != null ? mLogSessionId : LogSessionId.LOG_SESSION_ID_NONE;
    }

    // Private methods.

    private MediaParser(
            OutputConsumer outputConsumer, boolean createdByName, String... parserNamesPool) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new UnsupportedOperationException("Android version must be R or greater.");
        }
        mParserParameters = new HashMap<>();
        mOutputConsumer = outputConsumer;
        mParserNamesPool = parserNamesPool;
        mCreatedByName = createdByName;
        mParserName = createdByName ? parserNamesPool[0] : PARSER_NAME_UNKNOWN;
        mPositionHolder = new PositionHolder();
        mExoDataReader = new InputReadingDataReader();
        removePendingSeek();
        mScratchDataReaderAdapter = new DataReaderAdapter();
        mScratchParsableByteArrayAdapter = new ParsableByteArrayAdapter();
        mSchemeInitDataConstructor = getSchemeInitDataConstructor();
        mMuxedCaptionFormats = new ArrayList<>();

        // MediaMetrics.
        mTrackFormats = new SparseArray<>();
        mLastObservedExceptionName = "";
        mDurationMillis = -1;
    }

    private String buildMediaMetricsString(Function<Format, String> formatFieldGetter) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < mTrackFormats.size(); i++) {
            if (i > 0) {
                stringBuilder.append(MEDIAMETRICS_ELEMENT_SEPARATOR);
            }
            String fieldValue = formatFieldGetter.apply(mTrackFormats.valueAt(i));
            stringBuilder.append(fieldValue != null ? fieldValue : "");
        }
        return stringBuilder.substring(
                0, Math.min(stringBuilder.length(), MEDIAMETRICS_MAX_STRING_SIZE));
    }

    private void setMuxedCaptionFormats(List<MediaFormat> mediaFormats) {
        mMuxedCaptionFormats.clear();
        for (MediaFormat mediaFormat : mediaFormats) {
            mMuxedCaptionFormats.add(toExoPlayerCaptionFormat(mediaFormat));
        }
    }

    private boolean isPendingSeek() {
        return mPendingSeekPosition >= 0;
    }

    private void removePendingSeek() {
        mPendingSeekPosition = -1;
        mPendingSeekTimeMicros = -1;
    }

    private Extractor createExtractor(String parserName) {
        int flags = 0;
        TimestampAdjuster timestampAdjuster = null;
        if (mIgnoreTimestampOffset) {
            timestampAdjuster = new TimestampAdjuster(TimestampAdjuster.DO_NOT_OFFSET);
        }
        switch (parserName) {
            case PARSER_NAME_MATROSKA:
                flags =
                        getBooleanParameter(PARAMETER_MATROSKA_DISABLE_CUES_SEEKING)
                                ? MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES
                                : 0;
                return new MatroskaExtractor(flags);
            case PARSER_NAME_FMP4:
                flags |=
                        getBooleanParameter(PARAMETER_EXPOSE_EMSG_TRACK)
                                ? FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_MP4_IGNORE_EDIT_LISTS)
                                ? FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_MP4_IGNORE_TFDT_BOX)
                                ? FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_TFDT_BOX
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_MP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES)
                                ? FragmentedMp4Extractor
                                        .FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME
                                : 0;
                return new FragmentedMp4Extractor(
                        flags,
                        timestampAdjuster,
                        /* sideloadedTrack= */ null,
                        mMuxedCaptionFormats);
            case PARSER_NAME_MP4:
                flags |=
                        getBooleanParameter(PARAMETER_MP4_IGNORE_EDIT_LISTS)
                                ? Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS
                                : 0;
                return new Mp4Extractor(flags);
            case PARSER_NAME_MP3:
                flags |=
                        getBooleanParameter(PARAMETER_MP3_DISABLE_ID3)
                                ? Mp3Extractor.FLAG_DISABLE_ID3_METADATA
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_MP3_ENABLE_CBR_SEEKING)
                                ? Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                                : 0;
                // TODO: Add index seeking once we update the ExoPlayer version.
                return new Mp3Extractor(flags);
            case PARSER_NAME_ADTS:
                flags |=
                        getBooleanParameter(PARAMETER_ADTS_ENABLE_CBR_SEEKING)
                                ? AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                                : 0;
                return new AdtsExtractor(flags);
            case PARSER_NAME_AC3:
                return new Ac3Extractor();
            case PARSER_NAME_TS:
                flags |=
                        getBooleanParameter(PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES)
                                ? DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_TS_DETECT_ACCESS_UNITS)
                                ? DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                                ? DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_TS_IGNORE_AAC_STREAM)
                                ? DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_TS_IGNORE_AVC_STREAM)
                                ? DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM)
                                ? DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                                : 0;
                flags |=
                        getBooleanParameter(PARAMETER_OVERRIDE_IN_BAND_CAPTION_DECLARATIONS)
                                ? DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
                                : 0;
                String tsMode = getStringParameter(PARAMETER_TS_MODE, TS_MODE_SINGLE_PMT);
                int hlsMode =
                        TS_MODE_SINGLE_PMT.equals(tsMode)
                                ? TsExtractor.MODE_SINGLE_PMT
                                : TS_MODE_HLS.equals(tsMode)
                                        ? TsExtractor.MODE_HLS
                                        : TsExtractor.MODE_MULTI_PMT;
                return new TsExtractor(
                        hlsMode,
                        timestampAdjuster != null
                                ? timestampAdjuster
                                : new TimestampAdjuster(/* firstSampleTimestampUs= */ 0),
                        new DefaultTsPayloadReaderFactory(flags, mMuxedCaptionFormats));
            case PARSER_NAME_FLV:
                return new FlvExtractor();
            case PARSER_NAME_OGG:
                return new OggExtractor();
            case PARSER_NAME_PS:
                return new PsExtractor();
            case PARSER_NAME_WAV:
                return new WavExtractor();
            case PARSER_NAME_AMR:
                flags |=
                        getBooleanParameter(PARAMETER_AMR_ENABLE_CBR_SEEKING)
                                ? AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
                                : 0;
                return new AmrExtractor(flags);
            case PARSER_NAME_AC4:
                return new Ac4Extractor();
            case PARSER_NAME_FLAC:
                flags |=
                        getBooleanParameter(PARAMETER_FLAC_DISABLE_ID3)
                                ? FlacExtractor.FLAG_DISABLE_ID3_METADATA
                                : 0;
                return new FlacExtractor(flags);
            default:
                // Should never happen.
                throw new IllegalStateException("Unexpected attempt to create: " + parserName);
        }
    }

    private boolean getBooleanParameter(String name) {
        return (boolean) mParserParameters.getOrDefault(name, false);
    }

    private String getStringParameter(String name, String defaultValue) {
        return (String) mParserParameters.getOrDefault(name, defaultValue);
    }

    @RequiresApi(31)
    private String getLogSessionIdStringV31() {
        return mLogSessionId != null ? mLogSessionId.getStringId() : "";
    }

    // Private classes.

    private static final class InputReadingDataReader implements DataReader {

        public InputReader mInputReader;

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return mInputReader.read(buffer, offset, readLength);
        }
    }

    private final class MediaParserDrmInitData extends DrmInitData {

        private final SchemeInitData[] mSchemeDatas;

        private MediaParserDrmInitData(com.google.android.exoplayer2.drm.DrmInitData exoDrmInitData)
                throws IllegalAccessException, InstantiationException, InvocationTargetException {
            mSchemeDatas = new SchemeInitData[exoDrmInitData.schemeDataCount];
            for (int i = 0; i < mSchemeDatas.length; i++) {
                mSchemeDatas[i] = toFrameworkSchemeInitData(exoDrmInitData.get(i));
            }
        }

        @Override
        @Nullable
        public SchemeInitData get(UUID schemeUuid) {
            for (SchemeInitData schemeInitData : mSchemeDatas) {
                if (schemeInitData.uuid.equals(schemeUuid)) {
                    return schemeInitData;
                }
            }
            return null;
        }

        @Override
        public SchemeInitData getSchemeInitDataAt(int index) {
            return mSchemeDatas[index];
        }

        @Override
        public int getSchemeInitDataCount() {
            return mSchemeDatas.length;
        }

        private DrmInitData.SchemeInitData toFrameworkSchemeInitData(SchemeData exoSchemeData)
                throws IllegalAccessException, InvocationTargetException, InstantiationException {
            return mSchemeInitDataConstructor.newInstance(
                    exoSchemeData.uuid, exoSchemeData.mimeType, exoSchemeData.data);
        }
    }

    private final class ExtractorOutputAdapter implements ExtractorOutput {

        private final SparseArray<TrackOutput> mTrackOutputAdapters;
        private boolean mTracksEnded;

        private ExtractorOutputAdapter() {
            mTrackOutputAdapters = new SparseArray<>();
        }

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput trackOutput = mTrackOutputAdapters.get(id);
            if (trackOutput == null) {
                int trackIndex = mTrackOutputAdapters.size();
                trackOutput = new TrackOutputAdapter(trackIndex);
                mTrackOutputAdapters.put(id, trackOutput);
                if (mEagerlyExposeTrackType) {
                    MediaFormat mediaFormat = new MediaFormat();
                    mediaFormat.setString("track-type-string", toTypeString(type));
                    mOutputConsumer.onTrackDataFound(
                            trackIndex, new TrackData(mediaFormat, /* drmInitData= */ null));
                }
            }
            return trackOutput;
        }

        @Override
        public void endTracks() {
            mOutputConsumer.onTrackCountFound(mTrackOutputAdapters.size());
        }

        @Override
        public void seekMap(com.google.android.exoplayer2.extractor.SeekMap exoplayerSeekMap) {
            long durationUs = exoplayerSeekMap.getDurationUs();
            if (durationUs != C.TIME_UNSET) {
                mDurationMillis = C.usToMs(durationUs);
            }
            if (mExposeChunkIndexAsMediaFormat && exoplayerSeekMap instanceof ChunkIndex) {
                ChunkIndex chunkIndex = (ChunkIndex) exoplayerSeekMap;
                MediaFormat mediaFormat = new MediaFormat();
                mediaFormat.setByteBuffer("chunk-index-int-sizes", toByteBuffer(chunkIndex.sizes));
                mediaFormat.setByteBuffer(
                        "chunk-index-long-offsets", toByteBuffer(chunkIndex.offsets));
                mediaFormat.setByteBuffer(
                        "chunk-index-long-us-durations", toByteBuffer(chunkIndex.durationsUs));
                mediaFormat.setByteBuffer(
                        "chunk-index-long-us-times", toByteBuffer(chunkIndex.timesUs));
                mOutputConsumer.onTrackDataFound(
                        /* trackIndex= */ 0, new TrackData(mediaFormat, /* drmInitData= */ null));
            }
            mOutputConsumer.onSeekMapFound(new SeekMap(exoplayerSeekMap));
        }
    }

    private class TrackOutputAdapter implements TrackOutput {

        private final int mTrackIndex;

        private CryptoInfo mLastOutputCryptoInfo;
        private CryptoInfo.Pattern mLastOutputEncryptionPattern;
        private CryptoData mLastReceivedCryptoData;

        @EncryptionDataReadState private int mEncryptionDataReadState;
        private int mEncryptionDataSizeToSubtractFromSampleDataSize;
        private int mEncryptionVectorSize;
        private byte[] mScratchIvSpace;
        private int mSubsampleEncryptionDataSize;
        private int[] mScratchSubsampleEncryptedBytesCount;
        private int[] mScratchSubsampleClearBytesCount;
        private boolean mHasSubsampleEncryptionData;
        private int mSkippedSupplementalDataBytes;

        private TrackOutputAdapter(int trackIndex) {
            mTrackIndex = trackIndex;
            mScratchIvSpace = new byte[16]; // Size documented in CryptoInfo.
            mScratchSubsampleEncryptedBytesCount = new int[32];
            mScratchSubsampleClearBytesCount = new int[32];
            mEncryptionDataReadState = STATE_READING_SIGNAL_BYTE;
            mLastOutputEncryptionPattern =
                    new CryptoInfo.Pattern(/* blocksToEncrypt= */ 0, /* blocksToSkip= */ 0);
        }

        @Override
        public void format(Format format) {
            mTrackFormats.put(mTrackIndex, format);
            mOutputConsumer.onTrackDataFound(
                    mTrackIndex,
                    new TrackData(
                            toMediaFormat(format), toFrameworkDrmInitData(format.drmInitData)));
        }

        @Override
        public int sampleData(
                DataReader input,
                int length,
                boolean allowEndOfInput,
                @SampleDataPart int sampleDataPart)
                throws IOException {
            mScratchDataReaderAdapter.setDataReader(input, length);
            long positionBeforeReading = mScratchDataReaderAdapter.getPosition();
            mOutputConsumer.onSampleDataFound(mTrackIndex, mScratchDataReaderAdapter);
            return (int) (mScratchDataReaderAdapter.getPosition() - positionBeforeReading);
        }

        @Override
        public void sampleData(
                ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
            if (sampleDataPart == SAMPLE_DATA_PART_ENCRYPTION && !mInBandCryptoInfo) {
                while (length > 0) {
                    switch (mEncryptionDataReadState) {
                        case STATE_READING_SIGNAL_BYTE:
                            int encryptionSignalByte = data.readUnsignedByte();
                            length--;
                            mHasSubsampleEncryptionData = ((encryptionSignalByte >> 7) & 1) != 0;
                            mEncryptionVectorSize = encryptionSignalByte & 0x7F;
                            mEncryptionDataSizeToSubtractFromSampleDataSize =
                                    mEncryptionVectorSize + 1; // Signal byte.
                            mEncryptionDataReadState = STATE_READING_INIT_VECTOR;
                            break;
                        case STATE_READING_INIT_VECTOR:
                            Arrays.fill(mScratchIvSpace, (byte) 0); // Ensure 0-padding.
                            data.readBytes(mScratchIvSpace, /* offset= */ 0, mEncryptionVectorSize);
                            length -= mEncryptionVectorSize;
                            if (mHasSubsampleEncryptionData) {
                                mEncryptionDataReadState = STATE_READING_SUBSAMPLE_ENCRYPTION_SIZE;
                            } else {
                                mSubsampleEncryptionDataSize = 0;
                                mEncryptionDataReadState = STATE_READING_SIGNAL_BYTE;
                            }
                            break;
                        case STATE_READING_SUBSAMPLE_ENCRYPTION_SIZE:
                            mSubsampleEncryptionDataSize = data.readUnsignedShort();
                            if (mScratchSubsampleClearBytesCount.length
                                    < mSubsampleEncryptionDataSize) {
                                mScratchSubsampleClearBytesCount =
                                        new int[mSubsampleEncryptionDataSize];
                                mScratchSubsampleEncryptedBytesCount =
                                        new int[mSubsampleEncryptionDataSize];
                            }
                            length -= 2;
                            mEncryptionDataSizeToSubtractFromSampleDataSize +=
                                    2
                                            + mSubsampleEncryptionDataSize
                                                    * BYTES_PER_SUBSAMPLE_ENCRYPTION_ENTRY;
                            mEncryptionDataReadState = STATE_READING_SUBSAMPLE_ENCRYPTION_DATA;
                            break;
                        case STATE_READING_SUBSAMPLE_ENCRYPTION_DATA:
                            for (int i = 0; i < mSubsampleEncryptionDataSize; i++) {
                                mScratchSubsampleClearBytesCount[i] = data.readUnsignedShort();
                                mScratchSubsampleEncryptedBytesCount[i] = data.readInt();
                            }
                            length -=
                                    mSubsampleEncryptionDataSize
                                            * BYTES_PER_SUBSAMPLE_ENCRYPTION_ENTRY;
                            mEncryptionDataReadState = STATE_READING_SIGNAL_BYTE;
                            if (length != 0) {
                                throw new IllegalStateException();
                            }
                            break;
                        default:
                            // Never happens.
                            throw new IllegalStateException();
                    }
                }
            } else if (sampleDataPart == SAMPLE_DATA_PART_SUPPLEMENTAL
                    && !mIncludeSupplementalData) {
                mSkippedSupplementalDataBytes += length;
                data.skipBytes(length);
            } else {
                outputSampleData(data, length);
            }
        }

        @Override
        public void sampleMetadata(
                long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
            size -= mSkippedSupplementalDataBytes;
            mSkippedSupplementalDataBytes = 0;
            mOutputConsumer.onSampleCompleted(
                    mTrackIndex,
                    timeUs,
                    getMediaParserFlags(flags),
                    size - mEncryptionDataSizeToSubtractFromSampleDataSize,
                    offset,
                    getPopulatedCryptoInfo(cryptoData));
            mEncryptionDataReadState = STATE_READING_SIGNAL_BYTE;
            mEncryptionDataSizeToSubtractFromSampleDataSize = 0;
        }

        @Nullable
        private CryptoInfo getPopulatedCryptoInfo(@Nullable CryptoData cryptoData) {
            if (cryptoData == null) {
                // The sample is not encrypted.
                return null;
            } else if (mInBandCryptoInfo) {
                if (cryptoData != mLastReceivedCryptoData) {
                    mLastOutputCryptoInfo =
                            createNewCryptoInfoAndPopulateWithCryptoData(cryptoData);
                    // We are using in-band crypto info, so the IV will be ignored. But we prevent
                    // it from being null because toString assumes it non-null.
                    mLastOutputCryptoInfo.iv = EMPTY_BYTE_ARRAY;
                }
            } else /* We must populate the full CryptoInfo. */ {
                // CryptoInfo.pattern is not accessible to the user, so the user needs to feed
                // this CryptoInfo directly to MediaCodec. We need to create a new CryptoInfo per
                // sample because of per-sample initialization vector changes.
                CryptoInfo newCryptoInfo = createNewCryptoInfoAndPopulateWithCryptoData(cryptoData);
                newCryptoInfo.iv = Arrays.copyOf(mScratchIvSpace, mScratchIvSpace.length);
                boolean canReuseSubsampleInfo =
                        mLastOutputCryptoInfo != null
                                && mLastOutputCryptoInfo.numSubSamples
                                        == mSubsampleEncryptionDataSize;
                for (int i = 0; i < mSubsampleEncryptionDataSize && canReuseSubsampleInfo; i++) {
                    canReuseSubsampleInfo =
                            mLastOutputCryptoInfo.numBytesOfClearData[i]
                                            == mScratchSubsampleClearBytesCount[i]
                                    && mLastOutputCryptoInfo.numBytesOfEncryptedData[i]
                                            == mScratchSubsampleEncryptedBytesCount[i];
                }
                newCryptoInfo.numSubSamples = mSubsampleEncryptionDataSize;
                if (canReuseSubsampleInfo) {
                    newCryptoInfo.numBytesOfClearData = mLastOutputCryptoInfo.numBytesOfClearData;
                    newCryptoInfo.numBytesOfEncryptedData =
                            mLastOutputCryptoInfo.numBytesOfEncryptedData;
                } else {
                    newCryptoInfo.numBytesOfClearData =
                            Arrays.copyOf(
                                    mScratchSubsampleClearBytesCount, mSubsampleEncryptionDataSize);
                    newCryptoInfo.numBytesOfEncryptedData =
                            Arrays.copyOf(
                                    mScratchSubsampleEncryptedBytesCount,
                                    mSubsampleEncryptionDataSize);
                }
                mLastOutputCryptoInfo = newCryptoInfo;
            }
            mLastReceivedCryptoData = cryptoData;
            return mLastOutputCryptoInfo;
        }

        private CryptoInfo createNewCryptoInfoAndPopulateWithCryptoData(CryptoData cryptoData) {
            CryptoInfo cryptoInfo = new CryptoInfo();
            cryptoInfo.key = cryptoData.encryptionKey;
            cryptoInfo.mode = cryptoData.cryptoMode;
            if (cryptoData.clearBlocks != mLastOutputEncryptionPattern.getSkipBlocks()
                    || cryptoData.encryptedBlocks
                            != mLastOutputEncryptionPattern.getEncryptBlocks()) {
                mLastOutputEncryptionPattern =
                        new CryptoInfo.Pattern(cryptoData.encryptedBlocks, cryptoData.clearBlocks);
            }
            cryptoInfo.setPattern(mLastOutputEncryptionPattern);
            return cryptoInfo;
        }

        private void outputSampleData(ParsableByteArray data, int length) {
            mScratchParsableByteArrayAdapter.resetWithByteArray(data, length);
            try {
                // Read all bytes from data. ExoPlayer extractors expect all sample data to be
                // consumed by TrackOutput implementations when passing a ParsableByteArray.
                while (mScratchParsableByteArrayAdapter.getLength() > 0) {
                    mOutputConsumer.onSampleDataFound(
                            mTrackIndex, mScratchParsableByteArrayAdapter);
                }
            } catch (IOException e) {
                // Unexpected.
                throw new RuntimeException(e);
            }
        }
    }

    private static final class DataReaderAdapter implements InputReader {

        private DataReader mDataReader;
        private int mCurrentPosition;
        private long mLength;

        public void setDataReader(DataReader dataReader, long length) {
            mDataReader = dataReader;
            mCurrentPosition = 0;
            mLength = length;
        }

        // Input implementation.

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            int readBytes = 0;
            readBytes = mDataReader.read(buffer, offset, readLength);
            mCurrentPosition += readBytes;
            return readBytes;
        }

        @Override
        public long getPosition() {
            return mCurrentPosition;
        }

        @Override
        public long getLength() {
            return mLength - mCurrentPosition;
        }
    }

    private static final class ParsableByteArrayAdapter implements InputReader {

        private ParsableByteArray mByteArray;
        private long mLength;
        private int mCurrentPosition;

        public void resetWithByteArray(ParsableByteArray byteArray, long length) {
            mByteArray = byteArray;
            mCurrentPosition = 0;
            mLength = length;
        }

        // Input implementation.

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            mByteArray.readBytes(buffer, offset, readLength);
            mCurrentPosition += readLength;
            return readLength;
        }

        @Override
        public long getPosition() {
            return mCurrentPosition;
        }

        @Override
        public long getLength() {
            return mLength - mCurrentPosition;
        }
    }

    private static final class DummyExoPlayerSeekMap
            implements com.google.android.exoplayer2.extractor.SeekMap {

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public long getDurationUs() {
            return C.TIME_UNSET;
        }

        @Override
        public SeekPoints getSeekPoints(long timeUs) {
            com.google.android.exoplayer2.extractor.SeekPoint seekPoint =
                    new com.google.android.exoplayer2.extractor.SeekPoint(
                            timeUs, /* position= */ 0);
            return new SeekPoints(seekPoint, seekPoint);
        }
    }

    /** Creates extractor instances. */
    private interface ExtractorFactory {

        /** Returns a new extractor instance. */
        Extractor createInstance();
    }

    // Private static methods.

    private static Format toExoPlayerCaptionFormat(MediaFormat mediaFormat) {
        Format.Builder formatBuilder =
                new Format.Builder().setSampleMimeType(mediaFormat.getString(MediaFormat.KEY_MIME));
        if (mediaFormat.containsKey(MediaFormat.KEY_CAPTION_SERVICE_NUMBER)) {
            formatBuilder.setAccessibilityChannel(
                    mediaFormat.getInteger(MediaFormat.KEY_CAPTION_SERVICE_NUMBER));
        }
        return formatBuilder.build();
    }

    private static MediaFormat toMediaFormat(Format format) {
        MediaFormat result = new MediaFormat();
        setOptionalMediaFormatInt(result, MediaFormat.KEY_BIT_RATE, format.bitrate);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_CHANNEL_COUNT, format.channelCount);

        ColorInfo colorInfo = format.colorInfo;
        if (colorInfo != null) {
            setOptionalMediaFormatInt(
                    result, MediaFormat.KEY_COLOR_TRANSFER, colorInfo.colorTransfer);
            setOptionalMediaFormatInt(result, MediaFormat.KEY_COLOR_RANGE, colorInfo.colorRange);
            setOptionalMediaFormatInt(result, MediaFormat.KEY_COLOR_STANDARD, colorInfo.colorSpace);

            if (format.colorInfo.hdrStaticInfo != null) {
                result.setByteBuffer(
                        MediaFormat.KEY_HDR_STATIC_INFO,
                        ByteBuffer.wrap(format.colorInfo.hdrStaticInfo));
            }
        }

        setOptionalMediaFormatString(result, MediaFormat.KEY_MIME, format.sampleMimeType);
        setOptionalMediaFormatString(result, MediaFormat.KEY_CODECS_STRING, format.codecs);
        if (format.frameRate != Format.NO_VALUE) {
            result.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate);
        }
        setOptionalMediaFormatInt(result, MediaFormat.KEY_WIDTH, format.width);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_HEIGHT, format.height);

        List<byte[]> initData = format.initializationData;
        for (int i = 0; i < initData.size(); i++) {
            result.setByteBuffer("csd-" + i, ByteBuffer.wrap(initData.get(i)));
        }
        setPcmEncoding(format, result);
        setOptionalMediaFormatString(result, MediaFormat.KEY_LANGUAGE, format.language);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_ROTATION, format.rotationDegrees);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_SAMPLE_RATE, format.sampleRate);
        setOptionalMediaFormatInt(
                result, MediaFormat.KEY_CAPTION_SERVICE_NUMBER, format.accessibilityChannel);

        int selectionFlags = format.selectionFlags;
        result.setInteger(
                MediaFormat.KEY_IS_AUTOSELECT, selectionFlags & C.SELECTION_FLAG_AUTOSELECT);
        result.setInteger(MediaFormat.KEY_IS_DEFAULT, selectionFlags & C.SELECTION_FLAG_DEFAULT);
        result.setInteger(
                MediaFormat.KEY_IS_FORCED_SUBTITLE, selectionFlags & C.SELECTION_FLAG_FORCED);

        setOptionalMediaFormatInt(result, MediaFormat.KEY_ENCODER_DELAY, format.encoderDelay);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_ENCODER_PADDING, format.encoderPadding);

        if (format.pixelWidthHeightRatio != Format.NO_VALUE && format.pixelWidthHeightRatio != 0) {
            int parWidth = 1;
            int parHeight = 1;
            if (format.pixelWidthHeightRatio < 1.0f) {
                parHeight = 1 << 30;
                parWidth = (int) (format.pixelWidthHeightRatio * parHeight);
            } else if (format.pixelWidthHeightRatio > 1.0f) {
                parWidth = 1 << 30;
                parHeight = (int) (parWidth / format.pixelWidthHeightRatio);
            }
            result.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, parWidth);
            result.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, parHeight);
            result.setFloat("pixel-width-height-ratio-float", format.pixelWidthHeightRatio);
        }
        if (format.drmInitData != null) {
            // The crypto mode is propagated along with sample metadata. We also include it in the
            // format for convenient use from ExoPlayer.
            result.setString("crypto-mode-fourcc", format.drmInitData.schemeType);
        }
        if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE) {
            result.setLong("subsample-offset-us-long", format.subsampleOffsetUs);
        }
        // LACK OF SUPPORT FOR:
        //    format.id;
        //    format.metadata;
        //    format.stereoMode;
        return result;
    }

    private static ByteBuffer toByteBuffer(long[] longArray) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(longArray.length * Long.BYTES);
        for (long element : longArray) {
            byteBuffer.putLong(element);
        }
        byteBuffer.flip();
        return byteBuffer;
    }

    private static ByteBuffer toByteBuffer(int[] intArray) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(intArray.length * Integer.BYTES);
        for (int element : intArray) {
            byteBuffer.putInt(element);
        }
        byteBuffer.flip();
        return byteBuffer;
    }

    private static String toTypeString(int type) {
        switch (type) {
            case C.TRACK_TYPE_VIDEO:
                return "video";
            case C.TRACK_TYPE_AUDIO:
                return "audio";
            case C.TRACK_TYPE_TEXT:
                return "text";
            case C.TRACK_TYPE_METADATA:
                return "metadata";
            default:
                return "unknown";
        }
    }

    private static void setPcmEncoding(Format format, MediaFormat result) {
        int exoPcmEncoding = format.pcmEncoding;
        setOptionalMediaFormatInt(result, "exo-pcm-encoding", format.pcmEncoding);
        int mediaFormatPcmEncoding;
        switch (exoPcmEncoding) {
            case C.ENCODING_PCM_8BIT:
                mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_8BIT;
                break;
            case C.ENCODING_PCM_16BIT:
                mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
                break;
            case C.ENCODING_PCM_FLOAT:
                mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_FLOAT;
                break;
            default:
                // No matching value. Do nothing.
                return;
        }
        result.setInteger(MediaFormat.KEY_PCM_ENCODING, mediaFormatPcmEncoding);
    }

    private static void setOptionalMediaFormatInt(MediaFormat mediaFormat, String key, int value) {
        if (value != Format.NO_VALUE) {
            mediaFormat.setInteger(key, value);
        }
    }

    private static void setOptionalMediaFormatString(
            MediaFormat mediaFormat, String key, @Nullable String value) {
        if (value != null) {
            mediaFormat.setString(key, value);
        }
    }

    private DrmInitData toFrameworkDrmInitData(
            com.google.android.exoplayer2.drm.DrmInitData exoDrmInitData) {
        try {
            return exoDrmInitData != null && mSchemeInitDataConstructor != null
                    ? new MediaParserDrmInitData(exoDrmInitData)
                    : null;
        } catch (Throwable e) {
            if (!mLoggedSchemeInitDataCreationException) {
                mLoggedSchemeInitDataCreationException = true;
                Log.e(TAG, "Unable to create SchemeInitData instance.");
            }
            return null;
        }
    }

    /** Returns a new {@link SeekPoint} equivalent to the given {@code exoPlayerSeekPoint}. */
    private static SeekPoint toSeekPoint(
            com.google.android.exoplayer2.extractor.SeekPoint exoPlayerSeekPoint) {
        return new SeekPoint(exoPlayerSeekPoint.timeUs, exoPlayerSeekPoint.position);
    }

    /**
     * Introduces random error to the given metric value in order to prevent the identification of
     * the parsed media.
     */
    private static long addDither(long value) {
        // Generate a random in [0, 1].
        double randomDither = ThreadLocalRandom.current().nextFloat();
        // Clamp the random number to [0, 2 * MEDIAMETRICS_DITHER].
        randomDither *= 2 * MEDIAMETRICS_DITHER;
        // Translate the random number to [1 - MEDIAMETRICS_DITHER, 1 + MEDIAMETRICS_DITHER].
        randomDither += 1 - MEDIAMETRICS_DITHER;
        return value != -1 ? (long) (value * randomDither) : -1;
    }

    private static void assertValidNames(@NonNull String[] names) {
        for (String name : names) {
            if (!EXTRACTOR_FACTORIES_BY_NAME.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Invalid extractor name: "
                                + name
                                + ". Supported parsers are: "
                                + TextUtils.join(", ", EXTRACTOR_FACTORIES_BY_NAME.keySet())
                                + ".");
            }
        }
    }

    private int getMediaParserFlags(int flags) {
        @SampleFlags int result = 0;
        result |= (flags & C.BUFFER_FLAG_ENCRYPTED) != 0 ? SAMPLE_FLAG_ENCRYPTED : 0;
        result |= (flags & C.BUFFER_FLAG_KEY_FRAME) != 0 ? SAMPLE_FLAG_KEY_FRAME : 0;
        result |= (flags & C.BUFFER_FLAG_DECODE_ONLY) != 0 ? SAMPLE_FLAG_DECODE_ONLY : 0;
        result |=
                (flags & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) != 0 && mIncludeSupplementalData
                        ? SAMPLE_FLAG_HAS_SUPPLEMENTAL_DATA
                        : 0;
        result |= (flags & C.BUFFER_FLAG_LAST_SAMPLE) != 0 ? SAMPLE_FLAG_LAST_SAMPLE : 0;
        return result;
    }

    @Nullable
    private static Constructor<DrmInitData.SchemeInitData> getSchemeInitDataConstructor() {
        // TODO: Use constructor statically when available.
        Constructor<DrmInitData.SchemeInitData> constructor;
        try {
            return DrmInitData.SchemeInitData.class.getConstructor(
                    UUID.class, String.class, byte[].class);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to get SchemeInitData constructor.");
            return null;
        }
    }

    // Native methods.

    private native void nativeSubmitMetrics(
            String logSessionId,
            String parserName,
            boolean createdByName,
            String parserPool,
            String lastObservedExceptionName,
            long resourceByteCount,
            long durationMillis,
            String trackMimeTypes,
            String trackCodecs,
            String alteredParameters,
            int videoWidth,
            int videoHeight);

    // Static initialization.

    static {
        System.loadLibrary(JNI_LIBRARY_NAME);

        // Using a LinkedHashMap to keep the insertion order when iterating over the keys.
        LinkedHashMap<String, ExtractorFactory> extractorFactoriesByName = new LinkedHashMap<>();
        // Parsers are ordered to match ExoPlayer's DefaultExtractorsFactory extractor ordering,
        // which in turn aims to minimize the chances of incorrect extractor selections.
        extractorFactoriesByName.put(PARSER_NAME_MATROSKA, MatroskaExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_FMP4, FragmentedMp4Extractor::new);
        extractorFactoriesByName.put(PARSER_NAME_MP4, Mp4Extractor::new);
        extractorFactoriesByName.put(PARSER_NAME_MP3, Mp3Extractor::new);
        extractorFactoriesByName.put(PARSER_NAME_ADTS, AdtsExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_AC3, Ac3Extractor::new);
        extractorFactoriesByName.put(PARSER_NAME_TS, TsExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_FLV, FlvExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_OGG, OggExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_PS, PsExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_WAV, WavExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_AMR, AmrExtractor::new);
        extractorFactoriesByName.put(PARSER_NAME_AC4, Ac4Extractor::new);
        extractorFactoriesByName.put(PARSER_NAME_FLAC, FlacExtractor::new);
        EXTRACTOR_FACTORIES_BY_NAME = Collections.unmodifiableMap(extractorFactoriesByName);

        HashMap<String, Class> expectedTypeByParameterName = new HashMap<>();
        expectedTypeByParameterName.put(PARAMETER_ADTS_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_AMR_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_FLAC_DISABLE_ID3, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP4_IGNORE_EDIT_LISTS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP4_IGNORE_TFDT_BOX, Boolean.class);
        expectedTypeByParameterName.put(
                PARAMETER_MP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MATROSKA_DISABLE_CUES_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_DISABLE_ID3, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_ENABLE_INDEX_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_MODE, String.class);
        expectedTypeByParameterName.put(PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_AAC_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_AVC_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_DETECT_ACCESS_UNITS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_IN_BAND_CRYPTO_INFO, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_INCLUDE_SUPPLEMENTAL_DATA, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_IGNORE_TIMESTAMP_OFFSET, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_EAGERLY_EXPOSE_TRACKTYPE, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_EXPOSE_DUMMY_SEEKMAP, Boolean.class);
        expectedTypeByParameterName.put(
                PARAMETER_EXPOSE_CHUNK_INDEX_AS_MEDIA_FORMAT, Boolean.class);
        expectedTypeByParameterName.put(
                PARAMETER_OVERRIDE_IN_BAND_CAPTION_DECLARATIONS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_EXPOSE_EMSG_TRACK, Boolean.class);
        // We do not check PARAMETER_EXPOSE_CAPTION_FORMATS here, and we do it in setParameters
        // instead. Checking that the value is a List is insufficient to catch wrong parameter
        // value types.
        int sumOfParameterNameLengths =
                expectedTypeByParameterName.keySet().stream()
                        .map(String::length)
                        .reduce(0, Integer::sum);
        sumOfParameterNameLengths += PARAMETER_EXPOSE_CAPTION_FORMATS.length();
        // Add space for any required separators.
        MEDIAMETRICS_PARAMETER_LIST_MAX_LENGTH =
                sumOfParameterNameLengths + expectedTypeByParameterName.size();

        EXPECTED_TYPE_BY_PARAMETER_NAME = Collections.unmodifiableMap(expectedTypeByParameterName);
    }
}
