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

import android.compat.annotation.UnsupportedAppUsage;

import java.io.Writer;

/**
 * @deprecated Use {@link android.util.IndentingPrintWriter}
 */
@Deprecated
public class IndentingPrintWriter extends android.util.IndentingPrintWriter {

    @UnsupportedAppUsage
    public IndentingPrintWriter(Writer writer, String singleIndent) {
        super(writer, singleIndent, -1);
    }

    public IndentingPrintWriter(Writer writer, String singleIndent, int wrapLength) {
        super(writer, singleIndent, wrapLength);
    }

    public IndentingPrintWriter(Writer writer, String singleIndent, String prefix, int wrapLength) {
        super(writer, singleIndent, prefix, wrapLength);
    }

    public IndentingPrintWriter setIndent(String indent) {
        super.setIndent(indent);
        return this;
    }

    public IndentingPrintWriter setIndent(int indent) {
        super.setIndent(indent);
        return this;
    }

    @UnsupportedAppUsage
    public IndentingPrintWriter increaseIndent() {
        super.increaseIndent();
        return this;
    }

    @UnsupportedAppUsage
    public IndentingPrintWriter decreaseIndent() {
        super.decreaseIndent();
        return this;
    }

    public IndentingPrintWriter printPair(String key, Object value) {
        super.print(key, value);
        return this;
    }

    public IndentingPrintWriter printPair(String key, Object[] value) {
        super.print(key, value);
        return this;
    }

    public IndentingPrintWriter printHexPair(String key, int value) {
        super.printHexInt(key, value);
        return this;
    }
}
