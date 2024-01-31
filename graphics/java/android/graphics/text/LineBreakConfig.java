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

import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;
import static com.android.text.flags.Flags.FLAG_WORD_STYLE_AUTO;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.os.Build;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;

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
public final class LineBreakConfig implements Parcelable {
    /**
     * No hyphenation preference is specified.
     *
     * <p>
     * This is a special value of hyphenation preference indicating no hyphenation preference is
     * specified. When overriding a {@link LineBreakConfig} with another {@link LineBreakConfig}
     * with {@link Builder#merge(LineBreakConfig)} function, the hyphenation preference of
     * overridden config will be kept if the hyphenation preference of overriding config is
     * {@link #HYPHENATION_UNSPECIFIED}.
     *
     * <p>
     * <pre>
     *     val override = LineBreakConfig.Builder()
     *          .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
     *          .build();  // UNSPECIFIED if no setHyphenation is called.
     *     val config = LineBreakConfig.Builder()
     *          .setHyphenation(LineBreakConfig.HYPHENATION_DISABLED)
     *          .merge(override)
     *          .build()
     *     // Here, config has HYPHENATION_DISABLED for line break config and
     *     // LINE_BREAK_WORD_STYLE_PHRASE for line break word style.
     * </pre>
     *
     * <p>
     * This value is resolved to {@link #HYPHENATION_ENABLED} if this value is used for text
     * layout/rendering.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int HYPHENATION_UNSPECIFIED = -1;

    /**
     * The hyphenation is disabled.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int HYPHENATION_DISABLED = 0;

    /**
     * The hyphenation is enabled.
     *
     * Note: Even if the hyphenation is enabled with a line break strategy
     * {@link LineBreaker#BREAK_STRATEGY_SIMPLE}, the hyphenation will not be performed unless a
     * single word cannot meet width constraints.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int HYPHENATION_ENABLED = 1;

    /** @hide */
    @IntDef(prefix = { "HYPHENATION_" }, value = {
            HYPHENATION_UNSPECIFIED, HYPHENATION_ENABLED, HYPHENATION_DISABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Hyphenation {}

    /**
     * No line break style is specified.
     *
     * <p>
     * This is a special value of line break style indicating no style value is specified.
     * When overriding a {@link LineBreakConfig} with another {@link LineBreakConfig} with
     * {@link Builder#merge(LineBreakConfig)} function, the line break style of overridden config
     * will be kept if the line break style of overriding config is
     * {@link #LINE_BREAK_STYLE_UNSPECIFIED}.
     *
     * <pre>
     *     val override = LineBreakConfig.Builder()
     *          .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
     *          .build();  // UNSPECIFIED if no setLineBreakStyle is called.
     *     val config = LineBreakConfig.Builder()
     *          .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
     *          .merge(override)
     *          .build()
     *     // Here, config has LINE_BREAK_STYLE_STRICT for line break config and
     *     // LINE_BREAK_WORD_STYLE_PHRASE for line break word style.
     * </pre>
     *
     * <p>
     * This value is resolved to {@link #LINE_BREAK_STYLE_NONE} if the target SDK version is API
     * {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or before and this value is used for text
     * layout/rendering. This value is resolved to {@link #LINE_BREAK_STYLE_AUTO} if the target SDK
     * version is API {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} or after and this value is
     * used for text layout/rendering.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int LINE_BREAK_STYLE_UNSPECIFIED = -1;

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

    /**
     * The line break style that used for preventing automatic line breaking.
     *
     * This is useful when you want to preserve some words in the same line by using
     * {@link android.text.style.LineBreakConfigSpan} or
     * {@link android.text.style.LineBreakConfigSpan#createNoBreakSpan()} as a shorthand.
     * Note that even if this style is specified, the grapheme based line break is still performed
     * for preventing clipping text.
     *
     * @see android.text.style.LineBreakConfigSpan
     * @see android.text.style.LineBreakConfigSpan#createNoBreakSpan()
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int LINE_BREAK_STYLE_NO_BREAK = 4;

    /**
     * A special value for the line breaking style option.
     *
     * <p>
     * The auto option for the line break style set the line break style based on the locale of the
     * text rendering context. You can specify the context locale by
     * {@link android.widget.TextView#setTextLocales(LocaleList)} or
     * {@link android.graphics.Paint#setTextLocales(LocaleList)}.
     *
     * <p>
     * In the API {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, auto option does followings:
     * - If at least one locale in the locale list contains Japanese script, this option is
     * equivalent to {@link #LINE_BREAK_STYLE_STRICT}.
     * - Otherwise, this option is equivalent to {@link #LINE_BREAK_STYLE_NONE}.
     *
     * <p>
     * Note: future versions may have special line breaking style rules for other locales.
     */
    @FlaggedApi(FLAG_WORD_STYLE_AUTO)
    public static final int LINE_BREAK_STYLE_AUTO = 5;

    /** @hide */
    @IntDef(prefix = { "LINE_BREAK_STYLE_" }, value = {
            LINE_BREAK_STYLE_NONE, LINE_BREAK_STYLE_LOOSE, LINE_BREAK_STYLE_NORMAL,
            LINE_BREAK_STYLE_STRICT, LINE_BREAK_STYLE_UNSPECIFIED, LINE_BREAK_STYLE_NO_BREAK,
            LINE_BREAK_STYLE_AUTO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineBreakStyle {}

    /**
     * No line break word style is specified.
     *
     * This is a special value of line break word style indicating no style value is specified.
     * When overriding a {@link LineBreakConfig} with another {@link LineBreakConfig} with
     * {@link Builder#merge(LineBreakConfig)} function, the line break word style of overridden
     * config will be kept if the line break word style of overriding config is
     * {@link #LINE_BREAK_WORD_STYLE_UNSPECIFIED}.
     *
     * <pre>
     *     val override = LineBreakConfig.Builder()
     *          .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
     *          .build();  // UNSPECIFIED if no setLineBreakWordStyle is called.
     *     val config = LineBreakConfig.Builder()
     *          .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
     *          .merge(override)
     *          .build()
     *     // Here, config has LINE_BREAK_STYLE_STRICT for line break config and
     *     // LINE_BREAK_WORD_STYLE_PHRASE for line break word style.
     * </pre>
     *
     * This value is resolved to {@link #LINE_BREAK_WORD_STYLE_NONE} if the target SDK version is
     * API {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or before and this value is used for text
     * layout/rendering. This value is resolved to {@link #LINE_BREAK_WORD_STYLE_AUTO} if the target
     * SDK version is API {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} or after and this value is
     * used for text layout/rendering.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final int LINE_BREAK_WORD_STYLE_UNSPECIFIED = -1;

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

    /**
     * A special value for the line breaking word style option.
     *
     * <p>
     * The auto option for the line break word style does some heuristics based on locales and line
     * count.
     *
     * <p>
     * In the API {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, auto option does followings:
     * - If at least one locale in the locale list contains Korean script, this option is equivalent
     * to {@link #LINE_BREAK_WORD_STYLE_PHRASE}.
     * - If not, then if at least one locale in the locale list contains Japanese script, this
     * option is equivalent to {@link #LINE_BREAK_WORD_STYLE_PHRASE} if the result of its line
     * count is less than 5 lines.
     * - Otherwise, this option is equivalent to {@link #LINE_BREAK_WORD_STYLE_NONE}.
     *
     * <p>
     * Note: future versions may have special line breaking word style rules for other locales.
     */
    @FlaggedApi(FLAG_WORD_STYLE_AUTO)
    public static final int LINE_BREAK_WORD_STYLE_AUTO = 2;

    /** @hide */
    @IntDef(prefix = { "LINE_BREAK_WORD_STYLE_" }, value = {
        LINE_BREAK_WORD_STYLE_NONE, LINE_BREAK_WORD_STYLE_PHRASE, LINE_BREAK_WORD_STYLE_UNSPECIFIED,
            LINE_BREAK_WORD_STYLE_AUTO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineBreakWordStyle {}

    /**
     * A builder for creating a {@code LineBreakConfig} instance.
     */
    public static final class Builder {
        // The line break style for the LineBreakConfig.
        private @LineBreakStyle int mLineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_UNSPECIFIED;

        // The line break word style for the LineBreakConfig.
        private @LineBreakWordStyle int mLineBreakWordStyle =
                LineBreakConfig.LINE_BREAK_WORD_STYLE_UNSPECIFIED;

        private @Hyphenation int mHyphenation = LineBreakConfig.HYPHENATION_UNSPECIFIED;

        /**
         * Builder constructor.
         */
        public Builder() {
            reset(null);
        }

        /**
         * Merges line break config with other config
         *
         * Update the internal configurations with passed {@code config}. If the config values of
         * passed {@code config} are unspecified, the original config values are kept. For example,
         * the following code passes {@code config} that has {@link #LINE_BREAK_STYLE_UNSPECIFIED}.
         * This code generates {@link LineBreakConfig} that has line break config
         * {@link #LINE_BREAK_STYLE_STRICT}.
         *
         * <pre>
         *     val override = LineBreakConfig.Builder()
         *          .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
         *          .build();  // UNSPECIFIED if no setLineBreakStyle is called.
         *     val config = LineBreakConfig.Builder()
         *          .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
         *          .merge(override)
         *          .build()
         *     // Here, config has LINE_BREAK_STYLE_STRICT of line break config and
         *     // LINE_BREAK_WORD_STYLE_PHRASE of line break word style.
         * </pre>
         *
         * @see #LINE_BREAK_STYLE_UNSPECIFIED
         * @see #LINE_BREAK_WORD_STYLE_UNSPECIFIED
         *
         * @param config an override line break config
         * @return This {@code Builder}.
         */
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
        public @NonNull Builder merge(@NonNull LineBreakConfig config) {
            if (config.mLineBreakStyle != LINE_BREAK_STYLE_UNSPECIFIED) {
                mLineBreakStyle = config.mLineBreakStyle;
            }
            if (config.mLineBreakWordStyle != LINE_BREAK_WORD_STYLE_UNSPECIFIED) {
                mLineBreakWordStyle = config.mLineBreakWordStyle;
            }
            if (config.mHyphenation != HYPHENATION_UNSPECIFIED) {
                mHyphenation = config.mHyphenation;
            }
            return this;
        }

        /**
         * Resets this builder to the given config state.
         *
         * @param config a config value used for resetting. {@code null} is allowed. If {@code null}
         *              is passed, all configs are reset to unspecified.
         * @return This {@code Builder}.
         * @hide
         */
        public @NonNull Builder reset(@Nullable LineBreakConfig config) {
            if (config == null) {
                mLineBreakStyle = LINE_BREAK_STYLE_UNSPECIFIED;
                mLineBreakWordStyle = LINE_BREAK_WORD_STYLE_UNSPECIFIED;
                mHyphenation = HYPHENATION_UNSPECIFIED;
            } else {
                mLineBreakStyle = config.mLineBreakStyle;
                mLineBreakWordStyle = config.mLineBreakWordStyle;
                mHyphenation = config.mHyphenation;
            }
            return this;
        }

        // TODO(316208691): Revive following removed API docs.
        // Note: different from {@link #merge(LineBreakConfig)} if this function is called with
        // {@link #LINE_BREAK_STYLE_UNSPECIFIED}, the line break style is reset to
        // {@link #LINE_BREAK_STYLE_UNSPECIFIED}.
        /**
         * Sets the line-break style.
         *
         * @see <a href="https://unicode.org/reports/tr35/#UnicodeLineBreakStyleIdentifier">
         *     Unicode Line Break Style Identifier</a>
         * @see <a href="https://drafts.csswg.org/css-text/#line-break-property">
         *     CSS Line Break Property</a>
         *
         * @param lineBreakStyle The new line-break style.
         * @return This {@code Builder}.
         */
        public @NonNull Builder setLineBreakStyle(@LineBreakStyle int lineBreakStyle) {
            mLineBreakStyle = lineBreakStyle;
            return this;
        }

        // TODO(316208691): Revive following removed API docs.
        // Note: different from {@link #merge(LineBreakConfig)} method, if this function is called
        // with {@link #LINE_BREAK_WORD_STYLE_UNSPECIFIED}, the line break style is reset to
        // {@link #LINE_BREAK_WORD_STYLE_UNSPECIFIED}.
        /**
         * Sets the line-break word style.
         *
         * @see <a href="https://unicode.org/reports/tr35/#UnicodeLineBreakWordIdentifier">
         *     Unicode Line Break Word Identifier</a>
         * @see <a href="https://drafts.csswg.org/css-text/#word-break-property">
         *     CSS Word Break Property</a>
         *
         * @param lineBreakWordStyle The new line-break word style.
         * @return This {@code Builder}.
         */
        public @NonNull Builder setLineBreakWordStyle(@LineBreakWordStyle int lineBreakWordStyle) {
            mLineBreakWordStyle = lineBreakWordStyle;
            return this;
        }

        /**
         * Sets the hyphenation preference
         *
         * Note: Even if the {@link LineBreakConfig#HYPHENATION_ENABLED} is specified, the
         * hyphenation will not be performed if the {@link android.widget.TextView} or underlying
         * {@link android.text.StaticLayout}, {@link LineBreaker} are configured with
         * {@link LineBreaker#HYPHENATION_FREQUENCY_NONE}.
         *
         * Note: Even if the hyphenation is enabled with a line break strategy
         * {@link LineBreaker#BREAK_STRATEGY_SIMPLE}, the hyphenation will not be performed unless a
         * single word cannot meet width constraints.
         *
         * @param hyphenation The hyphenation preference.
         * @return This {@code Builder}.
         */
        @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
        public @NonNull Builder setHyphenation(@Hyphenation int hyphenation) {
            mHyphenation = hyphenation;
            return this;
        }

        /**
         * Builds a {@link LineBreakConfig} instance.
         *
         * This method can be called multiple times for generating multiple {@link LineBreakConfig}
         * instances.
         *
         * @return The {@code LineBreakConfig} instance.
         */
        public @NonNull LineBreakConfig build() {
            return new LineBreakConfig(mLineBreakStyle, mLineBreakWordStyle, mHyphenation);
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
    private final @Hyphenation int mHyphenation;

    /**
     * Constructor with line-break parameters.
     *
     * <p>Use {@link LineBreakConfig.Builder} to create the
     * {@code LineBreakConfig} instance.
     * @hide
     */
    public LineBreakConfig(@LineBreakStyle int lineBreakStyle,
            @LineBreakWordStyle int lineBreakWordStyle,
            @Hyphenation int hyphenation) {
        mLineBreakStyle = lineBreakStyle;
        mLineBreakWordStyle = lineBreakWordStyle;
        mHyphenation = hyphenation;
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
     * Gets the resolved line break style.
     *
     * This method never returns {@link #LINE_BREAK_STYLE_UNSPECIFIED}.
     *
     * @return The line break style.
     * @hide
     */
    public static @LineBreakStyle int getResolvedLineBreakStyle(@Nullable LineBreakConfig config) {
        final int targetSdkVersion = ActivityThread.currentApplication().getApplicationInfo()
                .targetSdkVersion;
        final int defaultStyle;
        final int vicVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        if (targetSdkVersion >= vicVersion) {
            defaultStyle = LINE_BREAK_STYLE_AUTO;
        } else {
            defaultStyle = LINE_BREAK_STYLE_NONE;
        }
        if (config == null) {
            return defaultStyle;
        }
        return config.mLineBreakStyle == LINE_BREAK_STYLE_UNSPECIFIED
                ? defaultStyle : config.mLineBreakStyle;
    }

    /**
     * Gets the current line-break word style.
     *
     * @return The line-break word style to be used for text wrapping.
     */
    public @LineBreakWordStyle int getLineBreakWordStyle() {
        return mLineBreakWordStyle;
    }

    /**
     * Gets the resolved line break style.
     *
     * This method never returns {@link #LINE_BREAK_WORD_STYLE_UNSPECIFIED}.
     *
     * @return The line break word style.
     * @hide
     */
    public static @LineBreakWordStyle int getResolvedLineBreakWordStyle(
            @Nullable LineBreakConfig config) {
        final int targetSdkVersion = ActivityThread.currentApplication().getApplicationInfo()
                .targetSdkVersion;
        final int defaultWordStyle;
        final int vicVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        if (targetSdkVersion >= vicVersion) {
            defaultWordStyle = LINE_BREAK_WORD_STYLE_AUTO;
        } else {
            defaultWordStyle = LINE_BREAK_WORD_STYLE_NONE;
        }
        if (config == null) {
            return defaultWordStyle;
        }
        return config.mLineBreakWordStyle == LINE_BREAK_WORD_STYLE_UNSPECIFIED
                ? defaultWordStyle : config.mLineBreakWordStyle;
    }

    /**
     * Returns a hyphenation preference.
     *
     * @return A hyphenation preference.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public @Hyphenation  int getHyphenation() {
        return mHyphenation;
    }

    /**
     * Returns a hyphenation preference.
     *
     * This method never returns {@link #HYPHENATION_UNSPECIFIED}.
     *
     * @return A hyphenation preference.
     * @hide
     */
    public static @Hyphenation int getResolvedHyphenation(
            @Nullable LineBreakConfig config) {
        if (config == null) {
            return HYPHENATION_ENABLED;
        }
        return config.mHyphenation == HYPHENATION_UNSPECIFIED
                ? HYPHENATION_ENABLED : config.mHyphenation;
    }


    /**
     * Generates a new {@link LineBreakConfig} instance merged with given {@code config}.
     *
     * If values of passing {@code config} are unspecified, the original values are kept. For
     * example, the following code shows how line break config is merged.
     *
     * <pre>
     *     val override = LineBreakConfig.Builder()
     *          .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
     *          .build();  // UNSPECIFIED if no setLineBreakStyle is called.
     *     val config = LineBreakConfig.Builder()
     *          .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
     *          .build();
     *
     *     val newConfig = config.merge(override)
     *     // newConfig has LINE_BREAK_STYLE_STRICT of line break style and
     *     LINE_BREAK_WORD_STYLE_PHRASE of line break word style.
     * </pre>
     *
     * @param config an overriding config.
     * @return newly created instance that is current style merged with passed config.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public @NonNull LineBreakConfig merge(@NonNull LineBreakConfig config) {
        return new LineBreakConfig(
                config.mLineBreakStyle == LINE_BREAK_STYLE_UNSPECIFIED
                        ? mLineBreakStyle : config.mLineBreakStyle,
                config.mLineBreakWordStyle == LINE_BREAK_WORD_STYLE_UNSPECIFIED
                        ? mLineBreakWordStyle : config.mLineBreakWordStyle,
                config.mHyphenation == HYPHENATION_UNSPECIFIED
                        ? mHyphenation : config.mHyphenation);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (this == o) return true;
        if (!(o instanceof LineBreakConfig)) return false;
        LineBreakConfig that = (LineBreakConfig) o;
        return (mLineBreakStyle == that.mLineBreakStyle)
                && (mLineBreakWordStyle == that.mLineBreakWordStyle)
                && (mHyphenation == that.mHyphenation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakStyle, mLineBreakWordStyle, mHyphenation);
    }

    @Override
    public String toString() {
        return "LineBreakConfig{"
                + "mLineBreakStyle=" + mLineBreakStyle
                + ", mLineBreakWordStyle=" + mLineBreakWordStyle
                + ", mHyphenation= " + mHyphenation
                + '}';
    }

    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    @Override
    public int describeContents() {
        return 0;
    }

    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLineBreakStyle);
        dest.writeInt(mLineBreakWordStyle);
        dest.writeInt(mHyphenation);
    }

    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static final @NonNull Creator<LineBreakConfig> CREATOR = new Creator<>() {

        @Override
        public LineBreakConfig createFromParcel(Parcel source) {
            final int lineBreakStyle = source.readInt();
            final int lineBreakWordStyle = source.readInt();
            final int hyphenation = source.readInt();
            return new LineBreakConfig(lineBreakStyle, lineBreakWordStyle, hyphenation);
        }

        @Override
        public LineBreakConfig[] newArray(int size) {
            return new LineBreakConfig[size];
        }
    };
}
