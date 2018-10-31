/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.Nullable;
import android.os.Process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and parses {@code time_in_state} files in the {@code proc} filesystem.
 *
 * Every line in a {@code time_in_state} file contains two numbers, separated by a single space
 * character. The first number is the frequency of the CPU used in kilohertz. The second number is
 * the time spent in this frequency. In the {@code time_in_state} file, this is given in 10s of
 * milliseconds, but this class returns in milliseconds. This can be per user, process, or thread
 * depending on which {@code time_in_state} file is used.
 *
 * For example, a {@code time_in_state} file would look like this:
 * <pre>
 *   300000 3
 *   364800 0
 *   ...
 *   1824000 0
 *   1900800 1
 * </pre>
 *
 * This file would indicate that the CPU has spent 30 milliseconds at frequency 300,000KHz (300Mhz)
 * and 10 milliseconds at frequency 1,900,800KHz (1.9GHz).
 */
public class ProcTimeInStateReader {
    private static final String TAG = "ProcTimeInStateReader";

    /**
     * The format of a single line of the {@code time_in_state} file that exports the frequency
     * values
     */
    private static final int[] TIME_IN_STATE_LINE_FREQUENCY_FORMAT = {
            Process.PROC_OUT_LONG | Process.PROC_SPACE_TERM,
            Process.PROC_NEWLINE_TERM,
    };

    /**
     * The format of a single line of the {@code time_in_state} file that exports the time values
     */
    private static final int[] TIME_IN_STATE_LINE_TIME_FORMAT = {
            Process.PROC_SPACE_TERM,
            Process.PROC_OUT_LONG | Process.PROC_NEWLINE_TERM,
    };

    /**
     * The format of the {@code time_in_state} file, defined using {@link Process}'s {@code
     * PROC_OUT_LONG} and related variables
     *
     * Defined on first successful read of {@code time_in_state} file.
     */
    private int[] mTimeInStateTimeFormat;

    /**
     * The frequencies reported in each {@code time_in_state} file
     *
     * Defined on first successful read of {@code time_in_state} file.
     */
    private long[] mFrequenciesKhz;

    /**
     * @param initialTimeInStateFile the file to base the format of the frequency files on, and to
     * read frequencies from. Expected to be in the same format as all other {@code time_in_state}
     * files, and contain the same frequencies.
     * @throws IOException if reading the initial {@code time_in_state} file failed
     */
    public ProcTimeInStateReader(Path initialTimeInStateFile) throws IOException {
        initializeTimeInStateFormat(initialTimeInStateFile);
    }

    /**
     * Read the CPU usages from a file
     *
     * @param timeInStatePath path where the CPU usages are read from
     * @return list of CPU usage times from the file. These correspond to the CPU frequencies given
     * by {@link ProcTimeInStateReader#getFrequenciesKhz}
     */
    @Nullable
    public long[] getUsageTimesMillis(final Path timeInStatePath) {
        // Read in the time_in_state file
        final long[] readLongs = new long[mFrequenciesKhz.length];
        final boolean readSuccess = Process.readProcFile(
                timeInStatePath.toString(),
                mTimeInStateTimeFormat,
                null, readLongs, null);
        if (!readSuccess) {
            return null;
        }
        // Usage time is given in 10ms, so convert to ms
        for (int i = 0; i < readLongs.length; i++) {
            readLongs[i] *= 10;
        }
        return readLongs;
    }

    /**
     * Get the frequencies found in each {@code time_in_state} file
     *
     * @return list of CPU frequencies. These correspond to the CPU times given by {@link
     * ProcTimeInStateReader#getUsageTimesMillis(Path)}()}.
     */
    @Nullable
    public long[] getFrequenciesKhz() {
        return mFrequenciesKhz;
    }

    /**
     * Set the {@link #mTimeInStateTimeFormat} and {@link #mFrequenciesKhz} variables based on the
     * an input file. If the file is empty, these variables aren't set
     *
     * This needs to be run once on the first invocation of {@link #getUsageTimesMillis(Path)}. This
     * is because we need to know how many frequencies are available in order to parse time
     * {@code time_in_state} file using {@link Process#readProcFile}, which only accepts
     * fixed-length formats. Also, as the frequencies do not change between {@code time_in_state}
     * files, we read and store them here.
     *
     * @param timeInStatePath the input file to base the format off of
     */
    private void initializeTimeInStateFormat(final Path timeInStatePath) throws IOException {
        // Read the bytes of the `time_in_state` file
        byte[] timeInStateBytes = Files.readAllBytes(timeInStatePath);

        // The number of lines in the `time_in_state` file is the number of frequencies available
        int numFrequencies = 0;
        for (int i = 0; i < timeInStateBytes.length; i++) {
            if (timeInStateBytes[i] == '\n') {
                numFrequencies++;
            }
        }
        if (numFrequencies == 0) {
            throw new IOException("Empty time_in_state file");
        }

        // Set `mTimeInStateTimeFormat` and `timeInStateFrequencyFormat` to the correct length, and
        // then copy in the `TIME_IN_STATE_{FREQUENCY,TIME}_LINE_FORMAT` until it's full. As we only
        // use the frequency format in this method, it is not an member variable.
        final int[] timeInStateTimeFormat =
                new int[numFrequencies * TIME_IN_STATE_LINE_TIME_FORMAT.length];
        final int[] timeInStateFrequencyFormat =
                new int[numFrequencies * TIME_IN_STATE_LINE_FREQUENCY_FORMAT.length];
        for (int i = 0; i < numFrequencies; i++) {
            System.arraycopy(
                    TIME_IN_STATE_LINE_FREQUENCY_FORMAT, 0, timeInStateFrequencyFormat,
                    i * TIME_IN_STATE_LINE_FREQUENCY_FORMAT.length,
                    TIME_IN_STATE_LINE_FREQUENCY_FORMAT.length);
            System.arraycopy(
                    TIME_IN_STATE_LINE_TIME_FORMAT, 0, timeInStateTimeFormat,
                    i * TIME_IN_STATE_LINE_TIME_FORMAT.length,
                    TIME_IN_STATE_LINE_TIME_FORMAT.length);
        }

        // Read the frequencies from the `time_in_state` file and store them, as they will be the
        // same for every `time_in_state` file
        final long[] readLongs = new long[numFrequencies];
        final boolean readSuccess = Process.parseProcLine(
                timeInStateBytes, 0, timeInStateBytes.length, timeInStateFrequencyFormat,
                null, readLongs, null);
        if (!readSuccess) {
            throw new IOException("Failed to parse time_in_state file");
        }

        mTimeInStateTimeFormat = timeInStateTimeFormat;
        mFrequenciesKhz = readLongs;
    }
}
