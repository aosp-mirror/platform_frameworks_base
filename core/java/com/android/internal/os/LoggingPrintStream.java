/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Formatter;
import java.util.Locale;

/**
 * A print stream which logs output line by line.
 *
 * {@hide}
 */
abstract class LoggingPrintStream extends PrintStream {

    private final StringBuilder builder = new StringBuilder();

    /**
     * A buffer that is initialized when raw bytes are first written to this
     * stream. It may contain the leading bytes of multi-byte characters.
     * Between writes this buffer is always ready to receive data; ie. the
     * position is at the first unassigned byte and the limit is the capacity.
     */
    private ByteBuffer encodedBytes;

    /**
     * A buffer that is initialized when raw bytes are first written to this
     * stream. Between writes this buffer is always clear; ie. the position is
     * zero and the limit is the capacity.
     */
    private CharBuffer decodedChars;

    /**
     * Decodes bytes to characters using the system default charset. Initialized
     * when raw bytes are first written to this stream.
     */
    private CharsetDecoder decoder;

    protected LoggingPrintStream() {
        super(new OutputStream() {
            public void write(int oneByte) throws IOException {
                throw new AssertionError();
            }
        });
    }

    /**
     * Logs the given line.
     */
    protected abstract void log(String line);

    @Override
    public synchronized void flush() {
        flush(true);
    }

    /**
     * Searches buffer for line breaks and logs a message for each one.
     *
     * @param completely true if the ending chars should be treated as a line
     *  even though they don't end in a line break
     */
    private void flush(boolean completely) {
        int length = builder.length();

        int start = 0;
        int nextBreak;

        // Log one line for each line break.
        while (start < length
                && (nextBreak = builder.indexOf("\n", start)) != -1) {
            log(builder.substring(start, nextBreak));
            start = nextBreak + 1;
        }

        if (completely) {
            // Log the remainder of the buffer.
            if (start < length) {
                log(builder.substring(start));
            }
            builder.setLength(0);
        } else {
            // Delete characters leading up to the next starting point.
            builder.delete(0, start);
        }
    }

    public void write(int oneByte) {
        write(new byte[] { (byte) oneByte }, 0, 1);
    }

    @Override
    public void write(byte[] buffer) {
        write(buffer, 0, buffer.length);
    }

    @Override
    public synchronized void write(byte bytes[], int start, int count) {
        if (decoder == null) {
            encodedBytes = ByteBuffer.allocate(80);
            decodedChars = CharBuffer.allocate(80);
            decoder = Charset.defaultCharset().newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        int end = start + count;
        while (start < end) {
            // copy some bytes from the array to the long-lived buffer. This
            // way, if we end with a partial character we don't lose it.
            int numBytes = Math.min(encodedBytes.remaining(), end - start);
            encodedBytes.put(bytes, start, numBytes);
            start += numBytes;

            encodedBytes.flip();
            CoderResult coderResult;
            do {
                // decode bytes from the byte buffer into the char buffer
                coderResult = decoder.decode(encodedBytes, decodedChars, false);

                // copy chars from the char buffer into our string builder
                decodedChars.flip();
                builder.append(decodedChars);
                decodedChars.clear();
            } while (coderResult.isOverflow());
            encodedBytes.compact();
        }
        flush(false);
    }

    /** Always returns false. */
    @Override
    public boolean checkError() {
        return false;
    }

    /** Ignored. */
    @Override
    protected void setError() { /* ignored */ }

    /** Ignored. */
    @Override
    public void close() { /* ignored */ }

    @Override
    public PrintStream format(String format, Object... args) {
        return format(Locale.getDefault(), format, args);
    }

    @Override
    public PrintStream printf(String format, Object... args) {
        return format(format, args);
    }

    @Override
    public PrintStream printf(Locale l, String format, Object... args) {
        return format(l, format, args);
    }

    private final Formatter formatter = new Formatter(builder, null);

    @Override
    public synchronized PrintStream format(
            Locale l, String format, Object... args) {
        if (format == null) {
            throw new NullPointerException("format");
        }

        formatter.format(l, format, args);
        flush(false);
        return this;
    }

    @Override
    public synchronized void print(char[] charArray) {
        builder.append(charArray);
        flush(false);
    }

    @Override
    public synchronized void print(char ch) {
        builder.append(ch);
        if (ch == '\n') {
            flush(false);
        }
    }

    @Override
    public synchronized void print(double dnum) {
        builder.append(dnum);
    }

    @Override
    public synchronized void print(float fnum) {
        builder.append(fnum);
    }

    @Override
    public synchronized void print(int inum) {
        builder.append(inum);
    }

    @Override
    public synchronized void print(long lnum) {
        builder.append(lnum);
    }

    @Override
    public synchronized void print(Object obj) {
        builder.append(obj);
        flush(false);
    }

    @Override
    public synchronized void print(String str) {
        builder.append(str);
        flush(false);
    }

    @Override
    public synchronized void print(boolean bool) {
        builder.append(bool);
    }

    @Override
    public synchronized void println() {
        flush(true);
    }

    @Override
    public synchronized void println(char[] charArray) {
        builder.append(charArray);
        flush(true);
    }

    @Override
    public synchronized void println(char ch) {
        builder.append(ch);
        flush(true);
    }

    @Override
    public synchronized void println(double dnum) {
        builder.append(dnum);
        flush(true);
    }

    @Override
    public synchronized void println(float fnum) {
        builder.append(fnum);
        flush(true);
    }

    @Override
    public synchronized void println(int inum) {
        builder.append(inum);
        flush(true);
    }

    @Override
    public synchronized void println(long lnum) {
        builder.append(lnum);
        flush(true);
    }

    @Override
    public synchronized void println(Object obj) {
        builder.append(obj);
        flush(true);
    }

    @Override
    public synchronized void println(String s) {
        if (builder.length() == 0 && s != null) {
            // Optimization for a simple println.
            int length = s.length();

            int start = 0;
            int nextBreak;

            // Log one line for each line break.
            while (start < length
                    && (nextBreak = s.indexOf('\n', start)) != -1) {
                log(s.substring(start, nextBreak));
                start = nextBreak + 1;
            }

            if (start < length) {
                log(s.substring(start));
            }
        } else {
            builder.append(s);
            flush(true);
        }
    }

    @Override
    public synchronized void println(boolean bool) {
        builder.append(bool);
        flush(true);
    }

    @Override
    public synchronized PrintStream append(char c) {
        print(c);
        return this;
    }

    @Override
    public synchronized PrintStream append(CharSequence csq) {
        builder.append(csq);
        flush(false);
        return this;
    }

    @Override
    public synchronized PrintStream append(
            CharSequence csq, int start, int end) {
        builder.append(csq, start, end);
        flush(false);
        return this;
    }
}
