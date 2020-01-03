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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
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
 *
 * class VideoOutputConsumer implements MediaParser.OutputConsumer {
 *
 *     private static final int MAXIMUM_SAMPLE_SIZE = ...;
 *     private byte[] sampleDataBuffer = new byte[MAXIMUM_SAMPLE_SIZE];
 *     private int videoTrackIndex = -1;
 *     private int bytesWrittenCount = 0;
 *
 *     \@Override
 *     public void onSeekMap(int i, @NonNull MediaFormat mediaFormat) { \/* Do nothing. *\/ }
 *
 *     \@Override
 *     public void onTrackData(int i, @NonNull TrackData trackData) {
 *       MediaFormat mediaFormat = trackData.mediaFormat;
 *       if (videoTrackIndex == -1 && mediaFormat
 *           .getString(MediaFormat.KEY_MIME, \/* defaultValue= *\/ "").startsWith("video/")) {
 *         videoTrackIndex = i;
 *       }
 *     }
 *
 *     \@Override
 *     public void onSampleData(int trackIndex, @NonNull InputReader inputReader)
 *         throws IOException, InterruptedException {
 *       int numberOfBytesToRead = (int) inputReader.getLength();
 *       if (videoTrackIndex != trackIndex) {
 *         // Discard contents.
 *         inputReader.read(\/* bytes= *\/ null, \/* offset= *\/ 0, numberOfBytesToRead);
 *       }
 *       int bytesRead = inputReader.read(sampleDataBuffer, bytesWrittenCount, numberOfBytesToRead);
 *       bytesWrittenCount += bytesRead;
 *     }
 *
 *     \@Override
 *     public void onSampleCompleted(
 *         int trackIndex,
 *         long timeUs,
 *         int flags,
 *         int size,
 *         int offset,
 *         \@Nullable CryptoInfo cryptoData) {
 *       if (videoTrackIndex != trackIndex) {
 *         return; // It's not the video track. Ignore.
 *       }
 *       byte[] sampleData = new byte[size];
 *       System.arraycopy(sampleDataBuffer, bytesWrittenCount - size - offset, sampleData, \/*
 *       destPos= *\/ 0, size);
 *       // Place trailing bytes at the start of the buffer.
 *       System.arraycopy(
 *           sampleDataBuffer,
 *           bytesWrittenCount - offset,
 *           sampleDataBuffer,
 *           \/* destPos= *\/ 0,
 *           \/* size= *\/ offset);
 *       publishSample(sampleData, timeUs, flags);
 *     }
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

        /** Returned by {@link #getDurationUs()} when the duration is unknown. */
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
        public long getDurationUs() {
            return mExoPlayerSeekMap.getDurationUs();
        }

        /**
         * Obtains {@link SeekPoint SeekPoints} for the specified seek time in microseconds.
         *
         * <p>{@code getSeekPoints(timeUs).first} contains the latest seek point for samples with
         * timestamp equal to or smaller than {@code timeUs}.
         *
         * <p>{@code getSeekPoints(timeUs).second} contains the earliest seek point for samples with
         * timestamp equal to or greater than {@code timeUs}. If a seek point exists for {@code
         * timeUs}, the returned pair will contain the same {@link SeekPoint} twice.
         *
         * @param timeUs A seek time in microseconds.
         * @return The corresponding {@link SeekPoint SeekPoints}.
         */
        @NonNull
        public Pair<SeekPoint, SeekPoint> getSeekPoints(long timeUs) {
            SeekPoints seekPoints = mExoPlayerSeekMap.getSeekPoints(timeUs);
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
        public static final @NonNull SeekPoint START = new SeekPoint(0, 0);

        /** The time of the seek point, in microseconds. */
        public final long timeUs;

        /** The byte offset of the seek point. */
        public final long position;

        /**
         * @param timeUs The time of the seek point, in microseconds.
         * @param position The byte offset of the seek point.
         */
        private SeekPoint(long timeUs, long position) {
            this.timeUs = timeUs;
            this.position = position;
        }

        @Override
        public @NonNull String toString() {
            return "[timeUs=" + timeUs + ", position=" + position + "]";
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
            return timeUs == other.timeUs && position == other.position;
        }

        @Override
        public int hashCode() {
            int result = (int) timeUs;
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
        int read(@NonNull byte[] buffer, int offset, int readLength)
                throws IOException, InterruptedException;

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
        void onSeekMap(@NonNull SeekMap seekMap);

        /**
         * Called when the number of tracks is found.
         *
         * @param numberOfTracks The number of tracks in the stream.
         */
        void onTracksFound(int numberOfTracks);

        /**
         * Called when new {@link TrackData} is extracted from the stream.
         *
         * @param trackIndex The index of the track for which the {@link TrackData} was extracted.
         * @param trackData The extracted {@link TrackData}.
         */
        void onTrackData(int trackIndex, @NonNull TrackData trackData);

        /**
         * Called to write sample data to the output.
         *
         * <p>Implementers must attempt to consume the entirety of the input, but should surface any
         * thrown {@link IOException} caused by reading from {@code input}.
         *
         * @param trackIndex The index of the track to which the sample data corresponds.
         * @param inputReader The {@link InputReader} from which to read the data.
         */
        void onSampleData(int trackIndex, @NonNull InputReader inputReader)
                throws IOException, InterruptedException;

        /**
         * Called once all the data of a sample has been passed to {@link #onSampleData}.
         *
         * <p>Also includes sample metadata, like presentation timestamp and flags.
         *
         * @param trackIndex The index of the track to which the sample corresponds.
         * @param timeUs The media timestamp associated with the sample, in microseconds.
         * @param flags Flags associated with the sample. See {@link MediaCodec
         *     MediaCodec.BUFFER_FLAG_*}.
         * @param size The size of the sample data, in bytes.
         * @param offset The number of bytes that have been passed to {@link #onSampleData} since
         *     the last byte belonging to the sample whose metadata is being passed.
         * @param cryptoData Encryption data required to decrypt the sample. May be null for
         *     unencrypted samples.
         */
        void onSampleCompleted(
                int trackIndex,
                long timeUs,
                int flags,
                int size,
                int offset,
                @Nullable MediaCodec.CryptoInfo cryptoData);
    }

    /**
     * Thrown if all extractors implementations provided to {@link #create} failed to sniff the
     * input content.
     */
    public static final class UnrecognizedInputFormatException extends IOException {

        /**
         * Creates a new instance which signals that the extractors with the given names failed to
         * parse the input.
         */
        @NonNull
        private static UnrecognizedInputFormatException createForExtractors(
                @NonNull String... extractorNames) {
            StringBuilder builder = new StringBuilder();
            builder.append("None of the available extractors ( ");
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

    // Private constants.

    private static final Map<String, ExtractorFactory> EXTRACTOR_FACTORIES_BY_NAME;

    // Instance creation methods.

    /**
     * Creates an instance backed by the extractor with the given {@code name}. The returned
     * instance will attempt extraction without sniffing the content.
     *
     * @param name The name of the extractor that will be associated with the created instance.
     * @param outputConsumer The {@link OutputConsumer} to which track data and samples are pushed.
     * @return A new instance.
     * @throws IllegalArgumentException If an invalid name is provided.
     */
    public static @NonNull MediaParser createByName(
            @NonNull String name, @NonNull OutputConsumer outputConsumer) {
        String[] nameAsArray = new String[] {name};
        assertValidNames(nameAsArray);
        return new MediaParser(outputConsumer, /* sniff= */ false, name);
    }

    /**
     * Creates an instance whose backing extractor will be selected by sniffing the content during
     * the first {@link #advance} call. Extractor implementations will sniff the content in order of
     * appearance in {@code extractorNames}.
     *
     * @param outputConsumer The {@link OutputConsumer} to which extracted data is output.
     * @param extractorNames The names of the extractors to sniff the content with. If empty, a
     *     default array of names is used.
     * @return A new instance.
     */
    public static @NonNull MediaParser create(
            @NonNull OutputConsumer outputConsumer, @NonNull String... extractorNames) {
        assertValidNames(extractorNames);
        if (extractorNames.length == 0) {
            extractorNames = EXTRACTOR_FACTORIES_BY_NAME.keySet().toArray(new String[0]);
        }
        return new MediaParser(outputConsumer, /* sniff= */ true, extractorNames);
    }

    // Misc static methods.

    /**
     * Returns an immutable list with the names of the extractors that are suitable for container
     * formats with the given {@link MediaFormat}.
     *
     * <p>TODO: List which properties are taken into account. E.g. MimeType.
     */
    public static @NonNull List<String> getExtractorNames(@NonNull MediaFormat mediaFormat) {
        throw new UnsupportedOperationException();
    }

    // Private fields.

    private final OutputConsumer mOutputConsumer;
    private final String[] mExtractorNamesPool;
    private final PositionHolder mPositionHolder;
    private final InputReadingDataSource mDataSource;
    private final ExtractorInputAdapter mScratchExtractorInputAdapter;
    private final ParsableByteArrayAdapter mScratchParsableByteArrayAdapter;
    private String mExtractorName;
    private Extractor mExtractor;
    private ExtractorInput mExtractorInput;
    private long mPendingSeekPosition;
    private long mPendingSeekTimeUs;

    // Public methods.

    /**
     * Returns the name of the backing extractor implementation.
     *
     * <p>If this instance was creating using {@link #createByName}, the provided name is returned.
     * If this instance was created using {@link #create}, this method will return null until the
     * first call to {@link #advance}, after which the name of the backing extractor implementation
     * is returned.
     *
     * @return The name of the backing extractor implementation, or null if the backing extractor
     *     implementation has not yet been selected.
     */
    public @Nullable String getExtractorName() {
        return mExtractorName;
    }

    /**
     * Makes progress in the extraction of the input media stream, unless the end of the input has
     * been reached.
     *
     * <p>This method will block until some progress has been made.
     *
     * <p>If this instance was created using {@link #create}. the first call to this method will
     * sniff the content with the extractors with the provided names.
     *
     * @param seekableInputReader The {@link SeekableInputReader} from which to obtain the media
     *     container data.
     * @return Whether there is any data left to extract. Returns false if the end of input has been
     *     reached.
     * @throws UnrecognizedInputFormatException
     */
    public boolean advance(@NonNull SeekableInputReader seekableInputReader)
            throws IOException, InterruptedException {
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

        if (mExtractor == null) {
            for (String extractorName : mExtractorNamesPool) {
                Extractor extractor =
                        EXTRACTOR_FACTORIES_BY_NAME.get(extractorName).createInstance();
                try {
                    if (extractor.sniff(mExtractorInput)) {
                        mExtractorName = extractorName;
                        mExtractor = extractor;
                        mExtractor.init(new ExtractorOutputAdapter());
                        break;
                    }
                } catch (EOFException e) {
                    // Do nothing.
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                } finally {
                    mExtractorInput.resetPeekPosition();
                }
            }
            if (mExtractor == null) {
                UnrecognizedInputFormatException.createForExtractors(mExtractorNamesPool);
            }
            return true;
        }

        if (isPendingSeek()) {
            mExtractor.seek(mPendingSeekPosition, mPendingSeekTimeUs);
            removePendingSeek();
        }

        mPositionHolder.position = seekableInputReader.getPosition();
        int result = mExtractor.read(mExtractorInput, mPositionHolder);
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
     * OutputConsumer#onSeekMap(SeekMap)}.
     *
     * <p>Following a call to this method, the {@link InputReader} passed to the next invocation of
     * {@link #advance} must provide data starting from {@link SeekPoint#position} in the stream.
     *
     * @param seekPoint The {@link SeekPoint} to seek to.
     */
    public void seek(@NonNull SeekPoint seekPoint) {
        if (mExtractor == null) {
            mPendingSeekPosition = seekPoint.position;
            mPendingSeekTimeUs = seekPoint.timeUs;
        } else {
            mExtractor.seek(seekPoint.position, seekPoint.timeUs);
        }
    }

    /**
     * Releases any acquired resources.
     *
     * <p>After calling this method, this instance becomes unusable and no other methods should be
     * invoked. DESIGN NOTE: Should be removed. There shouldn't be any resource for releasing.
     */
    public void release() {
        mExtractorInput = null;
        mExtractor = null;
    }

    // Private methods.

    private MediaParser(
            OutputConsumer outputConsumer, boolean sniff, String... extractorNamesPool) {
        mOutputConsumer = outputConsumer;
        mExtractorNamesPool = extractorNamesPool;
        if (!sniff) {
            mExtractorName = extractorNamesPool[0];
            mExtractor = EXTRACTOR_FACTORIES_BY_NAME.get(mExtractorName).createInstance();
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
        mPendingSeekTimeUs = -1;
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
            // TODO: Reevaluate interruption in Input.
            try {
                return mInputReader.read(buffer, offset, readLength);
            } catch (InterruptedException e) {
                // TODO: Remove.
                throw new RuntimeException();
            }
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
            mOutputConsumer.onTracksFound(mTrackOutputAdapters.size());
        }

        @Override
        public void seekMap(com.google.android.exoplayer2.extractor.SeekMap exoplayerSeekMap) {
            mOutputConsumer.onSeekMap(new SeekMap(exoplayerSeekMap));
        }
    }

    private class TrackOutputAdapter implements TrackOutput {

        private final int mTrackIndex;

        private TrackOutputAdapter(int trackIndex) {
            mTrackIndex = trackIndex;
        }

        @Override
        public void format(Format format) {
            mOutputConsumer.onTrackData(
                    mTrackIndex,
                    new TrackData(
                            toMediaFormat(format), toFrameworkDrmInitData(format.drmInitData)));
        }

        @Override
        public int sampleData(ExtractorInput input, int length, boolean allowEndOfInput)
                throws IOException, InterruptedException {
            mScratchExtractorInputAdapter.setExtractorInput(input, length);
            long positionBeforeReading = mScratchExtractorInputAdapter.getPosition();
            mOutputConsumer.onSampleData(mTrackIndex, mScratchExtractorInputAdapter);
            return (int) (mScratchExtractorInputAdapter.getPosition() - positionBeforeReading);
        }

        @Override
        public void sampleData(ParsableByteArray data, int length) {
            mScratchParsableByteArrayAdapter.resetWithByteArray(data, length);
            try {
                mOutputConsumer.onSampleData(mTrackIndex, mScratchParsableByteArrayAdapter);
            } catch (IOException | InterruptedException e) {
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
        public int read(byte[] buffer, int offset, int readLength)
                throws IOException, InterruptedException {
            int readBytes = mExtractorInput.read(buffer, offset, readLength);
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

        // TODO: Add if (value != Format.NO_VALUE);

        MediaFormat result = new MediaFormat();
        result.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate);
        result.setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channelCount);
        if (format.colorInfo != null) {
            result.setInteger(MediaFormat.KEY_COLOR_TRANSFER, format.colorInfo.colorTransfer);
            result.setInteger(MediaFormat.KEY_COLOR_RANGE, format.colorInfo.colorRange);
            result.setInteger(MediaFormat.KEY_COLOR_STANDARD, format.colorInfo.colorSpace);
            if (format.colorInfo.hdrStaticInfo != null) {
                result.setByteBuffer(
                        MediaFormat.KEY_HDR_STATIC_INFO,
                        ByteBuffer.wrap(format.colorInfo.hdrStaticInfo));
            }
        }
        result.setString(MediaFormat.KEY_MIME, format.sampleMimeType);
        result.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate);
        result.setInteger(MediaFormat.KEY_WIDTH, format.width);
        result.setInteger(MediaFormat.KEY_HEIGHT, format.height);
        List<byte[]> initData = format.initializationData;
        if (initData != null) {
            for (int i = 0; i < initData.size(); i++) {
                result.setByteBuffer("csd-" + i, ByteBuffer.wrap(initData.get(i)));
            }
        }
        result.setString(MediaFormat.KEY_LANGUAGE, format.language);
        result.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
        result.setInteger(MediaFormat.KEY_PCM_ENCODING, format.pcmEncoding);
        result.setInteger(MediaFormat.KEY_ROTATION, format.rotationDegrees);
        result.setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate);

        int selectionFlags = format.selectionFlags;
        // We avoid setting selection flags in the MediaFormat, unless explicitly signaled by the
        // extractor.
        if ((selectionFlags & C.SELECTION_FLAG_AUTOSELECT) != 0) {
            result.setInteger(MediaFormat.KEY_IS_AUTOSELECT, 1);
        }
        if ((selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
            result.setInteger(MediaFormat.KEY_IS_DEFAULT, 1);
        }
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
            result.setInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE, 1);
        }

        // LACK OF SUPPORT FOR:
        //    format.accessibilityChannel;
        //    format.codecs;
        //    format.containerMimeType;
        //    format.drmInitData;
        //    format.encoderDelay;
        //    format.encoderPadding;
        //    format.id;
        //    format.metadata;
        //    format.pixelWidthHeightRatio;
        //    format.roleFlags;
        //    format.stereoMode;
        //    format.subsampleOffsetUs;
        return result;
    }

    private static int toFrameworkFlags(int flags) {
        // TODO: Implement.
        return 0;
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
                                + ". Supported extractors are: "
                                + TextUtils.join(", ", EXTRACTOR_FACTORIES_BY_NAME.keySet())
                                + ".");
            }
        }
    }

    // Static initialization.

    static {
        // Using a LinkedHashMap to keep the insertion order when iterating over the keys.
        LinkedHashMap<String, ExtractorFactory> extractorFactoriesByName = new LinkedHashMap<>();
        extractorFactoriesByName.put("exo.Ac3Extractor", Ac3Extractor::new);
        extractorFactoriesByName.put("exo.Ac4Extractor", Ac4Extractor::new);
        extractorFactoriesByName.put("exo.AdtsExtractor", AdtsExtractor::new);
        extractorFactoriesByName.put("exo.AmrExtractor", AmrExtractor::new);
        extractorFactoriesByName.put("exo.FlvExtractor", FlvExtractor::new);
        extractorFactoriesByName.put("exo.FragmentedMp4Extractor", FragmentedMp4Extractor::new);
        extractorFactoriesByName.put("exo.MatroskaExtractor", MatroskaExtractor::new);
        extractorFactoriesByName.put("exo.Mp3Extractor", Mp3Extractor::new);
        extractorFactoriesByName.put("exo.Mp4Extractor", Mp4Extractor::new);
        extractorFactoriesByName.put("exo.OggExtractor", OggExtractor::new);
        extractorFactoriesByName.put("exo.PsExtractor", PsExtractor::new);
        extractorFactoriesByName.put("exo.TsExtractor", TsExtractor::new);
        extractorFactoriesByName.put("exo.WavExtractor", WavExtractor::new);
        EXTRACTOR_FACTORIES_BY_NAME = Collections.unmodifiableMap(extractorFactoriesByName);
    }
}
