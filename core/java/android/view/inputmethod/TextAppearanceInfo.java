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

package android.view.inputmethod;

import static android.graphics.Typeface.NORMAL;

import android.annotation.ColorInt;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.graphics.text.LineBreakConfig;
import android.inputmethodservice.InputMethodService;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.TransformationMethod;
import android.text.style.CharacterStyle;
import android.widget.TextView;

import java.util.Objects;

/**
 * Information about text appearance in an editor, passed through
 * {@link CursorAnchorInfo} for use by {@link InputMethodService}.
 * @see TextView
 * @see Paint
 * @see CursorAnchorInfo.Builder#setTextAppearanceInfo(TextAppearanceInfo)
 * @see CursorAnchorInfo#getTextAppearanceInfo()
 */
public final class TextAppearanceInfo implements Parcelable {
    /**
     * The text size (in pixels) for current editor.
     */
    private final @Px float mTextSize;

    /**
     * The {@link LocaleList} of the text.
     */
    @NonNull private final LocaleList mTextLocales;

    /**
     * The font family name if the {@link Typeface} of the text is created from a system font
     * family, otherwise this value should be null.
     */
    @Nullable private final String mSystemFontFamilyName;

    /**
     * The weight of the text.
     */
    @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED, to = FontStyle.FONT_WEIGHT_MAX)
    private final int mTextFontWeight;

    /**
     * The style (normal, bold, italic, bold|italic) of the text, see {@link Typeface}.
     */
    private final @Typeface.Style int mTextStyle;

    /**
     * Whether the transformation method applied to the current editor is set to all caps.
     */
    private final boolean mAllCaps;

    /**
     * The horizontal offset (in pixels) of the text shadow.
     */
    private final @Px float mShadowDx;

    /**
     * The vertical offset (in pixels) of the text shadow.
     */
    private final @Px float mShadowDy;

    /**
     * The blur radius (in pixels) of the text shadow.
     */
    private final @Px float mShadowRadius;

    /**
     * The shadow color of the text shadow.
     */
    private final @ColorInt int mShadowColor;

    /**
     * The elegant text height, especially for less compacted complex script text.
     */
    private final boolean mElegantTextHeight;

    /**
     * Whether to expand linespacing based on fallback fonts.
     */
    private final boolean mFallbackLineSpacing;

    /**
     * The text letter-spacing (in ems), which determines the spacing between characters.
     */
    private final float mLetterSpacing;

    /**
     * The font feature settings.
     */
    @Nullable private final String mFontFeatureSettings;

    /**
     * The font variation settings.
     */
    @Nullable private final String mFontVariationSettings;

    /**
     * The line-break strategies for text wrapping.
     */
    private final @LineBreakConfig.LineBreakStyle int mLineBreakStyle;

    /**
     * The line-break word strategies for text wrapping.
     */
    private final @LineBreakConfig.LineBreakWordStyle int mLineBreakWordStyle;

    /**
     * The extent by which text should be stretched horizontally. Returns 1.0 if not specified.
     */
    private final float mTextScaleX;

    /**
     * The color of the text selection highlight.
     */
    private final @ColorInt int mHighlightTextColor;

    /**
     * The current text color of the editor.
     */
    private final @ColorInt int mTextColor;

    /**
     *  The current color of the hint text.
     */
    private final @ColorInt int mHintTextColor;

    /**
     * The text color used to paint the links in the editor.
     */
    private final @ColorInt int mLinkTextColor;

    private TextAppearanceInfo(@NonNull final TextAppearanceInfo.Builder builder) {
        mTextSize = builder.mTextSize;
        mTextLocales = builder.mTextLocales;
        mSystemFontFamilyName = builder.mSystemFontFamilyName;
        mTextFontWeight = builder.mTextFontWeight;
        mTextStyle = builder.mTextStyle;
        mAllCaps = builder.mAllCaps;
        mShadowDx = builder.mShadowDx;
        mShadowDy = builder.mShadowDy;
        mShadowRadius = builder.mShadowRadius;
        mShadowColor = builder.mShadowColor;
        mElegantTextHeight = builder.mElegantTextHeight;
        mFallbackLineSpacing = builder.mFallbackLineSpacing;
        mLetterSpacing = builder.mLetterSpacing;
        mFontFeatureSettings = builder.mFontFeatureSettings;
        mFontVariationSettings = builder.mFontVariationSettings;
        mLineBreakStyle = builder.mLineBreakStyle;
        mLineBreakWordStyle = builder.mLineBreakWordStyle;
        mTextScaleX = builder.mTextScaleX;
        mHighlightTextColor = builder.mHighlightTextColor;
        mTextColor = builder.mTextColor;
        mHintTextColor = builder.mHintTextColor;
        mLinkTextColor = builder.mLinkTextColor;
    }

    /**
     * Creates a new instance of {@link TextAppearanceInfo} by extracting text appearance from the
     * character before cursor in the target {@link TextView}.
     * @param textView the target {@link TextView}.
     * @return the new instance of {@link TextAppearanceInfo}.
     * @hide
     */
    @NonNull
    public static TextAppearanceInfo createFromTextView(@NonNull TextView textView) {
        final int selectionStart = textView.getSelectionStart();
        final CharSequence text = textView.getText();
        TextPaint textPaint = new TextPaint();
        textPaint.set(textView.getPaint());    // Copy from textView
        if (text instanceof Spanned && text.length() > 0 && selectionStart > 0) {
            // Extract the CharacterStyle spans that changes text appearance in the character before
            // cursor.
            Spanned spannedText = (Spanned) text;
            int lastCh = selectionStart - 1;
            CharacterStyle[] spans = spannedText.getSpans(lastCh, lastCh, CharacterStyle.class);
            if (spans != null) {
                for (CharacterStyle span: spans) {
                    // Exclude spans that end at lastCh
                    if (spannedText.getSpanStart(span) <= lastCh
                            && lastCh < spannedText.getSpanEnd(span)) {
                        span.updateDrawState(textPaint); // Override the TextPaint
                    }
                }
            }
        }
        Typeface typeface = textPaint.getTypeface();
        String systemFontFamilyName = null;
        int textWeight = FontStyle.FONT_WEIGHT_UNSPECIFIED;
        int textStyle = Typeface.NORMAL;
        if (typeface != null) {
            systemFontFamilyName = typeface.getSystemFontFamilyName();
            textWeight = typeface.getWeight();
            textStyle = typeface.getStyle();
        }
        TextAppearanceInfo.Builder builder = new TextAppearanceInfo.Builder();
        builder.setTextSize(textPaint.getTextSize())
                .setTextLocales(textPaint.getTextLocales())
                .setSystemFontFamilyName(systemFontFamilyName)
                .setTextFontWeight(textWeight)
                .setTextStyle(textStyle)
                .setShadowDx(textPaint.getShadowLayerDx())
                .setShadowDy(textPaint.getShadowLayerDy())
                .setShadowRadius(textPaint.getShadowLayerRadius())
                .setShadowColor(textPaint.getShadowLayerColor())
                .setElegantTextHeight(textPaint.isElegantTextHeight())
                .setLetterSpacing(textPaint.getLetterSpacing())
                .setFontFeatureSettings(textPaint.getFontFeatureSettings())
                .setFontVariationSettings(textPaint.getFontVariationSettings())
                .setTextScaleX(textPaint.getTextScaleX())
                // When there is a hint text (text length is 0), the text color should be the normal
                // text color rather than hint text color.
                .setTextColor(text.length() == 0
                        ? textView.getCurrentTextColor() : textPaint.getColor())
                .setLinkTextColor(textPaint.linkColor)
                .setAllCaps(textView.isAllCaps())
                .setFallbackLineSpacing(textView.isFallbackLineSpacing())
                .setLineBreakStyle(textView.getLineBreakStyle())
                .setLineBreakWordStyle(textView.getLineBreakWordStyle())
                .setHighlightTextColor(textView.getHighlightColor())
                .setHintTextColor(textView.getCurrentHintTextColor());
        return builder.build();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(mTextSize);
        mTextLocales.writeToParcel(dest, flags); // NonNull
        dest.writeBoolean(mAllCaps);
        dest.writeString8(mSystemFontFamilyName);
        dest.writeInt(mTextFontWeight);
        dest.writeInt(mTextStyle);
        dest.writeFloat(mShadowDx);
        dest.writeFloat(mShadowDy);
        dest.writeFloat(mShadowRadius);
        dest.writeInt(mShadowColor);
        dest.writeBoolean(mElegantTextHeight);
        dest.writeBoolean(mFallbackLineSpacing);
        dest.writeFloat(mLetterSpacing);
        dest.writeString8(mFontFeatureSettings);
        dest.writeString8(mFontVariationSettings);
        dest.writeInt(mLineBreakStyle);
        dest.writeInt(mLineBreakWordStyle);
        dest.writeFloat(mTextScaleX);
        dest.writeInt(mHighlightTextColor);
        dest.writeInt(mTextColor);
        dest.writeInt(mHintTextColor);
        dest.writeInt(mLinkTextColor);
    }

    TextAppearanceInfo(@NonNull Parcel in) {
        mTextSize = in.readFloat();
        mTextLocales = LocaleList.CREATOR.createFromParcel(in);
        mAllCaps = in.readBoolean();
        mSystemFontFamilyName = in.readString8();
        mTextFontWeight = in.readInt();
        mTextStyle = in.readInt();
        mShadowDx = in.readFloat();
        mShadowDy = in.readFloat();
        mShadowRadius = in.readFloat();
        mShadowColor = in.readInt();
        mElegantTextHeight = in.readBoolean();
        mFallbackLineSpacing = in.readBoolean();
        mLetterSpacing = in.readFloat();
        mFontFeatureSettings = in.readString8();
        mFontVariationSettings = in.readString8();
        mLineBreakStyle = in.readInt();
        mLineBreakWordStyle = in.readInt();
        mTextScaleX = in.readFloat();
        mHighlightTextColor = in.readInt();
        mTextColor = in.readInt();
        mHintTextColor = in.readInt();
        mLinkTextColor = in.readInt();
    }

    @NonNull
    public static final Creator<TextAppearanceInfo> CREATOR = new Creator<TextAppearanceInfo>() {
        @Override
        public TextAppearanceInfo createFromParcel(@NonNull Parcel in) {
            return new TextAppearanceInfo(in);
        }

        @Override
        public TextAppearanceInfo[] newArray(int size) {
            return new TextAppearanceInfo[size];
        }
    };

    /**
     * Returns the text size (in pixels) for current editor.
     */
    public @Px float getTextSize() {
        return mTextSize;
    }

    /**
     * Returns the {@link LocaleList} of the text.
     */
    @NonNull
    public LocaleList getTextLocales() {
        return mTextLocales;
    }

    /**
     * Returns the font family name if the {@link Typeface} of the text is created from a
     * system font family. Returns null if no {@link Typeface} is specified, or it is not created
     * from a system font family.
     *
     * @see Typeface#getSystemFontFamilyName()
     */
    @Nullable
    public String getSystemFontFamilyName() {
        return mSystemFontFamilyName;
    }

    /**
     * Returns the weight of the text, or {@code FontStyle#FONT_WEIGHT_UNSPECIFIED}
     * when no {@link Typeface} is specified.
     */
    @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED, to = FontStyle.FONT_WEIGHT_MAX)
    public int getTextFontWeight() {
        return mTextFontWeight;
    }

    /**
     * Returns the style (normal, bold, italic, bold|italic) of the text. Returns
     * {@link Typeface#NORMAL} when no {@link Typeface} is specified.
     *
     * @see Typeface
     */
    public @Typeface.Style int getTextStyle() {
        return mTextStyle;
    }

    /**
     * Returns whether the transformation method applied to the current editor is set to all caps.
     *
     * @see TextView#setAllCaps(boolean)
     * @see TextView#setTransformationMethod(TransformationMethod)
     */
    public boolean isAllCaps() {
        return mAllCaps;
    }

    /**
     * Returns the horizontal offset (in pixels) of the text shadow.
     *
     * @see Paint#setShadowLayer(float, float, float, int)
     */
    public @Px float getShadowDx() {
        return mShadowDx;
    }

    /**
     * Returns the vertical offset (in pixels) of the text shadow.
     *
     * @see Paint#setShadowLayer(float, float, float, int)
     */
    public @Px float getShadowDy() {
        return mShadowDy;
    }

    /**
     * Returns the blur radius (in pixels) of the text shadow.
     *
     * @see Paint#setShadowLayer(float, float, float, int)
     */
    public @Px float getShadowRadius() {
        return mShadowRadius;
    }

    /**
     * Returns the color of the text shadow.
     *
     * @see Paint#setShadowLayer(float, float, float, int)
     */
    public @ColorInt int getShadowColor() {
        return mShadowColor;
    }

    /**
     * Returns {@code true} if the elegant height metrics flag is set. This setting selects font
     * variants that have not been compacted to fit Latin-based vertical metrics, and also increases
     * top and bottom bounds to provide more space.
     *
     * @see Paint#isElegantTextHeight()
     */
    public boolean isElegantTextHeight() {
        return mElegantTextHeight;
    }

    /**
     * Returns whether to expand linespacing based on fallback fonts.
     *
     * @see TextView#setFallbackLineSpacing(boolean)
     */
    public boolean isFallbackLineSpacing() {
        return mFallbackLineSpacing;
    }

    /**
     * Returns the text letter-spacing, which determines the spacing between characters.
     * The value is in 'EM' units. Normally, this value is 0.0.
     */
    public float getLetterSpacing() {
        return mLetterSpacing;
    }

    /**
     * Returns the font feature settings. Returns null if not specified.
     *
     * @see Paint#getFontFeatureSettings()
     */
    @Nullable
    public String getFontFeatureSettings() {
        return mFontFeatureSettings;
    }

    /**
     * Returns the font variation settings. Returns null if no variation is specified.
     *
     * @see Paint#getFontVariationSettings()
     */
    @Nullable
    public String getFontVariationSettings() {
        return mFontVariationSettings;
    }

    /**
     * Returns the line-break strategies for text wrapping.
     *
     * @see TextView#setLineBreakStyle(int)
     */
    public @LineBreakConfig.LineBreakStyle int getLineBreakStyle() {
        return mLineBreakStyle;
    }

    /**
     * Returns the line-break word strategies for text wrapping.
     *
     * @see TextView#setLineBreakWordStyle(int)
     */
    public @LineBreakConfig.LineBreakWordStyle int getLineBreakWordStyle() {
        return mLineBreakWordStyle;
    }

    /**
     * Returns the extent by which text should be stretched horizontally. Returns 1.0 if not
     * specified.
     */
    public float getTextScaleX() {
        return mTextScaleX;
    }

    /**
     * Returns the color of the text selection highlight.
     *
     * @see TextView#getHighlightColor()
     */
    public @ColorInt int getHighlightTextColor() {
        return mHighlightTextColor;
    }

    /**
     * Returns the current text color of the editor.
     *
     * @see TextView#getCurrentTextColor()
     */
    public @ColorInt int getTextColor() {
        return mTextColor;
    }

    /**
     * Returns the current color of the hint text.
     *
     * @see TextView#getCurrentHintTextColor()
     */
    public @ColorInt int getHintTextColor() {
        return mHintTextColor;
    }

    /**
     * Returns the text color used to paint the links in the editor.
     *
     * @see TextView#getLinkTextColors()
     */
    public @ColorInt int getLinkTextColor() {
        return mLinkTextColor;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextAppearanceInfo)) return false;
        TextAppearanceInfo that = (TextAppearanceInfo) o;
        return Float.compare(that.mTextSize, mTextSize) == 0
                && mTextFontWeight == that.mTextFontWeight && mTextStyle == that.mTextStyle
                && mAllCaps == that.mAllCaps && Float.compare(that.mShadowDx, mShadowDx) == 0
                && Float.compare(that.mShadowDy, mShadowDy) == 0 && Float.compare(
                that.mShadowRadius, mShadowRadius) == 0 && that.mShadowColor == mShadowColor
                && mElegantTextHeight == that.mElegantTextHeight
                && mFallbackLineSpacing == that.mFallbackLineSpacing && Float.compare(
                that.mLetterSpacing, mLetterSpacing) == 0 && mLineBreakStyle == that.mLineBreakStyle
                && mLineBreakWordStyle == that.mLineBreakWordStyle
                && mHighlightTextColor == that.mHighlightTextColor
                && mTextColor == that.mTextColor
                && mLinkTextColor == that.mLinkTextColor
                && mHintTextColor == that.mHintTextColor
                && Objects.equals(mTextLocales, that.mTextLocales)
                && Objects.equals(mSystemFontFamilyName, that.mSystemFontFamilyName)
                && Objects.equals(mFontFeatureSettings, that.mFontFeatureSettings)
                && Objects.equals(mFontVariationSettings, that.mFontVariationSettings)
                && Float.compare(that.mTextScaleX, mTextScaleX) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTextSize, mTextLocales, mSystemFontFamilyName, mTextFontWeight,
                mTextStyle, mAllCaps, mShadowDx, mShadowDy, mShadowRadius, mShadowColor,
                mElegantTextHeight, mFallbackLineSpacing, mLetterSpacing, mFontFeatureSettings,
                mFontVariationSettings, mLineBreakStyle, mLineBreakWordStyle, mTextScaleX,
                mHighlightTextColor, mTextColor, mHintTextColor, mLinkTextColor);
    }

    @Override
    public String toString() {
        return "TextAppearanceInfo{"
                + "mTextSize=" + mTextSize
                + ", mTextLocales=" + mTextLocales
                + ", mSystemFontFamilyName='" + mSystemFontFamilyName + '\''
                + ", mTextFontWeight=" + mTextFontWeight
                + ", mTextStyle=" + mTextStyle
                + ", mAllCaps=" + mAllCaps
                + ", mShadowDx=" + mShadowDx
                + ", mShadowDy=" + mShadowDy
                + ", mShadowRadius=" + mShadowRadius
                + ", mShadowColor=" + mShadowColor
                + ", mElegantTextHeight=" + mElegantTextHeight
                + ", mFallbackLineSpacing=" + mFallbackLineSpacing
                + ", mLetterSpacing=" + mLetterSpacing
                + ", mFontFeatureSettings='" + mFontFeatureSettings + '\''
                + ", mFontVariationSettings='" + mFontVariationSettings + '\''
                + ", mLineBreakStyle=" + mLineBreakStyle
                + ", mLineBreakWordStyle=" + mLineBreakWordStyle
                + ", mTextScaleX=" + mTextScaleX
                + ", mHighlightTextColor=" + mHighlightTextColor
                + ", mTextColor=" + mTextColor
                + ", mHintTextColor=" + mHintTextColor
                + ", mLinkTextColor=" + mLinkTextColor
                + '}';
    }

    /**
     * Builder for {@link TextAppearanceInfo}.
     */
    public static final class Builder {
        private @Px float mTextSize = -1;
        private @NonNull LocaleList mTextLocales = LocaleList.getAdjustedDefault();
        @Nullable private String mSystemFontFamilyName = null;
        @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED, to = FontStyle.FONT_WEIGHT_MAX)
        private int mTextFontWeight = FontStyle.FONT_WEIGHT_UNSPECIFIED;
        private @Typeface.Style int mTextStyle = NORMAL;
        private boolean mAllCaps = false;
        private @Px float mShadowDx = 0;
        private @Px float mShadowDy = 0;
        private @Px float mShadowRadius = 0;
        private @ColorInt int mShadowColor = 0;
        private boolean mElegantTextHeight = false;
        private boolean mFallbackLineSpacing = false;
        private float mLetterSpacing = 0;
        @Nullable private String mFontFeatureSettings = null;
        @Nullable private String mFontVariationSettings = null;
        @LineBreakConfig.LineBreakStyle
        private int mLineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_NONE;
        @LineBreakConfig.LineBreakWordStyle
        private int mLineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;
        private float mTextScaleX = 1;
        private @ColorInt int mHighlightTextColor = 0;
        private @ColorInt int mTextColor = 0;
        private @ColorInt int mHintTextColor = 0;
        private @ColorInt int mLinkTextColor = 0;

        /**
         * Set the text size (in pixels) obtained from the current editor.
         */
        @NonNull
        public Builder setTextSize(@Px float textSize) {
            mTextSize = textSize;
            return this;
        }

        /**
         * Set the {@link LocaleList} of the text.
         */
        @NonNull
        public Builder setTextLocales(@NonNull LocaleList textLocales) {
            mTextLocales = textLocales;
            return this;
        }

        /**
         * Set the system font family name if the {@link Typeface} of the text is created from a
         * system font family.
         *
         * @see Typeface#getSystemFontFamilyName()
         */
        @NonNull
        public Builder setSystemFontFamilyName(@Nullable String systemFontFamilyName) {
            mSystemFontFamilyName = systemFontFamilyName;
            return this;
        }

        /**
         * Set the weight of the text.
         */
        @NonNull
        public Builder setTextFontWeight(
                @IntRange(from = FontStyle.FONT_WEIGHT_UNSPECIFIED,
                        to = FontStyle.FONT_WEIGHT_MAX) int textFontWeight) {
            mTextFontWeight = textFontWeight;
            return this;
        }

        /**
         * Set the style (normal, bold, italic, bold|italic) of the text.
         *
         * @see Typeface
         */
        @NonNull
        public Builder setTextStyle(@Typeface.Style int textStyle) {
            mTextStyle = textStyle;
            return this;
        }

        /**
         * Set whether the transformation method applied to the current editor  is set to all caps.
         *
         * @see TextView#setAllCaps(boolean)
         * @see TextView#setTransformationMethod(TransformationMethod)
         */
        @NonNull
        public Builder setAllCaps(boolean allCaps) {
            mAllCaps = allCaps;
            return this;
        }

        /**
         * Set the horizontal offset (in pixels) of the text shadow.
         *
         * @see Paint#setShadowLayer(float, float, float, int)
         */
        @NonNull
        public Builder setShadowDx(@Px float shadowDx) {
            mShadowDx = shadowDx;
            return this;
        }

        /**
         * Set the vertical offset (in pixels) of the text shadow.
         *
         * @see Paint#setShadowLayer(float, float, float, int)
         */
        @NonNull
        public Builder setShadowDy(@Px float shadowDy) {
            mShadowDy = shadowDy;
            return this;
        }

        /**
         * Set the blur radius (in pixels) of the text shadow.
         *
         * @see Paint#setShadowLayer(float, float, float, int)
         */
        @NonNull
        public Builder setShadowRadius(@Px float shadowRadius) {
            mShadowRadius = shadowRadius;
            return this;
        }

        /**
         * Set the color of the text shadow.
         *
         * @see Paint#setShadowLayer(float, float, float, int)
         */
        @NonNull
        public Builder setShadowColor(@ColorInt int shadowColor) {
            mShadowColor = shadowColor;
            return this;
        }

        /**
         * Set the elegant height metrics flag. This setting selects font variants that
         * have not been compacted to fit Latin-based vertical metrics, and also increases
         * top and bottom bounds to provide more space.
         *
         * @see Paint#isElegantTextHeight()
         */
        @NonNull
        public Builder setElegantTextHeight(boolean elegantTextHeight) {
            mElegantTextHeight = elegantTextHeight;
            return this;
        }

        /**
         * Set whether to expand linespacing based on fallback fonts.
         *
         * @see TextView#setFallbackLineSpacing(boolean)
         */
        @NonNull
        public Builder setFallbackLineSpacing(boolean fallbackLineSpacing) {
            mFallbackLineSpacing = fallbackLineSpacing;
            return this;
        }

        /**
         * Set the text letter-spacing, which determines the spacing between characters.
         * The value is in 'EM' units. Normally, this value is 0.0.
         */
        @NonNull
        public Builder setLetterSpacing(float letterSpacing) {
            mLetterSpacing = letterSpacing;
            return this;
        }

        /**
         * Set the font feature settings.
         *
         * @see Paint#getFontFeatureSettings()
         */
        @NonNull
        public Builder setFontFeatureSettings(@Nullable String fontFeatureSettings) {
            mFontFeatureSettings = fontFeatureSettings;
            return this;
        }

        /**
         * Set the font variation settings. Set {@code null} if no variation is specified.
         *
         * @see Paint#getFontVariationSettings()
         */
        @NonNull
        public Builder setFontVariationSettings(@Nullable String fontVariationSettings) {
            mFontVariationSettings = fontVariationSettings;
            return this;
        }

        /**
         * Set the line-break strategies for text wrapping.
         *
         * @see TextView#setLineBreakStyle(int)
         */
        @NonNull
        public Builder setLineBreakStyle(@LineBreakConfig.LineBreakStyle int lineBreakStyle) {
            mLineBreakStyle = lineBreakStyle;
            return this;
        }

        /**
         * Set the line-break word strategies for text wrapping.
         *
         * @see TextView#setLineBreakWordStyle(int)
         */
        @NonNull
        public Builder setLineBreakWordStyle(
                @LineBreakConfig.LineBreakWordStyle int lineBreakWordStyle) {
            mLineBreakWordStyle = lineBreakWordStyle;
            return this;
        }

        /**
         * Set the extent by which text should be stretched horizontally.
         */
        @NonNull
        public Builder setTextScaleX(float textScaleX) {
            mTextScaleX = textScaleX;
            return this;
        }

        /**
         * Set the color of the text selection highlight.
         *
         * @see TextView#getHighlightColor()
         */
        @NonNull
        public Builder setHighlightTextColor(@ColorInt int highlightTextColor) {
            mHighlightTextColor = highlightTextColor;
            return this;
        }

        /**
         * Set the current text color of the editor.
         *
         * @see TextView#getCurrentTextColor()
         */
        @NonNull
        public Builder setTextColor(@ColorInt int textColor) {
            mTextColor = textColor;
            return this;
        }

        /**
         * Set the current color of the hint text.
         *
         * @see TextView#getCurrentHintTextColor()
         */
        @NonNull
        public Builder setHintTextColor(@ColorInt int hintTextColor) {
            mHintTextColor = hintTextColor;
            return this;
        }

        /**
         * Set the text color used to paint the links in the editor.
         *
         * @see TextView#getLinkTextColors()
         */
        @NonNull
        public Builder setLinkTextColor(@ColorInt int linkTextColor) {
            mLinkTextColor = linkTextColor;
            return this;
        }

        /**
         * Returns {@link TextAppearanceInfo} using parameters in this
         * {@link TextAppearanceInfo.Builder}.
         */
        @NonNull
        public TextAppearanceInfo build() {
            return new TextAppearanceInfo(this);
        }
    }
}
