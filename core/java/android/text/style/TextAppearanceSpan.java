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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.LeakyTypefaceStorage;
import android.graphics.Typeface;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Sets the text color, size, style, and typeface to match a TextAppearance
 * resource.
 */
public class TextAppearanceSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final String mFamilyName;
    private final int mStyle;
    private final int mTextSize;
    private final ColorStateList mTextColor;
    private final ColorStateList mTextColorLink;
    private final Typeface mTypeface;

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

    @Override
    public void updateDrawState(TextPaint ds) {
        updateMeasureState(ds);

        if (mTextColor != null) {
            ds.setColor(mTextColor.getColorForState(ds.drawableState, 0));
        }

        if (mTextColorLink != null) {
            ds.linkColor = mTextColorLink.getColorForState(ds.drawableState, 0);
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
            int fake = style & ~styledTypeface.getStyle();

            if ((fake & Typeface.BOLD) != 0) {
                ds.setFakeBoldText(true);
            }

            if ((fake & Typeface.ITALIC) != 0) {
                ds.setTextSkewX(-0.25f);
            }

            ds.setTypeface(styledTypeface);
        }

        if (mTextSize > 0) {
            ds.setTextSize(mTextSize);
        }
    }
}
