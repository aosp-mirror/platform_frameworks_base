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

import android.annotation.ColorInt;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.graphics.text.LineBreakConfig;
import android.inputmethodservice.InputMethodService;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputFilter;
import android.widget.TextView;

import java.util.Objects;

/**
 * Information about text appearance in an editor, passed through
 * {@link CursorAnchorInfo} for use by {@link InputMethodService}.
 *
 * @see TextView
 * @see Paint
 * @see CursorAnchorInfo.Builder#setTextAppearanceInfo(TextAppearanceInfo)
 * @see CursorAnchorInfo#getTextAppearanceInfo()
 */
public final class TextAppearanceInfo implements Parcelable {
    /**
     * The text size (in pixels) for current {@link TextView}.
     */
    private final @Px float mTextSize;

    /**
     * The LocaleList of the text.
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
    private final @IntRange(from = -1, to = FontStyle.FONT_WEIGHT_MAX) int mTextFontWeight;

    /**
     * The style (normal, bold, italic, bold|italic) of the text, see {@link Typeface}.
     */
    private final @Typeface.Style int mTextStyle;

    /**
     * Whether the transformation method applied to the current {@link TextView} is set to
     * ALL CAPS.
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
    private final @ColorInt int mTextColorHighlight;

    /**
     * The current text color.
     */
    private final @ColorInt int mTextColor;

    /**
     * The current color of the hint text.
     */
    private final @ColorInt int mTextColorHint;

    /**
     * The text color for links.
     */
    @Nullable private final ColorStateList mTextColorLink;

    /**
     * The max length of text.
     */
    private final int mMaxLength;


    public TextAppearanceInfo(@NonNull TextView textView) {
        mTextSize = textView.getTextSize();
        mTextLocales = textView.getTextLocales();
        Typeface typeface = textView.getPaint().getTypeface();
        String systemFontFamilyName = null;
        int textFontWeight = -1;
        if (typeface != null) {
            systemFontFamilyName = typeface.getSystemFontFamilyName();
            textFontWeight = typeface.getWeight();
        }
        mSystemFontFamilyName = systemFontFamilyName;
        mTextFontWeight = textFontWeight;
        mTextStyle = textView.getTypefaceStyle();
        mAllCaps = textView.isAllCaps();
        mShadowRadius = textView.getShadowRadius();
        mShadowDx = textView.getShadowDx();
        mShadowDy = textView.getShadowDy();
        mElegantTextHeight = textView.isElegantTextHeight();
        mFallbackLineSpacing = textView.isFallbackLineSpacing();
        mLetterSpacing = textView.getLetterSpacing();
        mFontFeatureSettings = textView.getFontFeatureSettings();
        mFontVariationSettings = textView.getFontVariationSettings();
        mLineBreakStyle = textView.getLineBreakStyle();
        mLineBreakWordStyle = textView.getLineBreakWordStyle();
        mTextScaleX = textView.getTextScaleX();
        mTextColorHighlight = textView.getHighlightColor();
        mTextColor = textView.getCurrentTextColor();
        mTextColorHint = textView.getCurrentHintTextColor();
        mTextColorLink = textView.getLinkTextColors();
        int maxLength = -1;
        for (InputFilter filter: textView.getFilters()) {
            if (filter instanceof InputFilter.LengthFilter) {
                maxLength = ((InputFilter.LengthFilter) filter).getMax();
                // There is at most one LengthFilter.
                break;
            }
        }
        mMaxLength = maxLength;
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
        dest.writeBoolean(mElegantTextHeight);
        dest.writeBoolean(mFallbackLineSpacing);
        dest.writeFloat(mLetterSpacing);
        dest.writeString8(mFontFeatureSettings);
        dest.writeString8(mFontVariationSettings);
        dest.writeInt(mLineBreakStyle);
        dest.writeInt(mLineBreakWordStyle);
        dest.writeFloat(mTextScaleX);
        dest.writeInt(mTextColorHighlight);
        dest.writeInt(mTextColor);
        dest.writeInt(mTextColorHint);
        dest.writeTypedObject(mTextColorLink, flags);
        dest.writeInt(mMaxLength);
    }

    private TextAppearanceInfo(@NonNull Parcel in) {
        mTextSize = in.readFloat();
        mTextLocales = LocaleList.CREATOR.createFromParcel(in);
        mAllCaps = in.readBoolean();
        mSystemFontFamilyName = in.readString8();
        mTextFontWeight = in.readInt();
        mTextStyle = in.readInt();
        mShadowDx = in.readFloat();
        mShadowDy = in.readFloat();
        mShadowRadius = in.readFloat();
        mElegantTextHeight = in.readBoolean();
        mFallbackLineSpacing = in.readBoolean();
        mLetterSpacing = in.readFloat();
        mFontFeatureSettings = in.readString8();
        mFontVariationSettings = in.readString8();
        mLineBreakStyle = in.readInt();
        mLineBreakWordStyle = in.readInt();
        mTextScaleX = in.readFloat();
        mTextColorHighlight = in.readInt();
        mTextColor = in.readInt();
        mTextColorHint = in.readInt();
        mTextColorLink = in.readTypedObject(ColorStateList.CREATOR);
        mMaxLength = in.readInt();
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
     * Returns the text size (in pixels) for current {@link TextView}.
     */
    public @Px float getTextSize() {
        return mTextSize;
    }

    /**
     * Returns the LocaleList of the text.
     */
    @NonNull
    public LocaleList getTextLocales() {
        return mTextLocales;
    }

    /**
     * Returns the font family name if the {@link Typeface} of the text is created from a
     * system font family. Returns null if no {@link Typeface} is specified, or it is not created
     * from a system font family.
     */
    @Nullable
    public String getFontFamilyName() {
        return mSystemFontFamilyName;
    }

    /**
     * Returns the weight of the text. Returns -1 when no {@link Typeface} is specified.
     */
    public @IntRange(from = -1, to = FontStyle.FONT_WEIGHT_MAX) int getTextFontWeight() {
        return mTextFontWeight;
    }

    /**
     * Returns the style (normal, bold, italic, bold|italic) of the text. Returns
     * {@link Typeface#NORMAL} when no {@link Typeface} is specified. See {@link Typeface} for
     * more information.
     */
    public @Typeface.Style int getTextStyle() {
        return mTextStyle;
    }

    /**
     * Returns whether the transformation method applied to the current {@link TextView} is set to
     * ALL CAPS.
     */
    public boolean isAllCaps() {
        return mAllCaps;
    }

    /**
     * Returns the horizontal offset (in pixels) of the text shadow.
     */
    public @Px float getShadowDx() {
        return mShadowDx;
    }

    /**
     * Returns the vertical offset (in pixels) of the text shadow.
     */
    public @Px float getShadowDy() {
        return mShadowDy;
    }

    /**
     * Returns the blur radius (in pixels) of the text shadow.
     */
    public @Px float getShadowRadius() {
        return mShadowRadius;
    }

    /**
     * Returns {@code true} if the elegant height metrics flag is set. This setting selects font
     * variants that have not been compacted to fit Latin-based vertical metrics, and also increases
     * top and bottom bounds to provide more space.
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
     */
    public @ColorInt int getTextColorHighlight() {
        return mTextColorHighlight;
    }

    /**
     * Returns the current text color.
     */
    public @ColorInt int getTextColor() {
        return mTextColor;
    }

    /**
     * Returns the current color of the hint text.
     */
    public @ColorInt int getTextColorHint() {
        return mTextColorHint;
    }

    /**
     * Returns the text color for links.
     */
    @Nullable
    public ColorStateList getTextColorLink() {
        return mTextColorLink;
    }

    /**
     * Returns the max length of text, which is used to set an input filter to constrain the text
     * length to the specified number. Returns -1 when there is no {@link InputFilter.LengthFilter}
     * in the Editor.
     */
    public int getMaxLength() {
        return mMaxLength;
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
                that.mShadowRadius, mShadowRadius) == 0 && mMaxLength == that.mMaxLength
                && mElegantTextHeight == that.mElegantTextHeight
                && mFallbackLineSpacing == that.mFallbackLineSpacing && Float.compare(
                that.mLetterSpacing, mLetterSpacing) == 0 && mLineBreakStyle == that.mLineBreakStyle
                && mLineBreakWordStyle == that.mLineBreakWordStyle
                && mTextColorHighlight == that.mTextColorHighlight && mTextColor == that.mTextColor
                && mTextColorLink.getDefaultColor() == that.mTextColorLink.getDefaultColor()
                && mTextColorHint == that.mTextColorHint && Objects.equals(
                mTextLocales, that.mTextLocales) && Objects.equals(mSystemFontFamilyName,
                that.mSystemFontFamilyName) && Objects.equals(mFontFeatureSettings,
                that.mFontFeatureSettings) && Objects.equals(mFontVariationSettings,
                that.mFontVariationSettings) && Float.compare(that.mTextScaleX, mTextScaleX) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTextSize, mTextLocales, mSystemFontFamilyName, mTextFontWeight,
                mTextStyle, mAllCaps, mShadowDx, mShadowDy, mShadowRadius, mElegantTextHeight,
                mFallbackLineSpacing, mLetterSpacing, mFontFeatureSettings, mFontVariationSettings,
                mLineBreakStyle, mLineBreakWordStyle, mTextScaleX, mTextColorHighlight, mTextColor,
                mTextColorHint, mTextColorLink, mMaxLength);
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
                + ", mElegantTextHeight=" + mElegantTextHeight
                + ", mFallbackLineSpacing=" + mFallbackLineSpacing
                + ", mLetterSpacing=" + mLetterSpacing
                + ", mFontFeatureSettings='" + mFontFeatureSettings + '\''
                + ", mFontVariationSettings='" + mFontVariationSettings + '\''
                + ", mLineBreakStyle=" + mLineBreakStyle
                + ", mLineBreakWordStyle=" + mLineBreakWordStyle
                + ", mTextScaleX=" + mTextScaleX
                + ", mTextColorHighlight=" + mTextColorHighlight
                + ", mTextColor=" + mTextColor
                + ", mTextColorHint=" + mTextColorHint
                + ", mTextColorLink=" + mTextColorLink
                + ", mMaxLength=" + mMaxLength
                + '}';
    }
}
