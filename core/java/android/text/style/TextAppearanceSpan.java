/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.LeakyTypefaceStorage;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.os.LocaleList;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Sets the text appearance using the given
 * {@link android.R.styleable#TextAppearance TextAppearance} attributes.
 * By default {@link TextAppearanceSpan} only changes the specified attributes in XML.
 * {@link android.R.styleable#TextAppearance_textColorHighlight textColorHighlight},
 * {@link android.R.styleable#TextAppearance_textColorHint textColorHint},
 * {@link android.R.styleable#TextAppearance_textAllCaps textAllCaps} and
 * {@link android.R.styleable#TextAppearance_fallbackLineSpacing fallbackLineSpacing}
 * are not supported by {@link TextAppearanceSpan}.
 *
 * {@see android.widget.TextView#setTextAppearance(int)}
 *
 * @attr ref android.R.styleable#TextAppearance_fontFamily
 * @attr ref android.R.styleable#TextAppearance_textColor
 * @attr ref android.R.styleable#TextAppearance_textColorLink
 * @attr ref android.R.styleable#TextAppearance_textFontWeight
 * @attr ref android.R.styleable#TextAppearance_textSize
 * @attr ref android.R.styleable#TextAppearance_textStyle
 * @attr ref android.R.styleable#TextAppearance_typeface
 * @attr ref android.R.styleable#TextAppearance_shadowColor
 * @attr ref android.R.styleable#TextAppearance_shadowDx
 * @attr ref android.R.styleable#TextAppearance_shadowDy
 * @attr ref android.R.styleable#TextAppearance_shadowRadius
 * @attr ref android.R.styleable#TextAppearance_elegantTextHeight
 * @attr ref android.R.styleable#TextAppearance_letterSpacing
 * @attr ref android.R.styleable#TextAppearance_fontFeatureSettings
 * @attr ref android.R.styleable#TextAppearance_fontVariationSettings
 *
 */
public class TextAppearanceSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final String mFamilyName;
    private final int mStyle;
    private final int mTextSize;
    private final ColorStateList mTextColor;
    private final ColorStateList mTextColorLink;
    private final Typeface mTypeface;

    private final int mTextFontWeight;
    private final LocaleList mTextLocales;

    private final float mShadowRadius;
    private final float mShadowDx;
    private final float mShadowDy;
    private final int mShadowColor;

    private final boolean mHasElegantTextHeight;
    private final boolean mElegantTextHeight;
    private final boolean mHasLetterSpacing;
    private final float mLetterSpacing;

    private final String mFontFeatureSettings;
    private final String mFontVariationSettings;

    /**
     * Uses the specified TextAppearance resource to determine the
     * text appearance.  The <code>appearance</code> should be, for example,
     * <code>android.R.style.TextAppearance_Small</code>.
     */
    public TextAppearanceSpan(Context context, int appearance) {
        this(context, appearance, -1);
    }

    /**
     * Uses the specified TextAppearance resource to determine the
     * text appearance, and the specified text color resource
     * to determine the color.  The <code>appearance</code> should be,
     * for example, <code>android.R.style.TextAppearance_Small</code>,
     * and the <code>colorList</code> should be, for example,
     * <code>android.R.styleable.Theme_textColorPrimary</code>.
     */
    public TextAppearanceSpan(Context context, int appearance, int colorList) {
        ColorStateList textColor;

        TypedArray a =
            context.obtainStyledAttributes(appearance,
                                           com.android.internal.R.styleable.TextAppearance);

        textColor = a.getColorStateList(com.android.internal.R.styleable.
                                        TextAppearance_textColor);
        mTextColorLink = a.getColorStateList(com.android.internal.R.styleable.
                                        TextAppearance_textColorLink);
        mTextSize = a.getDimensionPixelSize(com.android.internal.R.styleable.
                                        TextAppearance_textSize, -1);

        mStyle = a.getInt(com.android.internal.R.styleable.TextAppearance_textStyle, 0);
        if (!context.isRestricted() && context.canLoadUnsafeResources()) {
            mTypeface = a.getFont(com.android.internal.R.styleable.TextAppearance_fontFamily);
        } else {
            mTypeface = null;
        }
        if (mTypeface != null) {
            mFamilyName = null;
        } else {
            String family = a.getString(com.android.internal.R.styleable.TextAppearance_fontFamily);
            if (family != null) {
                mFamilyName = family;
            } else {
                int tf = a.getInt(com.android.internal.R.styleable.TextAppearance_typeface, 0);

                switch (tf) {
                    case 1:
                        mFamilyName = "sans";
                        break;

                    case 2:
                        mFamilyName = "serif";
                        break;

                    case 3:
                        mFamilyName = "monospace";
                        break;

                    default:
                        mFamilyName = null;
                        break;
                }
            }
        }

        mTextFontWeight = a.getInt(com.android.internal.R.styleable
                .TextAppearance_textFontWeight, -1);

        final String localeString = a.getString(com.android.internal.R.styleable
                .TextAppearance_textLocale);
        if (localeString != null) {
            LocaleList localeList = LocaleList.forLanguageTags(localeString);
            if (!localeList.isEmpty()) {
                mTextLocales = localeList;
            } else {
                mTextLocales = null;
            }
        } else {
            mTextLocales = null;
        }

        mShadowRadius = a.getFloat(com.android.internal.R.styleable
                .TextAppearance_shadowRadius, 0.0f);
        mShadowDx = a.getFloat(com.android.internal.R.styleable
                .TextAppearance_shadowDx, 0.0f);
        mShadowDy = a.getFloat(com.android.internal.R.styleable
                .TextAppearance_shadowDy, 0.0f);
        mShadowColor = a.getInt(com.android.internal.R.styleable
                .TextAppearance_shadowColor, 0);

        mHasElegantTextHeight = a.hasValue(com.android.internal.R.styleable
                .TextAppearance_elegantTextHeight);
        mElegantTextHeight = a.getBoolean(com.android.internal.R.styleable
                .TextAppearance_elegantTextHeight, false);

        mHasLetterSpacing = a.hasValue(com.android.internal.R.styleable
                .TextAppearance_letterSpacing);
        mLetterSpacing = a.getFloat(com.android.internal.R.styleable
                .TextAppearance_letterSpacing, 0.0f);

        mFontFeatureSettings = a.getString(com.android.internal.R.styleable
                .TextAppearance_fontFeatureSettings);

        mFontVariationSettings = a.getString(com.android.internal.R.styleable
                .TextAppearance_fontVariationSettings);

        a.recycle();

        if (colorList >= 0) {
            a = context.obtainStyledAttributes(com.android.internal.R.style.Theme,
                                            com.android.internal.R.styleable.Theme);

            textColor = a.getColorStateList(colorList);
            a.recycle();
        }

        mTextColor = textColor;
    }

    /**
     * Makes text be drawn with the specified typeface, size, style,
     * and colors.
     */
    public TextAppearanceSpan(String family, int style, int size,
                              ColorStateList color, ColorStateList linkColor) {
        mFamilyName = family;
        mStyle = style;
        mTextSize = size;
        mTextColor = color;
        mTextColorLink = linkColor;
        mTypeface = null;

        mTextFontWeight = -1;
        mTextLocales = null;

        mShadowRadius = 0.0f;
        mShadowDx = 0.0f;
        mShadowDy = 0.0f;
        mShadowColor = 0;

        mHasElegantTextHeight = false;
        mElegantTextHeight = false;
        mHasLetterSpacing = false;
        mLetterSpacing = 0.0f;

        mFontFeatureSettings = null;
        mFontVariationSettings = null;
    }

    public TextAppearanceSpan(Parcel src) {
        mFamilyName = src.readString();
        mStyle = src.readInt();
        mTextSize = src.readInt();
        if (src.readInt() != 0) {
            mTextColor = ColorStateList.CREATOR.createFromParcel(src);
        } else {
            mTextColor = null;
        }
        if (src.readInt() != 0) {
            mTextColorLink = ColorStateList.CREATOR.createFromParcel(src);
        } else {
            mTextColorLink = null;
        }
        mTypeface = LeakyTypefaceStorage.readTypefaceFromParcel(src);

        mTextFontWeight = src.readInt();
        mTextLocales = src.readParcelable(LocaleList.class.getClassLoader(), android.os.LocaleList.class);

        mShadowRadius = src.readFloat();
        mShadowDx = src.readFloat();
        mShadowDy = src.readFloat();
        mShadowColor = src.readInt();

        mHasElegantTextHeight = src.readBoolean();
        mElegantTextHeight = src.readBoolean();
        mHasLetterSpacing = src.readBoolean();
        mLetterSpacing = src.readFloat();

        mFontFeatureSettings = src.readString();
        mFontVariationSettings = src.readString();
    }

    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    public int getSpanTypeIdInternal() {
        return TextUtils.TEXT_APPEARANCE_SPAN;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeString(mFamilyName);
        dest.writeInt(mStyle);
        dest.writeInt(mTextSize);
        if (mTextColor != null) {
            dest.writeInt(1);
            mTextColor.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (mTextColorLink != null) {
            dest.writeInt(1);
            mTextColorLink.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        LeakyTypefaceStorage.writeTypefaceToParcel(mTypeface, dest);

        dest.writeInt(mTextFontWeight);
        dest.writeParcelable(mTextLocales, flags);

        dest.writeFloat(mShadowRadius);
        dest.writeFloat(mShadowDx);
        dest.writeFloat(mShadowDy);
        dest.writeInt(mShadowColor);

        dest.writeBoolean(mHasElegantTextHeight);
        dest.writeBoolean(mElegantTextHeight);
        dest.writeBoolean(mHasLetterSpacing);
        dest.writeFloat(mLetterSpacing);

        dest.writeString(mFontFeatureSettings);
        dest.writeString(mFontVariationSettings);
    }

    /**
     * Returns the typeface family specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    public String getFamily() {
        return mFamilyName;
    }

    /**
     * Returns the text color specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    public ColorStateList getTextColor() {
        return mTextColor;
    }

    /**
     * Returns the link color specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    public ColorStateList getLinkTextColor() {
        return mTextColorLink;
    }

    /**
     * Returns the text size specified by this span, or <code>-1</code>
     * if it does not specify one.
     */
    public int getTextSize() {
        return mTextSize;
    }

    /**
     * Returns the text style specified by this span, or <code>0</code>
     * if it does not specify one.
     */
    public int getTextStyle() {
        return mStyle;
    }

    /**
     * Returns the text font weight specified by this span, or <code>-1</code>
     * if it does not specify one.
     */
    public int getTextFontWeight() {
        return mTextFontWeight;
    }

    /**
     * Returns the {@link android.os.LocaleList} specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    @Nullable
    public LocaleList getTextLocales() {
        return mTextLocales;
    }

    /**
     * Returns the typeface specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    @Nullable
    public Typeface getTypeface() {
        return mTypeface;
    }

    /**
     * Returns the color of the text shadow specified by this span, or <code>0</code>
     * if it does not specify one.
     */
    public int getShadowColor() {
        return mShadowColor;
    }

    /**
     * Returns the horizontal offset of the text shadow specified by this span, or <code>0.0f</code>
     * if it does not specify one.
     */
    public float getShadowDx() {
        return mShadowDx;
    }

    /**
     * Returns the vertical offset of the text shadow specified by this span, or <code>0.0f</code>
     * if it does not specify one.
     */
    public float getShadowDy() {
        return mShadowDy;
    }

    /**
     * Returns the blur radius of the text shadow specified by this span, or <code>0.0f</code>
     * if it does not specify one.
     */
    public float getShadowRadius() {
        return mShadowRadius;
    }

    /**
     * Returns the font feature settings specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    @Nullable
    public String getFontFeatureSettings() {
        return mFontFeatureSettings;
    }

    /**
     * Returns the font variation settings specified by this span, or <code>null</code>
     * if it does not specify one.
     */
    @Nullable
    public String getFontVariationSettings() {
        return mFontVariationSettings;
    }

    /**
     * Returns the value of elegant height metrics flag specified by this span,
     * or <code>false</code> if it does not specify one.
     */
    public boolean isElegantTextHeight() {
        return mElegantTextHeight;
    }

    /**
     * Returns the value of letter spacing to be added in em unit.
     * @return a letter spacing amount
     */
    public float getLetterSpacing() {
        return mLetterSpacing;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        updateMeasureState(ds);

        if (mTextColor != null) {
            ds.setColor(mTextColor.getColorForState(ds.drawableState, 0));
        }

        if (mTextColorLink != null) {
            ds.linkColor = mTextColorLink.getColorForState(ds.drawableState, 0);
        }

        if (mShadowColor != 0) {
            ds.setShadowLayer(mShadowRadius, mShadowDx, mShadowDy, mShadowColor);
        }
    }

    @Override
    public void updateMeasureState(TextPaint ds) {
        final Typeface styledTypeface;
        int style = 0;

        if (mTypeface != null) {
            style = mStyle;
            styledTypeface = Typeface.create(mTypeface, style);
        } else if (mFamilyName != null || mStyle != 0) {
            Typeface tf = ds.getTypeface();

            if (tf != null) {
                style = tf.getStyle();
            }

            style |= mStyle;

            if (mFamilyName != null) {
                styledTypeface = Typeface.create(mFamilyName, style);
            } else if (tf == null) {
                styledTypeface = Typeface.defaultFromStyle(style);
            } else {
                styledTypeface = Typeface.create(tf, style);
            }
        } else {
            styledTypeface = null;
        }

        if (styledTypeface != null) {
            final Typeface readyTypeface;
            if (mTextFontWeight >= 0) {
                final int weight = Math.min(FontStyle.FONT_WEIGHT_MAX, mTextFontWeight);
                final boolean italic = (style & Typeface.ITALIC) != 0;
                readyTypeface = ds.setTypeface(Typeface.create(styledTypeface, weight, italic));
            } else {
                readyTypeface = styledTypeface;
            }

            int fake = style & ~readyTypeface.getStyle();

            if ((fake & Typeface.BOLD) != 0) {
                ds.setFakeBoldText(true);
            }

            if ((fake & Typeface.ITALIC) != 0) {
                ds.setTextSkewX(-0.25f);
            }

            ds.setTypeface(readyTypeface);
        }

        if (mTextSize > 0) {
            ds.setTextSize(mTextSize);
        }

        if (mTextLocales != null) {
            ds.setTextLocales(mTextLocales);
        }

        if (mHasElegantTextHeight) {
            ds.setElegantTextHeight(mElegantTextHeight);
        }

        if (mHasLetterSpacing) {
            ds.setLetterSpacing(mLetterSpacing);
        }

        if (mFontFeatureSettings != null) {
            ds.setFontFeatureSettings(mFontFeatureSettings);
        }

        if (mFontVariationSettings != null) {
            ds.setFontVariationSettings(mFontVariationSettings);
        }
    }

    @Override
    public String toString() {
        return "TextAppearanceSpan{"
                + "familyName='" + getFamily() + '\''
                + ", style=" + getTextStyle()
                + ", textSize=" + getTextSize()
                + ", textColor=" + getTextColor()
                + ", textColorLink=" + getLinkTextColor()
                + ", typeface=" + getTypeface()
                + ", textFontWeight=" + getTextFontWeight()
                + ", textLocales=" + getTextLocales()
                + ", shadowRadius=" + getShadowRadius()
                + ", shadowDx=" + getShadowDx()
                + ", shadowDy=" + getShadowDy()
                + ", shadowColor=" + String.format("#%08X", getShadowColor())
                + ", elegantTextHeight=" + isElegantTextHeight()
                + ", letterSpacing=" + getLetterSpacing()
                + ", fontFeatureSettings='" + getFontFeatureSettings() + '\''
                + ", fontVariationSettings='" + getFontVariationSettings() + '\''
                + '}';
    }
}
