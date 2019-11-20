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

import android.util.Pair;

import java.io.IOException;
import java.util.List;

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
 *   <li>{@link Input}: Provides the media containers bytes to parse.
 *   <li>{@link OutputCallback}: Provides a sink for all extracted data and metadata.
 * </ul>
 *
 * TODO: Add usage example here.
 */
// @HiddenApi
public final class MediaParser {

    /** Maps seek positions to corresponding positions in the stream. */
    public interface SeekMap {

        /** Returned by {@link #getDurationUs()} when the duration is unknown. */
        int UNKNOWN_DURATION = Integer.MIN_VALUE;

        /** Returns whether seeking is supported. */
        boolean isSeekable();

        /**
         * Returns the duration of the stream in microseconds or {@link #UNKNOWN_DURATION} if the
         * duration is unknown.
         */
        long getDurationUs();

        /**
         * Obtains {@link SeekPoint SeekPoints} for the specified seek time in microseconds.
         *
         * <p>{@code getSeekPoints(timeUs).first} contains the latest seek point for samples with
         * timestamp equal to or smaller than {@code timeUs}.
         *
         * <p>{@code getSeekPoints(timeUs).second} contains the earlies seek point for samples with
         * timestamp equal to or greater than {@code timeUs}. If a seek point exists for {@code
         * timeUs}, the returned pair will contain the same {@link SeekPoint} twice.
         *
         * @param timeUs A seek time in microseconds.
         * @return The corresponding {@link SeekPoint SeekPoints}.
         */
        Pair<SeekPoint, SeekPoint> getSeekPoints(long timeUs);
    }

    /** Defines a seek point in a media stream. */
    public static final class SeekPoint {

        /** A {@link SeekPoint} whose time and byte offset are both set to 0. */
        public static final SeekPoint START = new SeekPoint(0, 0);

        /** The time of the seek point, in microseconds. */
        public final long mTimeUs;

        /** The byte offset of the seek point. */
        public final long mPosition;

        /**
         * @param timeUs The time of the seek point, in microseconds.
         * @param position The byte offset of the seek point.
         */
        public SeekPoint(long timeUs, long position) {
            this.mTimeUs = timeUs;
            this.mPosition = position;
        }

        @Override
        public String toString() {
            return "[timeUs=" + mTimeUs + ", position=" + mPosition + "]";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            SeekPoint other = (SeekPoint) obj;
            return mTimeUs == other.mTimeUs && mPosition == other.mPosition;
        }

        @Override
        public int hashCode() {
            int result = (int) mTimeUs;
            result = 31 * result + (int) mPosition;
            return result;
        }
    }

    /** Provides input data to {@link MediaParser}. */
    public interface Input {

        /**
         * Reads up to {@code readLength} bytes of data and stores them into {@code buffer},
         * starting at index {@code offset}.
         *
         * <p>The call will block until at least one byte of data has been read.
         *
         * @param buffer The buffer into which the read data should be stored.
         * @param offset The start offset into {@code buffer} at which data should be written.
         * @param readLength The maximum number of bytes to read.
         * @return The non-zero number of bytes read, or -1 if no data is available because the end
         *     of the input has been reached.
         * @throws java.io.IOException If an error occurs reading from the source.
         */
        int read(byte[] buffer, int offset, int readLength)
                throws IOException, InterruptedException;

        /** Returns the current read position (byte offset) in the stream. */
        long getPosition();

        /** Returns the length of the input in bytes, or -1 if the length is unknown. */
        long getLength();
    }

    /** Receives extracted media sample data and metadata from {@link MediaParser}. */
    public interface OutputCallback {

        /**
         * Called when the number of tracks is defined.
         *
         * @param numberOfTracks The number of tracks in the stream.
         */
        void onTracksFound(int numberOfTracks);

        /**
         * Called when a {@link SeekMap} has been extracted from the stream.
         *
         * @param seekMap The extracted {@link SeekMap}.
         */
        void onSeekMap(SeekMap seekMap);

        /**
         * Called when the {@link MediaFormat} of the track is extracted from the stream.
         *
         * @param trackIndex The index of the track for which the {@link MediaFormat} was found.
         * @param format The extracted {@link MediaFormat}.
         */
        void onFormat(int trackIndex, MediaFormat format);

        /**
         * Called to write sample data to the output.
         *
         * <p>Implementers must attempt to consume the entirety of the input, but should surface any
         * thrown {@link IOException} caused by reading from {@code input}.
         *
         * @param trackIndex The index of the track to which the sample data corresponds.
         * @param input The {@link Input} from which to read the data.
         * @return
         */
        int onSampleData(int trackIndex, Input input) throws IOException, InterruptedException;

        /**
         * Defines the boundaries and metadata of an extracted sample.
         *
         * <p>The corresponding sample data will have already been passed to the output via calls to
         * {@link #onSampleData}.
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
                MediaCodec.CryptoInfo cryptoData);
    }

    /**
     * Controls the behavior of extractors' implementations.
     *
     * <p>DESIGN NOTE: For setting flags like workarounds and special behaviors for adaptive
     * streaming.
     */
    public static final class Parameters {

        // TODO: Implement.

    }

    /** Holds the result of an {@link #advance} invocation. */
    public static final class ResultHolder {

        /** Creates a new instance with {@link #result} holding {@link #ADVANCE_RESULT_CONTINUE}. */
        public ResultHolder() {
            result = ADVANCE_RESULT_CONTINUE;
        }

        /**
         * May hold {@link #ADVANCE_RESULT_END_OF_INPUT}, {@link #ADVANCE_RESULT_CONTINUE}, {@link
         * #ADVANCE_RESULT_SEEK}.
         */
        public int result;

        /**
         * If {@link #result} holds {@link #ADVANCE_RESULT_SEEK}, holds the stream position required
         * from the passed {@link Input} to the next {@link #advance} call. If {@link #result} does
         * not hold {@link #ADVANCE_RESULT_SEEK}, the value of this variable is undefined and should
         * be ignored.
         */
        public long seekPosition;
    }

    /**
     * Thrown if all extractors implementations provided to {@link #create} failed to sniff the
     * input content.
     */
    // @HiddenApi
    public static final class UnrecognizedInputFormatException extends IOException {

        /**
         * Creates a new instance which signals that the extractors with the given names failed to
         * parse the input.
         */
        public static UnrecognizedInputFormatException createForExtractors(
                String... extractorNames) {
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

    // Public constants.

    /**
     * Returned by {@link #advance} if the {@link Input} passed to the next {@link #advance} is
     * required to provide data continuing from the position in the stream reached by the returning
     * call.
     */
    public static final int ADVANCE_RESULT_CONTINUE = -1;
    /** Returned by {@link #advance} if the end of the {@link Input} was reached. */
    public static final int ADVANCE_RESULT_END_OF_INPUT = -2;
    /**
     * Returned by {@link #advance} when its next call expects a specific stream position, which
     * will be held by {@link ResultHolder#seekPosition}.
     */
    public static final int ADVANCE_RESULT_SEEK = -3;

    // Instance creation methods.

    /**
     * Creates an instance backed by the extractor with the given {@code name}. The returned
     * instance will attempt extraction without sniffing the content.
     *
     * @param name The name of the extractor that will be associated with the created instance.
     * @param outputCallback The {@link OutputCallback} to which track data and samples are pushed.
     * @param parameters Parameters that control specific aspects of the behavior of the extractors.
     * @return A new instance.
     */
    public static MediaParser createByName(
            String name, OutputCallback outputCallback, Parameters parameters) {
        throw new UnsupportedOperationException();
    }

    /**
     * Creates an instance whose backing extractor will be selected by sniffing the content during
     * the first {@link #advance} call. Extractor implementations will sniff the content in order of
     * appearance in {@code extractorNames}.
     *
     * @param outputCallback The {@link OutputCallback} to track data and samples are obtained.
     * @param parameters Parameters that control specific aspects of the behavior of the extractors.
     * @param extractorNames The names of the extractors to sniff the content with. If empty, a
     *     default array of names is used.
     * @return A new instance.
     */
    public static MediaParser create(
            OutputCallback outputCallback, Parameters parameters, String... extractorNames) {
        throw new UnsupportedOperationException();
    }

    // Misc static methods.

    /**
     * Returns an immutable list with the names of the extractors that are suitable for container
     * formats with the given {@code mimeTypes}. If an empty string is passed, all available
     * extractors' names are returned.
     *
     * <p>TODO: Replace string with media type object.
     */
    public static List<String> getExtractorNames(String mimeTypes) {
        throw new UnsupportedOperationException();
    }

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
    public String getExtractorName() {
        throw new UnsupportedOperationException();
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
     * @param input The {@link Input} from which to obtain the media container data.
     * @param resultHolder The {@link ResultHolder} into which the result of the operation will be
     *     written.
     * @throws UnrecognizedInputFormatException
     */
    public void advance(Input input, ResultHolder resultHolder)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    /**
     * Seeks within the media container being extracted.
     *
     * <p>Following a call to this method, the {@link Input} passed to the next invocation of {@link
     * #advance} must provide data starting from {@link SeekPoint#mPosition} in the stream.
     *
     * @param seekPoint The {@link SeekPoint} to seek to.
     */
    public void seek(SeekPoint seekPoint) {
        throw new UnsupportedOperationException();
    }

    /**
     * Releases any acquired resources.
     *
     * <p>After calling this method, this instance becomes unusable and no other methods should be
     * invoked. DESIGN NOTE: Should be removed. There shouldn't be any resource for releasing.
     */
    public void release() {
        throw new UnsupportedOperationException();
    }
}
