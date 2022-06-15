/*
 * Copyright 2021 The Android Open Source Project
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

package android.app.appsearch.util;

import android.annotation.NonNull;

/**
 * Utility for building indented strings.
 *
 * <p>This is a wrapper for {@link StringBuilder} for appending strings with indentation. The
 * indentation level can be increased by calling {@link #increaseIndentLevel()} and decreased by
 * calling {@link #decreaseIndentLevel()}.
 *
 * <p>Indentation is applied after each newline character for the given indent level.
 *
 * @hide
 */
public class IndentingStringBuilder {
    private final StringBuilder mStringBuilder = new StringBuilder();

    // Indicates whether next non-newline character should have an indent applied before it.
    private boolean mIndentNext = false;
    private int mIndentLevel = 0;

    /** Increases the indent level by one for appended strings. */
    @NonNull
    public IndentingStringBuilder increaseIndentLevel() {
        mIndentLevel++;
        return this;
    }

    /** Decreases the indent level by one for appended strings. */
    @NonNull
    public IndentingStringBuilder decreaseIndentLevel() throws IllegalStateException {
        if (mIndentLevel == 0) {
            throw new IllegalStateException("Cannot set indent level below 0.");
        }
        mIndentLevel--;
        return this;
    }

    /**
     * Appends provided {@code String} at the current indentation level.
     *
     * <p>Indentation is applied after each newline character.
     */
    @NonNull
    public IndentingStringBuilder append(@NonNull String str) {
        applyIndentToString(str);
        return this;
    }

    /**
     * Appends provided {@code Object}, represented as a {@code String}, at the current indentation
     * level.
     *
     * <p>Indentation is applied after each newline character.
     */
    @NonNull
    public IndentingStringBuilder append(@NonNull Object obj) {
        applyIndentToString(obj.toString());
        return this;
    }

    @Override
    @NonNull
    public String toString() {
        return mStringBuilder.toString();
    }

    /** Adds indent string to the {@link StringBuilder} instance for current indent level. */
    private void applyIndent() {
        for (int i = 0; i < mIndentLevel; i++) {
            mStringBuilder.append("  ");
        }
    }

    /**
     * Applies indent, for current indent level, after each newline character.
     *
     * <p>Consecutive newline characters are not indented.
     */
    private void applyIndentToString(@NonNull String str) {
        int index = str.indexOf("\n");
        if (index == 0) {
            // String begins with new line character: append newline and slide past newline.
            mStringBuilder.append("\n");
            mIndentNext = true;
            if (str.length() > 1) {
                applyIndentToString(str.substring(index + 1));
            }
        } else if (index >= 1) {
            // String contains new line character: divide string between newline, append new line,
            // and recurse on each string.
            String beforeIndentString = str.substring(0, index);
            applyIndentToString(beforeIndentString);
            mStringBuilder.append("\n");
            mIndentNext = true;
            if (str.length() > index + 1) {
                String afterIndentString = str.substring(index + 1);
                applyIndentToString(afterIndentString);
            }
        } else {
            // String does not contain newline character: append string.
            if (mIndentNext) {
                applyIndent();
                mIndentNext = false;
            }
            mStringBuilder.append(str);
        }
    }
}
