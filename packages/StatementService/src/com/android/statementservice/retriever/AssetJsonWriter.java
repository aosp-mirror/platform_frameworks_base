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

package com.android.statementservice.retriever;

import android.util.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;

/**
 * Creates a Json string where the order of the fields can be specified.
 */
/* package private */ final class AssetJsonWriter {

    private StringWriter mStringWriter = new StringWriter();
    private JsonWriter mWriter;
    private boolean mClosed = false;

    public AssetJsonWriter() {
        mWriter = new JsonWriter(mStringWriter);
        try {
            mWriter.beginObject();
        } catch (IOException e) {
            throw new AssertionError("Unreachable exception.");
        }
    }

    /**
     * Appends a field to the output, putting both the key and value in lowercase. Null values are
     * not written.
     */
    public void writeFieldLower(String key, String value) {
        if (mClosed) {
            throw new IllegalArgumentException(
                    "Cannot write to an object that has already been closed.");
        }

        if (value != null) {
            try {
                mWriter.name(key.toLowerCase(Locale.US));
                mWriter.value(value.toLowerCase(Locale.US));
            } catch (IOException e) {
                throw new AssertionError("Unreachable exception.");
            }
        }
    }

    /**
     * Appends an array to the output, putting both the key and values in lowercase. If {@code
     * values} is null, this field will not be written. Individual values in the list must not be
     * null.
     */
    public void writeArrayUpper(String key, List<String> values) {
        if (mClosed) {
            throw new IllegalArgumentException(
                    "Cannot write to an object that has already been closed.");
        }

        if (values != null) {
            try {
                mWriter.name(key.toLowerCase(Locale.US));
                mWriter.beginArray();
                for (String value : values) {
                    mWriter.value(value.toUpperCase(Locale.US));
                }
                mWriter.endArray();
            } catch (IOException e) {
                throw new AssertionError("Unreachable exception.");
            }
        }
    }

    /**
     * Returns the string representation of the constructed json. After calling this method, {@link
     * #writeFieldLower} can no longer be called.
     */
    public String closeAndGetString() {
        if (!mClosed) {
            try {
                mWriter.endObject();
            } catch (IOException e) {
                throw new AssertionError("Unreachable exception.");
            }
            mClosed = true;
        }
        return mStringWriter.toString();
    }
}
