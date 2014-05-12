/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.params;

import static com.android.internal.util.Preconditions.*;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.utils.HashCodeHelpers;

import java.util.Arrays;

/**
 * Immutable class to store the input to output formats
 * {@link CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP map} to be used for with
 * camera image reprocessing.
 *
 * <p>
 * The mapping of image formats that are supported by this camera device for input streams,
 * to their corresponding output formats.</p>
 *
 * <p>
 * Attempting to configure an input stream with output streams not listed as available in this map
 * is not valid.
 * </p>
 *
 * @see CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP
 * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS
 *
 * <!-- hide this until we expose input streams through public API -->
 * @hide
 */
public final class ReprocessFormatsMap {
    /**
     * Create a new {@link ReprocessFormatsMap}
     *
     * <p>This value is encoded as a variable-size array-of-arrays.
     * The inner array always contains {@code [format, length, ...]} where ... has length elements.
     * An inner array is followed by another inner array if the total metadata entry size hasn't
     * yet been exceeded.</p>
     *
     * <p>Entry must not be {@code null}. Empty array is acceptable.</p>
     *
     * <p>The entry array ownership is passed to this instance after construction; do not
     * write to it afterwards.</p>
     *
     * @param entry Array of ints, not yet deserialized (not-null)
     *
     * @throws IllegalArgumentException
     *              if the data was poorly formatted
     *              (missing output format length or too few output formats)
     *              or if any of the input/formats were not valid
     * @throws NullPointerException
     *              if entry was null
     *
     * @see StreamConfigurationMap#checkArgumentFormatInternal
     *
     * @hide
     */
    public ReprocessFormatsMap(final int[] entry) {
        checkNotNull(entry, "entry must not be null");

        int numInputs = 0;
        int left = entry.length;
        for (int i = 0; i < entry.length; ) {
            int inputFormat = StreamConfigurationMap.checkArgumentFormatInternal(entry[i]);

            left--;
            i++;

            if (left < 1) {
                throw new IllegalArgumentException(
                        String.format("Input %x had no output format length listed", inputFormat));
            }

            final int length = entry[i];
            left--;
            i++;

            for (int j = 0; j < length; ++j) {
                int outputFormat = entry[i + j];
                StreamConfigurationMap.checkArgumentFormatInternal(outputFormat);
            }

            if (length > 0) {
                if (left < length) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "Input %x had too few output formats listed (actual: %d, " +
                                    "expected: %d)", inputFormat, left, length));
                }

                i += length;
                left -= length;
            }

            numInputs++;
        }

        mEntry = entry;
        mInputCount = numInputs;
    }

    /**
     * Get a list of all input image formats that can be used to reprocess an input
     * stream into an output stream.
     *
     * <p>Use this input format to look up the available output formats with {@link #getOutputs}.
     * </p>
     *
     * @return an array of inputs (possibly empty, but never {@code null})
     *
     * @see ImageFormat
     * @see #getOutputs
     */
    public int[] getInputs() {
        final int[] inputs = new int[mInputCount];

        int left = mEntry.length;
        for (int i = 0, j = 0; i < mEntry.length; j++) {
            final int format = mEntry[i];

            left--;
            i++;

            if (left < 1) {
                throw new AssertionError(
                        String.format("Input %x had no output format length listed", format));
            }

            final int length = mEntry[i];
            left--;
            i++;

            if (length > 0) {
                if (left < length) {
                    throw new AssertionError(
                            String.format(
                                    "Input %x had too few output formats listed (actual: %d, " +
                                    "expected: %d)", format, left, length));
                }

                i += length;
                left -= length;
            }

            inputs[j] = format;
        }

        return StreamConfigurationMap.imageFormatToPublic(inputs);
    }

    /**
     * Get the list of output formats that can be reprocessed into from the input {@code format}.
     *
     * <p>The input {@code format} must be one of the formats returned by {@link #getInputs}.</p>
     *
     * @param format an input format
     *
     * @return list of output image formats
     *
     * @see ImageFormat
     * @see #getInputs
     */
    public int[] getOutputs(final int format) {

        int left = mEntry.length;
        for (int i = 0; i < mEntry.length; ) {
            final int inputFormat = mEntry[i];

            left--;
            i++;

            if (left < 1) {
                throw new AssertionError(
                        String.format("Input %x had no output format length listed", format));
            }

            final int length = mEntry[i];
            left--;
            i++;

            if (length > 0) {
                if (left < length) {
                    throw new AssertionError(
                            String.format(
                                    "Input %x had too few output formats listed (actual: %d, " +
                                    "expected: %d)", format, left, length));
                }
            }

            if (inputFormat == format) {
                int[] outputs = new int[length];

                // Copying manually faster than System.arraycopy for small arrays
                for (int k = 0; k < length; ++k) {
                    outputs[k] = mEntry[i + k];
                }

                return StreamConfigurationMap.imageFormatToPublic(outputs);
            }

            i += length;
            left -= length;

        }

        throw new IllegalArgumentException(
                String.format("Input format %x was not one in #getInputs", format));
    }

    /**
     * Check if this {@link ReprocessFormatsMap} is equal to another
     * {@link ReprocessFormatsMap}.
     *
     * <p>These two objects are only equal if and only if each of the respective elements is equal.
     * </p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof ReprocessFormatsMap) {
            final ReprocessFormatsMap other = (ReprocessFormatsMap) obj;
            // Do not compare anything besides mEntry, since the rest of the values are derived
            return Arrays.equals(mEntry, other.mEntry);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // Do not hash anything besides mEntry since the rest of the values are derived
        return HashCodeHelpers.hashCode(mEntry);
    }

    private final int[] mEntry;
    /*
     * Dependent fields: values are derived from mEntry
     */
    private final int mInputCount;
}
