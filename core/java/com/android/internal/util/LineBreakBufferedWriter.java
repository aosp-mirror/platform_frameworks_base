/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.util;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Arrays;

/**
 * A writer that breaks up its output into chunks before writing to its out writer,
 * and which is linebreak aware, i.e., chunks will created along line breaks, if
 * possible.
 *
 * Note: this class is not thread-safe.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class LineBreakBufferedWriter extends PrintWriter {

    /**
     * A buffer to collect data until the buffer size is reached.
     *
     * Note: we manage a char[] ourselves to avoid an allocation when printing to the
     *       out writer. Otherwise a StringBuilder would have been simpler to use.
     */
    private char[] buffer;

    /**
     * The index of the first free element in the buffer.
     */
    private int bufferIndex;

    /**
     * The chunk size (=maximum buffer size) to use for this writer.
     */
    private final int bufferSize;


    /**
     * Index of the last newline character discovered in the buffer. The writer will try
     * to split there.
     */
    private int lastNewline = -1;

    /**
     * The line separator for println().
     */
    private final String lineSeparator;

    /**
     * Create a new linebreak-aware buffered writer with the given output and buffer
     * size. The initial capacity will be a default value.
     * @param out The writer to write to.
     * @param bufferSize The maximum buffer size.
     */
    public LineBreakBufferedWriter(Writer out, int bufferSize) {
        this(out, bufferSize, 16);  // 16 is the default size of a StringBuilder buffer.
    }

    /**
     * Create a new linebreak-aware buffered writer with the given output, buffer
     * size and initial capacity.
     * @param out The writer to write to.
     * @param bufferSize The maximum buffer size.
     * @param initialCapacity The initial capacity of the internal buffer.
     */
    public LineBreakBufferedWriter(Writer out, int bufferSize, int initialCapacity) {
        super(out);
        this.buffer = new char[Math.min(initialCapacity, bufferSize)];
        this.bufferIndex = 0;
        this.bufferSize = bufferSize;
        this.lineSeparator = System.getProperty("line.separator");
    }

    /**
     * Flush the current buffer. This will ignore line breaks.
     */
    @Override
    public void flush() {
        writeBuffer(bufferIndex);
        bufferIndex = 0;
        super.flush();
    }

    @Override
    public void write(int c) {
        if (bufferIndex < buffer.length) {
            buffer[bufferIndex] = (char)c;
            bufferIndex++;
            if ((char)c == '\n') {
                lastNewline = bufferIndex;
            }
        } else {
            // This should be an uncommon case, we mostly expect char[] and String. So
            // let the chunking be handled by the char[] case.
            write(new char[] { (char)c }, 0 ,1);
        }
    }

    @Override
    public void println() {
        write(lineSeparator);
    }

    @Override
    public void write(char[] buf, int off, int len) {
        while (bufferIndex + len > bufferSize) {
            // Find the next newline in the buffer, see if that's below the limit.
            // Repeat.
            int nextNewLine = -1;
            int maxLength = bufferSize - bufferIndex;
            for (int i = 0; i < maxLength; i++) {
                if (buf[off + i] == '\n') {
                    if (bufferIndex + i < bufferSize) {
                        nextNewLine = i;
                    } else {
                        break;
                    }
                }
            }

            if (nextNewLine != -1) {
                // We can add some more data.
                appendToBuffer(buf, off, nextNewLine);
                writeBuffer(bufferIndex);
                bufferIndex = 0;
                lastNewline = -1;
                off += nextNewLine + 1;
                len -= nextNewLine + 1;
            } else if (lastNewline != -1) {
                // Use the last newline.
                writeBuffer(lastNewline);
                removeFromBuffer(lastNewline + 1);
                lastNewline = -1;
            } else {
                // OK, there was no newline, break at a full buffer.
                int rest = bufferSize - bufferIndex;
                appendToBuffer(buf, off, rest);
                writeBuffer(bufferIndex);
                bufferIndex = 0;
                off += rest;
                len -= rest;
            }
        }

        // Add to the buffer, this will fit.
        if (len > 0) {
            // Add the chars, find the last newline.
            appendToBuffer(buf, off, len);
            for (int i = len - 1; i >= 0; i--) {
                if (buf[off + i] == '\n') {
                    lastNewline = bufferIndex - len + i;
                    break;
                }
            }
        }
    }

    @Override
    public void write(String s, int off, int len) {
        while (bufferIndex + len > bufferSize) {
            // Find the next newline in the buffer, see if that's below the limit.
            // Repeat.
            int nextNewLine = -1;
            int maxLength = bufferSize - bufferIndex;
            for (int i = 0; i < maxLength; i++) {
                if (s.charAt(off + i) == '\n') {
                    if (bufferIndex + i < bufferSize) {
                        nextNewLine = i;
                    } else {
                        break;
                    }
                }
            }

            if (nextNewLine != -1) {
                // We can add some more data.
                appendToBuffer(s, off, nextNewLine);
                writeBuffer(bufferIndex);
                bufferIndex = 0;
                lastNewline = -1;
                off += nextNewLine + 1;
                len -= nextNewLine + 1;
            } else if (lastNewline != -1) {
                // Use the last newline.
                writeBuffer(lastNewline);
                removeFromBuffer(lastNewline + 1);
                lastNewline = -1;
            } else {
                // OK, there was no newline, break at a full buffer.
                int rest = bufferSize - bufferIndex;
                appendToBuffer(s, off, rest);
                writeBuffer(bufferIndex);
                bufferIndex = 0;
                off += rest;
                len -= rest;
            }
        }

        // Add to the buffer, this will fit.
        if (len > 0) {
            // Add the chars, find the last newline.
            appendToBuffer(s, off, len);
            for (int i = len - 1; i >= 0; i--) {
                if (s.charAt(off + i) == '\n') {
                    lastNewline = bufferIndex - len + i;
                    break;
                }
            }
        }
    }

    /**
     * Append the characters to the buffer. This will potentially resize the buffer,
     * and move the index along.
     * @param buf The char[] containing the data.
     * @param off The start index to copy from.
     * @param len The number of characters to copy.
     */
    private void appendToBuffer(char[] buf, int off, int len) {
        if (bufferIndex + len > buffer.length) {
            ensureCapacity(bufferIndex + len);
        }
        System.arraycopy(buf, off, buffer, bufferIndex, len);
        bufferIndex += len;
    }

    /**
     * Append the characters from the given string to the buffer. This will potentially
     * resize the buffer, and move the index along.
     * @param s The string supplying the characters.
     * @param off The start index to copy from.
     * @param len The number of characters to copy.
     */
    private void appendToBuffer(String s, int off, int len) {
        if (bufferIndex + len > buffer.length) {
            ensureCapacity(bufferIndex + len);
        }
        s.getChars(off, off + len, buffer, bufferIndex);
        bufferIndex += len;
    }

    /**
     * Resize the buffer. We use the usual double-the-size plus constant scheme for
     * amortized O(1) insert. Note: we expect small buffers, so this won't check for
     * overflow.
     * @param capacity The size to be ensured.
     */
    private void ensureCapacity(int capacity) {
        int newSize = buffer.length * 2 + 2;
        if (newSize < capacity) {
            newSize = capacity;
        }
        buffer = Arrays.copyOf(buffer, newSize);
    }

    /**
     * Remove the characters up to (and excluding) index i from the buffer. This will
     * not resize the buffer, but will update bufferIndex.
     * @param i The number of characters to remove from the front.
     */
    private void removeFromBuffer(int i) {
        int rest = bufferIndex - i;
        if (rest > 0) {
            System.arraycopy(buffer, bufferIndex - rest, buffer, 0, rest);
            bufferIndex = rest;
        } else {
            bufferIndex = 0;
        }
    }

    /**
     * Helper method, write the given part of the buffer, [start,length), to the output.
     * @param length The number of characters to flush.
     */
    private void writeBuffer(int length) {
        if (length > 0) {
            super.write(buffer, 0, length);
        }
    }
}
