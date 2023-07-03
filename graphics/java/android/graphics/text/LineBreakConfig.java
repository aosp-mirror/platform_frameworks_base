/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.graphics.text;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Specifies the line-break strategies for text wrapping.
 *
 * <p>See the
 * <a href="https://www.w3.org/TR/css-text-3/#line-break-property" class="external">
 * line-break property</a> for more information.
 */
public final class LineBreakConfig {

    /**
     * No line-break rules are used for line breaking.
     */
    public static final int LINE_BREAK_STYLE_NONE = 0;

    /**
     * The least restrictive line-break rules are used for line breaking. This
     * setting is typically used for short lines.
     */
    public static final int LINE_BREAK_STYLE_LOOSE = 1;

    /**
     * The most common line-break rules are used for line breaking.
     */
    public static final int LINE_BREAK_STYLE_NORMAL = 2;

    /**
     * The most strict line-break rules are used for line breaking.
     */
    public static final int LINE_BREAK_STYLE_STRICT = 3;

    /** @hide */
    @IntDef(prefix = { "LINE_BREAK_STYLE_" }, value = {
            LINE_BREAK_STYLE_NONE, LINE_BREAK_STYLE_LOOSE, LINE_BREAK_STYLE_NORMAL,
            LINE_BREAK_STYLE_STRICT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineBreakStyle {}

    /**
     * No line-break word style is used for line breaking.
     */
    public static final int LINE_BREAK_WORD_STYLE_NONE = 0;

    /**
     * Line breaking is based on phrases, which results in text wrapping only on
     * meaningful words.
     *
     * <p>Support for this line-break word style depends on locale. If the
     * current locale does not support phrase-based text wrapping, this setting
     * has no effect.
     */
    public static final int LINE_BREAK_WORD_STYLE_PHRASE = 1;

    /** @hide */
    @IntDef(prefix = { "LINE_BREAK_WORD_STYLE_" }, value = {
        LINE_BREAK_WORD_STYLE_NONE, LINE_BREAK_WORD_STYLE_PHRASE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineBreakWordStyle {}

    /**
     * A builder for creating a {@code LineBreakConfig} instance.
     */
    public static final class Builder {
        // The line break style for the LineBreakConfig.
        private @LineBreakStyle int mLineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_NONE;

        // The line break word style for the LineBreakConfig.
        private @LineBreakWordStyle int mLineBreakWordStyle =
                LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;

        /**
         * Builder constructor.
         */
        public Builder() {
        }

        /**
         * Sets the line-break style.
         *
         * @param lineBreakStyle The new line-break style.
         * @return This {@code Builder}.
         */
        public @NonNull Builder setLineBreakStyle(@LineBreakStyle int lineBreakStyle) {
            mLineBreakStyle = lineBreakStyle;
            return this;
        }

        /**
         * Sets the line-break word style.
         *
         * @param lineBreakWordStyle The new line-break word style.
         * @return This {@code Builder}.
         */
        public @NonNull Builder setLineBreakWordStyle(@LineBreakWordStyle int lineBreakWordStyle) {
            mLineBreakWordStyle = lineBreakWordStyle;
            return this;
        }

        /**
         * Builds a {@link LineBreakConfig} instance.
         *
         * @return The {@code LineBreakConfig} instance.
         */
        public @NonNull LineBreakConfig build() {
            return new LineBreakConfig(mLineBreakStyle, mLineBreakWordStyle);
        }
    }

    /**
     * Creates a {@code LineBreakConfig} instance with the provided line break
     * parameters.
     *
     * @param lineBreakStyle The line-break style for text wrapping.
     * @param lineBreakWordStyle The line-break word style for text wrapping.
     * @return The {@code LineBreakConfig} instance.
     * @hide
     */
    public static @NonNull LineBreakConfig getLineBreakConfig(@LineBreakStyle int lineBreakStyle,
            @LineBreakWordStyle int lineBreakWordStyle) {
        LineBreakConfig.Builder builder = new LineBreakConfig.Builder();
        return builder.setLineBreakStyle(lineBreakStyle)
                .setLineBreakWordStyle(lineBreakWordStyle)
                .build();
    }

    /** @hide */
    public static final LineBreakConfig NONE =
            new Builder().setLineBreakStyle(LINE_BREAK_STYLE_NONE)
                    .setLineBreakWordStyle(LINE_BREAK_WORD_STYLE_NONE).build();

    private final @LineBreakStyle int mLineBreakStyle;
    private final @LineBreakWordStyle int mLineBreakWordStyle;

    /**
     * Constructor with line-break parameters.
     *
     * <p>Use {@link LineBreakConfig.Builder} to create the
     * {@code LineBreakConfig} instance.
     */
    private LineBreakConfig(@LineBreakStyle int lineBreakStyle,
            @LineBreakWordStyle int lineBreakWordStyle) {
        mLineBreakStyle = lineBreakStyle;
        mLineBreakWordStyle = lineBreakWordStyle;
    }

    /**
     * Gets the current line-break style.
     *
     * @return The line-break style to be used for text wrapping.
     */
    public @LineBreakStyle int getLineBreakStyle() {
        return mLineBreakStyle;
    }

    /**
     * Gets the current line-break word style.
     *
     * @return The line-break word style to be used for text wrapping.
     */
    public @LineBreakWordStyle int getLineBreakWordStyle() {
        return mLineBreakWordStyle;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (this == o) return true;
        if (!(o instanceof LineBreakConfig)) return false;
        LineBreakConfig that = (LineBreakConfig) o;
        return (mLineBreakStyle == that.mLineBreakStyle)
                && (mLineBreakWordStyle == that.mLineBreakWordStyle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakStyle, mLineBreakWordStyle);
    }
}
