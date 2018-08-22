/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Arrays;

/**
 * Lightweight wrapper around {@link PrintWriter} that automatically indents
 * newlines based on internal state. It also automatically wraps long lines
 * based on given line length.
 * <p>
 * Delays writing indent until first actual write on a newline, enabling indent
 * modification after newline.
 */
public class IndentingPrintWriter extends PrintWriter {
    private final String mSingleIndent;
    private final int mWrapLength;

    /** Mutable version of current indent */
    private StringBuilder mIndentBuilder = new StringBuilder();
    /** Cache of current {@link #mIndentBuilder} value */
    private char[] mCurrentIndent;
    /** Length of current line being built, excluding any indent */
    private int mCurrentLength;

    /**
     * Flag indicating if we're currently sitting on an empty line, and that
     * next write should be prefixed with the current indent.
     */
    private boolean mEmptyLine = true;

    private char[] mSingleChar = new char[1];

    @UnsupportedAppUsage
    public IndentingPrintWriter(Writer writer, String singleIndent) {
        this(writer, singleIndent, -1);
    }

    public IndentingPrintWriter(Writer writer, String singleIndent, int wrapLength) {
        super(writer);
        mSingleIndent = singleIndent;
        mWrapLength = wrapLength;
    }

    public IndentingPrintWriter setIndent(String indent) {
        mIndentBuilder.setLength(0);
        mIndentBuilder.append(indent);
        mCurrentIndent = null;
        return this;
    }

    public IndentingPrintWriter setIndent(int indent) {
        mIndentBuilder.setLength(0);
        for (int i = 0; i < indent; i++) {
            increaseIndent();
        }
        return this;
    }

    @UnsupportedAppUsage
    public IndentingPrintWriter increaseIndent() {
        mIndentBuilder.append(mSingleIndent);
        mCurrentIndent = null;
        return this;
    }

    @UnsupportedAppUsage
    public IndentingPrintWriter decreaseIndent() {
        mIndentBuilder.delete(0, mSingleIndent.length());
        mCurrentIndent = null;
        return this;
    }

    public IndentingPrintWriter printPair(String key, Object value) {
        print(key + "=" + String.valueOf(value) + " ");
        return this;
    }

    public IndentingPrintWriter printPair(String key, Object[] value) {
        print(key + "=" + Arrays.toString(value) + " ");
        return this;
    }

    public IndentingPrintWriter printHexPair(String key, int value) {
        print(key + "=0x" + Integer.toHexString(value) + " ");
        return this;
    }

    @Override
    public void println() {
        write('\n');
    }

    @Override
    public void write(int c) {
        mSingleChar[0] = (char) c;
        write(mSingleChar, 0, 1);
    }

    @Override
    public void write(String s, int off, int len) {
        final char[] buf = new char[len];
        s.getChars(off, len - off, buf, 0);
        write(buf, 0, len);
    }

    @Override
    public void write(char[] buf, int offset, int count) {
        final int indentLength = mIndentBuilder.length();
        final int bufferEnd = offset + count;
        int lineStart = offset;
        int lineEnd = offset;

        // March through incoming buffer looking for newlines
        while (lineEnd < bufferEnd) {
            char ch = buf[lineEnd++];
            mCurrentLength++;
            if (ch == '\n') {
                maybeWriteIndent();
                super.write(buf, lineStart, lineEnd - lineStart);
                lineStart = lineEnd;
                mEmptyLine = true;
                mCurrentLength = 0;
            }

            // Wrap if we've pushed beyond line length
            if (mWrapLength > 0 && mCurrentLength >= mWrapLength - indentLength) {
                if (!mEmptyLine) {
                    // Give ourselves a fresh line to work with
                    super.write('\n');
                    mEmptyLine = true;
                    mCurrentLength = lineEnd - lineStart;
                } else {
                    // We need more than a dedicated line, slice it hard
                    maybeWriteIndent();
                    super.write(buf, lineStart, lineEnd - lineStart);
                    super.write('\n');
                    mEmptyLine = true;
                    lineStart = lineEnd;
                    mCurrentLength = 0;
                }
            }
        }

        if (lineStart != lineEnd) {
            maybeWriteIndent();
            super.write(buf, lineStart, lineEnd - lineStart);
        }
    }

    private void maybeWriteIndent() {
        if (mEmptyLine) {
            mEmptyLine = false;
            if (mIndentBuilder.length() != 0) {
                if (mCurrentIndent == null) {
                    mCurrentIndent = mIndentBuilder.toString().toCharArray();
                }
                super.write(mCurrentIndent, 0, mCurrentIndent.length);
            }
        }
    }
}
