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

import java.io.PrintWriter;
import java.io.Writer;

/**
 * Lightweight wrapper around {@link PrintWriter} that automatically indents
 * newlines based on internal state. Delays writing indent until first actual
 * write on a newline, enabling indent modification after newline.
 */
public class IndentingPrintWriter extends PrintWriter {
    private final String mIndent;

    private StringBuilder mBuilder = new StringBuilder();
    private String mCurrent = new String();
    private boolean mEmptyLine = true;

    public IndentingPrintWriter(Writer writer, String indent) {
        super(writer);
        mIndent = indent;
    }

    public void increaseIndent() {
        mBuilder.append(mIndent);
        mCurrent = mBuilder.toString();
    }

    public void decreaseIndent() {
        mBuilder.delete(0, mIndent.length());
        mCurrent = mBuilder.toString();
    }

    @Override
    public void println() {
        super.println();
        mEmptyLine = true;
    }

    @Override
    public void write(char[] buf, int offset, int count) {
        if (mEmptyLine) {
            mEmptyLine = false;
            super.print(mCurrent);
        }
        super.write(buf, offset, count);
    }
}
