/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.text.style;

import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.text.LineBreakConfig;

import java.util.Objects;

/**
 * LineBreakSpan for changing line break style of the specific region of the text.
 */
@FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
public class LineBreakConfigSpan {
    private final LineBreakConfig mLineBreakConfig;

    /**
     * Construct a new {@link LineBreakConfigSpan}
     * @param lineBreakConfig a line break config
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public LineBreakConfigSpan(@NonNull LineBreakConfig lineBreakConfig) {
        mLineBreakConfig = lineBreakConfig;
    }

    /**
     * Gets an associated line break config.
     * @return associated line break config.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public @NonNull LineBreakConfig getLineBreakConfig() {
        return mLineBreakConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineBreakConfigSpan)) return false;
        LineBreakConfigSpan that = (LineBreakConfigSpan) o;
        return Objects.equals(mLineBreakConfig, that.mLineBreakConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakConfig);
    }

    @Override
    public String toString() {
        return "LineBreakConfigSpan{mLineBreakConfig=" + mLineBreakConfig + '}';
    }

    private static final LineBreakConfig sNoHyphenationConfig = new LineBreakConfig.Builder()
            .setHyphenation(LineBreakConfig.HYPHENATION_DISABLED)
            .build();

    /**
     * A specialized {@link LineBreakConfigSpan} that used for preventing hyphenation.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final class NoHyphenationSpan extends LineBreakConfigSpan {
        /**
         * Construct a new {@link NoHyphenationSpan}.
         */
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
        public NoHyphenationSpan() {
            super(sNoHyphenationConfig);
        }
    }
}
