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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Pair;
import android.util.SparseArray;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
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
import com.google.android.exoplayer2.extractor.ts.PsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.wav.WavExtractor;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.video.ColorInfo;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *     public void onSeekMap(int i, &#64;NonNull MediaFormat mediaFormat) {
 *       // Do nothing.
 *     }
 *
 *     &#64;Override
 *     public void onTrackData(int i, &#64;NonNull TrackData trackData) {
 *       MediaFormat mediaFormat = trackData.mediaFormat;
 *       if (videoTrackIndex == -1 &&
 *           mediaFormat
 *               .getString(MediaFormat.KEY_MIME, &#47;* defaultValue= *&#47; "")
 *               .startsWith("video/")) {
 *         videoTrackIndex = i;
 *       }
 *     }
 *
 *     &#64;Override
 *     public void onSampleData(int trackIndex, &#64;NonNull InputReader inputReader)
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
 *      if (requiredLength > sampleDataBuffer.length) {
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
            return mExoPlayerSeekMap.getDurationUs();
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
         * <p>Also includes sample metadata, like presentation timestamp and flags.
         *
         * @param trackIndex The index of the track to which the sample corresponds.
         * @param timeMicros The media timestamp associated with the sample, in microseconds.
         * @param flags Flags associated with the sample. See {@link MediaCodec
         *     MediaCodec.BUFFER_FLAG_*}.
         * @param size The size of the sample data, in bytes.
         * @param offset The number of bytes that have been consumed by {@code onSampleData(int,
         *     MediaParser.InputReader)} for the specified track, since the last byte belonging to
         *     the sample whose metadata is being passed.
         * @param cryptoData Encryption data required to decrypt the sample. May be null for
         *     unencrypted samples.
         */
        void onSampleCompleted(
                int trackIndex,
                long timeMicros,
                int flags,
                int size,
                int offset,
                @Nullable MediaCodec.CryptoInfo cryptoData);
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

    // Public constants.

    /**
     * Sets whether constant bitrate seeking should be enabled for exo.AdtsParser. {@code boolean}
     * expected. Default value is {@code false}.
     */
    public static final String PARAMETER_ADTS_ENABLE_CBR_SEEKING =
            "exo.AdtsParser.enableCbrSeeking";
    /**
     * Sets whether constant bitrate seeking should be enabled for exo.AmrParser. {@code boolean}
     * expected. Default value is {@code false}.
     */
    public static final String PARAMETER_AMR_ENABLE_CBR_SEEKING = "exo.AmrParser.enableCbrSeeking";
    /**
     * Sets whether the ID3 track should be disabled for exo.FlacParser. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_FLAC_DISABLE_ID3 = "exo.FlacParser.disableId3";
    /**
     * Sets whether exo.FragmentedMp4Parser should ignore edit lists. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_FMP4_IGNORE_EDIT_LISTS =
            "exo.FragmentedMp4Parser.ignoreEditLists";
    /**
     * Sets whether exo.FragmentedMp4Parser should ignore the tfdt box. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_FMP4_IGNORE_TFDT_BOX =
            "exo.FragmentedMp4Parser.ignoreTfdtBox";
    /**
     * Sets whether exo.FragmentedMp4Parser should treat all video frames as key frames. {@code
     * boolean} expected. Default value is {@code false}.
     */
    public static final String PARAMETER_FMP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES =
            "exo.FragmentedMp4Parser.treatVideoFramesAsKeyframes";
    /**
     * Sets whether exo.MatroskaParser should avoid seeking to the cues element. {@code boolean}
     * expected. Default value is {@code false}.
     *
     * <p>If this flag is enabled and the cues element occurs after the first cluster, then the
     * media is treated as unseekable.
     */
    public static final String PARAMETER_MATROSKA_DISABLE_CUES_SEEKING =
            "exo.MatroskaParser.disableCuesSeeking";
    /**
     * Sets whether the ID3 track should be disabled for exo.Mp3Parser. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_MP3_DISABLE_ID3 = "exo.Mp3Parser.disableId3";
    /**
     * Sets whether constant bitrate seeking should be enabled for exo.Mp3Parser. {@code boolean}
     * expected. Default value is {@code false}.
     */
    public static final String PARAMETER_MP3_ENABLE_CBR_SEEKING = "exo.Mp3Parser.enableCbrSeeking";
    /**
     * Sets whether exo.Mp3Parser should generate a time-to-byte mapping. {@code boolean} expected.
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
            "exo.Mp3Parser.enableIndexSeeking";
    /**
     * Sets whether exo.Mp4Parser should ignore edit lists. {@code boolean} expected. Default value
     * is {@code false}.
     */
    public static final String PARAMETER_MP4_IGNORE_EDIT_LISTS = "exo.Mp4Parser.ignoreEditLists";
    /**
     * Sets the operation mode for exo.TsParser. {@code String} expected. Valid values are {@code
     * "single_pmt"}, {@code "multi_pmt"}, and {@code "hls"}. Default value is {@code "single_pmt"}.
     *
     * <p>The operation modes alter the way exo.TsParser behaves so that it can handle certain kinds
     * of commonly-occurring malformed media.
     *
     * <ul>
     *   <li>{@code "single_pmt"}: Only the first found PMT is parsed. Others are ignored, even if
     *       more PMTs are declared in the PAT.
     *   <li>{@code "multi_pmt"}: Behave as described in ISO/IEC 13818-1.
     *   <li>{@code "hls"}: Enable {@code "single_pmt"} mode, and ignore continuity counters.
     * </ul>
     */
    public static final String PARAMETER_TS_MODE = "exo.TsParser.mode";
    /**
     * Sets whether exo.TsParser should treat samples consisting of non-IDR I slices as
     * synchronization samples (key-frames). {@code boolean} expected. Default value is {@code
     * false}.
     */
    public static final String PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES =
            "exo.TsParser.allowNonIdrAvcKeyframes";
    /**
     * Sets whether exo.TsParser should ignore AAC elementary streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_AAC_STREAM = "exo.TsParser.ignoreAacStream";
    /**
     * Sets whether exo.TsParser should ignore AVC elementary streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_AVC_STREAM = "exo.TsParser.ignoreAvcStream";
    /**
     * Sets whether exo.TsParser should ignore splice information streams. {@code boolean} expected.
     * Default value is {@code false}.
     */
    public static final String PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM =
            "exo.TsParser.ignoreSpliceInfoStream";
    /**
     * Sets whether exo.TsParser should split AVC stream into access units based on slice headers.
     * {@code boolean} expected. Default value is {@code false}.
     *
     * <p>This flag should be left disabled if the stream contains access units delimiters in order
     * to avoid unnecessary computational costs.
     */
    public static final String PARAMETER_TS_DETECT_ACCESS_UNITS =
            "exo.TsParser.ignoreDetectAccessUnits";
    /**
     * Sets whether exo.TsParser should handle HDMV DTS audio streams. {@code boolean} expected.
     * Default value is {@code false}.
     *
     * <p>Enabling this flag will disable the detection of SCTE subtitles.
     */
    public static final String PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS =
            "exo.TsParser.enableHdmvDtsAudioStreams";

    // Private constants.

    private static final Map<String, ExtractorFactory> EXTRACTOR_FACTORIES_BY_NAME;
    private static final Map<String, Class> EXPECTED_TYPE_BY_PARAMETER_NAME;

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
            @NonNull String name, @NonNull OutputConsumer outputConsumer) {
        String[] nameAsArray = new String[] {name};
        assertValidNames(nameAsArray);
        return new MediaParser(outputConsumer, /* sniff= */ false, name);
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
            @NonNull OutputConsumer outputConsumer, @NonNull String... parserNames) {
        assertValidNames(parserNames);
        if (parserNames.length == 0) {
            parserNames = EXTRACTOR_FACTORIES_BY_NAME.keySet().toArray(new String[0]);
        }
        return new MediaParser(outputConsumer, /* sniff= */ true, parserNames);
    }

    // Misc static methods.

    /**
     * Returns an immutable list with the names of the parsers that are suitable for container
     * formats with the given {@link MediaFormat}.
     *
     * <p>TODO: List which properties are taken into account. E.g. MimeType.
     */
    @NonNull
    public static List<String> getParserNames(@NonNull MediaFormat mediaFormat) {
        throw new UnsupportedOperationException();
    }

    // Private fields.

    private final Map<String, Object> mParserParameters;
    private final OutputConsumer mOutputConsumer;
    private final String[] mParserNamesPool;
    private final PositionHolder mPositionHolder;
    private final InputReadingDataSource mDataSource;
    private final ExtractorInputAdapter mScratchExtractorInputAdapter;
    private final ParsableByteArrayAdapter mScratchParsableByteArrayAdapter;
    private String mExtractorName;
    private Extractor mExtractor;
    private ExtractorInput mExtractorInput;
    private long mPendingSeekPosition;
    private long mPendingSeekTimeMicros;

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
    public MediaParser setParameter(@NonNull String parameterName, @NonNull Object value) {
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
    public boolean supportsParameter(@NonNull String parameterName) {
        return EXPECTED_TYPE_BY_PARAMETER_NAME.containsKey(parameterName);
    }

    /**
     * Returns the name of the backing parser implementation.
     *
     * <p>If this instance was creating using {@link #createByName}, the provided name is returned.
     * If this instance was created using {@link #create}, this method will return null until the
     * first call to {@link #advance}, after which the name of the backing parser implementation is
     * returned.
     *
     * @return The name of the backing parser implementation, or null if the backing parser
     *     implementation has not yet been selected.
     */
    @Nullable
    public String getParserName() {
        return mExtractorName;
    }

    /**
     * Makes progress in the extraction of the input media stream, unless the end of the input has
     * been reached.
     *
     * <p>This method will block until some progress has been made.
     *
     * <p>If this instance was created using {@link #create}. the first call to this method will
     * sniff the content with the parsers with the provided names.
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
            mExtractorInput =
                    new DefaultExtractorInput(
                            mDataSource,
                            seekableInputReader.getPosition(),
                            seekableInputReader.getLength());
        }
        mDataSource.mInputReader = seekableInputReader;

        // TODO: Apply parameters when creating extractor instances.
        if (mExtractor == null) {
            if (mExtractorName != null) {
                mExtractor = EXTRACTOR_FACTORIES_BY_NAME.get(mExtractorName).createInstance();
                mExtractor.init(new ExtractorOutputAdapter());
            } else {
                for (String parserName : mParserNamesPool) {
                    Extractor extractor =
                            EXTRACTOR_FACTORIES_BY_NAME.get(parserName).createInstance();
                    try {
                        if (extractor.sniff(mExtractorInput)) {
                            mExtractorName = parserName;
                            mExtractor = extractor;
                            mExtractor.init(new ExtractorOutputAdapter());
                            break;
                        }
                    } catch (EOFException e) {
                        // Do nothing.
                    } catch (InterruptedException e) {
                        // TODO: Remove this exception replacement once we update the ExoPlayer
                        // version.
                        throw new InterruptedIOException();
                    } finally {
                        mExtractorInput.resetPeekPosition();
                    }
                }
                if (mExtractor == null) {
                    throw UnrecognizedInputFormatException.createForExtractors(mParserNamesPool);
                }
                return true;
            }
        }

        if (isPendingSeek()) {
            mExtractor.seek(mPendingSeekPosition, mPendingSeekTimeMicros);
            removePendingSeek();
        }

        mPositionHolder.position = seekableInputReader.getPosition();
        int result = 0;
        try {
            result = mExtractor.read(mExtractorInput, mPositionHolder);
        } catch (InterruptedException e) {
            // TODO: Remove this exception replacement once we update the ExoPlayer version.
            throw new InterruptedIOException();
        }
        if (result == Extractor.RESULT_END_OF_INPUT) {
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
        // TODO: Dump media metrics here.
        mExtractorInput = null;
        mExtractor = null;
    }

    // Private methods.

    private MediaParser(OutputConsumer outputConsumer, boolean sniff, String... parserNamesPool) {
        mParserParameters = new HashMap<>();
        mOutputConsumer = outputConsumer;
        mParserNamesPool = parserNamesPool;
        if (!sniff) {
            mExtractorName = parserNamesPool[0];
        }
        mPositionHolder = new PositionHolder();
        mDataSource = new InputReadingDataSource();
        removePendingSeek();
        mScratchExtractorInputAdapter = new ExtractorInputAdapter();
        mScratchParsableByteArrayAdapter = new ParsableByteArrayAdapter();
    }

    private boolean isPendingSeek() {
        return mPendingSeekPosition >= 0;
    }

    private void removePendingSeek() {
        mPendingSeekPosition = -1;
        mPendingSeekTimeMicros = -1;
    }

    // Private classes.

    private static final class InputReadingDataSource implements DataSource {

        public InputReader mInputReader;

        @Override
        public void addTransferListener(TransferListener transferListener) {
            // Do nothing.
        }

        @Override
        public long open(DataSpec dataSpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return mInputReader.read(buffer, offset, readLength);
        }

        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return null;
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException();
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
                trackOutput = new TrackOutputAdapter(mTrackOutputAdapters.size());
                mTrackOutputAdapters.put(id, trackOutput);
            }
            return trackOutput;
        }

        @Override
        public void endTracks() {
            mOutputConsumer.onTrackCountFound(mTrackOutputAdapters.size());
        }

        @Override
        public void seekMap(com.google.android.exoplayer2.extractor.SeekMap exoplayerSeekMap) {
            mOutputConsumer.onSeekMapFound(new SeekMap(exoplayerSeekMap));
        }
    }

    private class TrackOutputAdapter implements TrackOutput {

        private final int mTrackIndex;

        private TrackOutputAdapter(int trackIndex) {
            mTrackIndex = trackIndex;
        }

        @Override
        public void format(Format format) {
            mOutputConsumer.onTrackDataFound(
                    mTrackIndex,
                    new TrackData(
                            toMediaFormat(format), toFrameworkDrmInitData(format.drmInitData)));
        }

        @Override
        public int sampleData(ExtractorInput input, int length, boolean allowEndOfInput)
                throws IOException {
            mScratchExtractorInputAdapter.setExtractorInput(input, length);
            long positionBeforeReading = mScratchExtractorInputAdapter.getPosition();
            mOutputConsumer.onSampleDataFound(mTrackIndex, mScratchExtractorInputAdapter);
            return (int) (mScratchExtractorInputAdapter.getPosition() - positionBeforeReading);
        }

        @Override
        public void sampleData(ParsableByteArray data, int length) {
            mScratchParsableByteArrayAdapter.resetWithByteArray(data, length);
            try {
                mOutputConsumer.onSampleDataFound(mTrackIndex, mScratchParsableByteArrayAdapter);
            } catch (IOException e) {
                // Unexpected.
                throw new RuntimeException(e);
            }
        }

        @Override
        public void sampleMetadata(
                long timeUs, int flags, int size, int offset, CryptoData encryptionData) {
            mOutputConsumer.onSampleCompleted(
                    mTrackIndex, timeUs, flags, size, offset, toCryptoInfo(encryptionData));
        }
    }

    private static final class ExtractorInputAdapter implements InputReader {

        private ExtractorInput mExtractorInput;
        private int mCurrentPosition;
        private long mLength;

        public void setExtractorInput(ExtractorInput extractorInput, long length) {
            mExtractorInput = extractorInput;
            mCurrentPosition = 0;
            mLength = length;
        }

        // Input implementation.

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            int readBytes = 0;
            try {
                readBytes = mExtractorInput.read(buffer, offset, readLength);
            } catch (InterruptedException e) {
                // TODO: Remove this exception replacement once we update the ExoPlayer version.
                throw new InterruptedIOException();
            }
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

    /** Creates extractor instances. */
    private interface ExtractorFactory {

        /** Returns a new extractor instance. */
        Extractor createInstance();
    }

    // Private static methods.

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
        if (initData != null) {
            for (int i = 0; i < initData.size(); i++) {
                result.setByteBuffer("csd-" + i, ByteBuffer.wrap(initData.get(i)));
            }
        }
        setOptionalMediaFormatString(result, MediaFormat.KEY_LANGUAGE, format.language);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_PCM_ENCODING, format.pcmEncoding);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_ROTATION, format.rotationDegrees);
        setOptionalMediaFormatInt(result, MediaFormat.KEY_SAMPLE_RATE, format.sampleRate);

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

        // LACK OF SUPPORT FOR:
        //    format.accessibilityChannel;
        //    format.containerMimeType;
        //    format.id;
        //    format.metadata;
        //    format.roleFlags;
        //    format.stereoMode;
        //    format.subsampleOffsetUs;
        return result;
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

    private static DrmInitData toFrameworkDrmInitData(
            com.google.android.exoplayer2.drm.DrmInitData drmInitData) {
        // TODO: Implement.
        return null;
    }

    private static MediaCodec.CryptoInfo toCryptoInfo(TrackOutput.CryptoData encryptionData) {
        // TODO: Implement.
        return null;
    }

    /** Returns a new {@link SeekPoint} equivalent to the given {@code exoPlayerSeekPoint}. */
    private static SeekPoint toSeekPoint(
            com.google.android.exoplayer2.extractor.SeekPoint exoPlayerSeekPoint) {
        return new SeekPoint(exoPlayerSeekPoint.timeUs, exoPlayerSeekPoint.position);
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

    // Static initialization.

    static {
        // Using a LinkedHashMap to keep the insertion order when iterating over the keys.
        LinkedHashMap<String, ExtractorFactory> extractorFactoriesByName = new LinkedHashMap<>();
        // Parsers are ordered to match ExoPlayer's DefaultExtractorsFactory extractor ordering,
        // which in turn aims to minimize the chances of incorrect extractor selections.
        extractorFactoriesByName.put("exo.MatroskaParser", MatroskaExtractor::new);
        extractorFactoriesByName.put("exo.FragmentedMp4Parser", FragmentedMp4Extractor::new);
        extractorFactoriesByName.put("exo.Mp4Parser", Mp4Extractor::new);
        extractorFactoriesByName.put("exo.Mp3Parser", Mp3Extractor::new);
        extractorFactoriesByName.put("exo.AdtsParser", AdtsExtractor::new);
        extractorFactoriesByName.put("exo.Ac3Parser", Ac3Extractor::new);
        extractorFactoriesByName.put("exo.TsParser", TsExtractor::new);
        extractorFactoriesByName.put("exo.FlvParser", FlvExtractor::new);
        extractorFactoriesByName.put("exo.OggParser", OggExtractor::new);
        extractorFactoriesByName.put("exo.PsParser", PsExtractor::new);
        extractorFactoriesByName.put("exo.WavParser", WavExtractor::new);
        extractorFactoriesByName.put("exo.AmrParser", AmrExtractor::new);
        extractorFactoriesByName.put("exo.Ac4Parser", Ac4Extractor::new);
        extractorFactoriesByName.put("exo.FlacParser", FlacExtractor::new);
        EXTRACTOR_FACTORIES_BY_NAME = Collections.unmodifiableMap(extractorFactoriesByName);

        HashMap<String, Class> expectedTypeByParameterName = new HashMap<>();
        expectedTypeByParameterName.put(PARAMETER_ADTS_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_AMR_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_FLAC_DISABLE_ID3, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_FMP4_IGNORE_EDIT_LISTS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_FMP4_IGNORE_TFDT_BOX, Boolean.class);
        expectedTypeByParameterName.put(
                PARAMETER_FMP4_TREAT_VIDEO_FRAMES_AS_KEYFRAMES, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MATROSKA_DISABLE_CUES_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_DISABLE_ID3, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_ENABLE_CBR_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP3_ENABLE_INDEX_SEEKING, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_MP4_IGNORE_EDIT_LISTS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_MODE, String.class);
        expectedTypeByParameterName.put(PARAMETER_TS_ALLOW_NON_IDR_AVC_KEYFRAMES, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_AAC_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_AVC_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_IGNORE_SPLICE_INFO_STREAM, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_DETECT_ACCESS_UNITS, Boolean.class);
        expectedTypeByParameterName.put(PARAMETER_TS_ENABLE_HDMV_DTS_AUDIO_STREAMS, Boolean.class);
        EXPECTED_TYPE_BY_PARAMETER_NAME = Collections.unmodifiableMap(expectedTypeByParameterName);
    }
}
