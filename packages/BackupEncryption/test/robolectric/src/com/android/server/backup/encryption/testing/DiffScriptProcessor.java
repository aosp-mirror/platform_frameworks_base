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

package com.android.server.backup.encryption.testing;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.regex.Pattern;

/**
 * To be used as part of a fake backup server. Processes a Scotty diff script.
 *
 * <p>A Scotty diff script consists of an ASCII line denoting a command, optionally followed by a
 * range of bytes. Command format is either
 *
 * <ul>
 *   <li>A single 64-bit integer, followed by a new line: this denotes that the given number of
 *       bytes are to follow in the stream. These bytes should be written directly to the new file.
 *   <li>Two 64-bit integers, separated by a hyphen, followed by a new line: this says that the
 *       given range of bytes from the original file ought to be copied into the new file.
 * </ul>
 */
public class DiffScriptProcessor {

    private static final int COPY_BUFFER_SIZE = 1024;

    private static final String READ_MODE = "r";
    private static final Pattern VALID_COMMAND_PATTERN = Pattern.compile("^\\d+(-\\d+)?$");

    private final File mInput;
    private final File mOutput;
    private final long mInputLength;

    /**
     * A new instance, with {@code input} as previous file, and {@code output} as new file.
     *
     * @param input Previous file from which ranges of bytes are to be copied. This file should be
     *     immutable.
     * @param output Output file, to which the new data should be written.
     * @throws IllegalArgumentException if input does not exist.
     */
    public DiffScriptProcessor(File input, File output) {
        checkArgument(input.exists(), "input file did not exist.");
        mInput = input;
        mInputLength = input.length();
        mOutput = checkNotNull(output);
    }

    public void process(InputStream diffScript) throws IOException, MalformedDiffScriptException {
        RandomAccessFile randomAccessInput = new RandomAccessFile(mInput, READ_MODE);

        try (FileOutputStream outputStream = new FileOutputStream(mOutput)) {
            while (true) {
                Optional<String> commandString = readCommand(diffScript);
                if (!commandString.isPresent()) {
                    return;
                }
                Command command = Command.parse(commandString.get());

                if (command.mIsRange) {
                    checkFileRange(command.mCount, command.mLimit);
                    copyRange(randomAccessInput, outputStream, command.mCount, command.mLimit);
                } else {
                    long bytesCopied = copyBytes(diffScript, outputStream, command.mCount);
                    if (bytesCopied < command.mCount) {
                        throw new MalformedDiffScriptException(
                                String.format(
                                        Locale.US,
                                        "Command to copy %d bytes from diff script, but only %d"
                                            + " bytes available",
                                        command.mCount,
                                        bytesCopied));
                    }
                    if (diffScript.read() != '\n') {
                        throw new MalformedDiffScriptException("Expected new line after bytes.");
                    }
                }
            }
        }
    }

    private void checkFileRange(long start, long end) throws MalformedDiffScriptException {
        if (end < start) {
            throw new MalformedDiffScriptException(
                    String.format(
                            Locale.US,
                            "Command to copy %d-%d bytes from original file, but %2$d < %1$d.",
                            start,
                            end));
        }

        if (end >= mInputLength) {
            throw new MalformedDiffScriptException(
                    String.format(
                            Locale.US,
                            "Command to copy %d-%d bytes from original file, but file is only %d"
                                + " bytes long.",
                            start,
                            end,
                            mInputLength));
        }
    }

    /**
     * Reads a command from the input stream.
     *
     * @param inputStream The input.
     * @return Optional of command, or empty if EOF.
     */
    private static Optional<String> readCommand(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        int b;
        while (!isEndOfCommand(b = inputStream.read())) {
            byteArrayOutputStream.write(b);
        }

        byte[] bytes = byteArrayOutputStream.toByteArray();
        if (bytes.length == 0) {
            return Optional.empty();
        } else {
            return Optional.of(new String(bytes, UTF_8));
        }
    }

    /**
     * If the given output from {@link InputStream#read()} is the end of a command - i.e., a new
     * line or the EOF.
     *
     * @param b The byte or -1.
     * @return {@code true} if ends the command.
     */
    private static boolean isEndOfCommand(int b) {
        return b == -1 || b == '\n';
    }

    /**
     * Copies {@code n} bytes from {@code inputStream} to {@code outputStream}.
     *
     * @return The number of bytes copied.
     * @throws IOException if there was a problem reading or writing.
     */
    private static long copyBytes(InputStream inputStream, OutputStream outputStream, long n)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0;
        while (n - copied > COPY_BUFFER_SIZE) {
            long read = copyBlock(inputStream, outputStream, buffer, COPY_BUFFER_SIZE);
            if (read <= 0) {
                return copied;
            }
        }
        while (n - copied > 0) {
            copied += copyBlock(inputStream, outputStream, buffer, (int) (n - copied));
        }
        return copied;
    }

    private static long copyBlock(
            InputStream inputStream, OutputStream outputStream, byte[] buffer, int size)
            throws IOException {
        int read = inputStream.read(buffer, 0, size);
        outputStream.write(buffer, 0, read);
        return read;
    }

    /**
     * Copies the given range of bytes from the input file to the output stream.
     *
     * @param input The input file.
     * @param output The output stream.
     * @param start Start position in the input file.
     * @param end End position in the output file (inclusive).
     * @throws IOException if there was a problem reading or writing.
     */
    private static void copyRange(RandomAccessFile input, OutputStream output, long start, long end)
            throws IOException {
        input.seek(start);

        // Inefficient but obviously correct. If tests become slow, optimize.
        for (; start <= end; start++) {
            output.write(input.read());
        }
    }

    /** Error thrown for a malformed diff script. */
    public static class MalformedDiffScriptException extends Exception {
        public MalformedDiffScriptException(String message) {
            super(message);
        }
    }

    /**
     * A command telling the processor either to insert n bytes, which follow, or copy n-m bytes
     * from the original file.
     */
    private static class Command {
        private final long mCount;
        private final long mLimit;
        private final boolean mIsRange;

        private Command(long count, long limit, boolean isRange) {
            mCount = count;
            mLimit = limit;
            mIsRange = isRange;
        }

        /**
         * Attempts to parse the command string into a usable structure.
         *
         * @param command The command string, without a new line at the end.
         * @throws MalformedDiffScriptException if the command is not a valid diff script command.
         * @return The parsed command.
         */
        private static Command parse(String command) throws MalformedDiffScriptException {
            if (!VALID_COMMAND_PATTERN.matcher(command).matches()) {
                throw new MalformedDiffScriptException("Bad command: " + command);
            }

            Scanner commandScanner = new Scanner(command);
            commandScanner.useDelimiter("-");
            long n = commandScanner.nextLong();
            if (!commandScanner.hasNextLong()) {
                return new Command(n, 0L, /*isRange=*/ false);
            }
            long m = commandScanner.nextLong();
            return new Command(n, m, /*isRange=*/ true);
        }
    }
}
