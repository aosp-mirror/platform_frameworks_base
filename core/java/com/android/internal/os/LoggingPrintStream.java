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

import java.io.PrintStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Formatter;

/**
 * A print stream which logs output line by line.
 *
 * {@hide}
 */
abstract class LoggingPrintStream extends PrintStream {

    private final StringBuilder builder = new StringBuilder();

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

    /*
     * We have no idea of how these bytes are encoded, so just ignore them.
     */

    /** Ignored. */
    public void write(int oneByte) {}

    /** Ignored. */
    @Override
    public void write(byte buffer[]) {}

    /** Ignored. */
    @Override
    public void write(byte bytes[], int start, int count) {}

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
        if (builder.length() == 0) {
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
