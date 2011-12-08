/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * Holds suggestion candidates for the text enclosed in this span.
 *
 * When such a span is edited in an EditText, double tapping on the text enclosed in this span will
 * display a popup dialog listing suggestion replacement for that text. The user can then replace
 * the original text by one of the suggestions.
 *
 * These spans should typically be created by the input method to provide correction and alternates
 * for the text.
 *
 * @see TextView#isSuggestionsEnabled()
 */
public class SuggestionSpan extends CharacterStyle implements ParcelableSpan {

    /**
     * Sets this flag if the suggestions should be easily accessible with few interactions.
     * This flag should be set for every suggestions that the user is likely to use.
     */
    public static final int FLAG_EASY_CORRECT = 0x0001;

    /**
     * Sets this flag if the suggestions apply to a misspelled word/text. This type of suggestion is
     * rendered differently to highlight the error.
     */
    public static final int FLAG_MISSPELLED = 0x0002;

    /**
     * Sets this flag if the auto correction is about to be applied to a word/text
     * that the user is typing/composing. This type of suggestion is rendered differently
     * to indicate the auto correction is happening.
     */
    public static final int FLAG_AUTO_CORRECTION = 0x0004;

    public static final String ACTION_SUGGESTION_PICKED = "android.text.style.SUGGESTION_PICKED";
    public static final String SUGGESTION_SPAN_PICKED_AFTER = "after";
    public static final String SUGGESTION_SPAN_PICKED_BEFORE = "before";
    public static final String SUGGESTION_SPAN_PICKED_HASHCODE = "hashcode";

    public static final int SUGGESTIONS_MAX_SIZE = 5;

    /*
     * TODO: Needs to check the validity and add a feature that TextView will change
     * the current IME to the other IME which is specified in SuggestionSpan.
     * An IME needs to set the span by specifying the target IME and Subtype of SuggestionSpan.
     * And the current IME might want to specify any IME as the target IME including other IMEs.
     */

    private int mFlags;
    private final String[] mSuggestions;
    private final String mLocaleString;
    private final String mNotificationTargetClassName;
    private final int mHashCode;

    private float mEasyCorrectUnderlineThickness;
    private int mEasyCorrectUnderlineColor;

    private float mMisspelledUnderlineThickness;
    private int mMisspelledUnderlineColor;

    private float mAutoCorrectionUnderlineThickness;
    private int mAutoCorrectionUnderlineColor;

    /**
     * @param context Context for the application
     * @param suggestions Suggestions for the string under the span
     * @param flags Additional flags indicating how this span is handled in TextView
     */
    public SuggestionSpan(Context context, String[] suggestions, int flags) {
        this(context, null, suggestions, flags, null);
    }

    /**
     * @param locale Locale of the suggestions
     * @param suggestions Suggestions for the string under the span
     * @param flags Additional flags indicating how this span is handled in TextView
     */
    public SuggestionSpan(Locale locale, String[] suggestions, int flags) {
        this(null, locale, suggestions, flags, null);
    }

    /**
     * @param context Context for the application
     * @param locale locale Locale of the suggestions
     * @param suggestions Suggestions for the string under the span. Only the first up to
     * {@link SuggestionSpan#SUGGESTIONS_MAX_SIZE} will be considered.
     * @param flags Additional flags indicating how this span is handled in TextView
     * @param notificationTargetClass if not null, this class will get notified when the user
     * selects one of the suggestions.
     */
    public SuggestionSpan(Context context, Locale locale, String[] suggestions, int flags,
            Class<?> notificationTargetClass) {
        final int N = Math.min(SUGGESTIONS_MAX_SIZE, suggestions.length);
        mSuggestions = Arrays.copyOf(suggestions, N);
        mFlags = flags;
        if (context != null && locale == null) {
            mLocaleString = context.getResources().getConfiguration().locale.toString();
        } else {
            mLocaleString = locale.toString();
        }

        if (notificationTargetClass != null) {
            mNotificationTargetClassName = notificationTargetClass.getCanonicalName();
        } else {
            mNotificationTargetClassName = "";
        }
        mHashCode = hashCodeInternal(mSuggestions, mLocaleString, mNotificationTargetClassName);

        initStyle(context);
    }

    private void initStyle(Context context) {
        if (context == null) {
            mMisspelledUnderlineThickness = 0;
            mEasyCorrectUnderlineThickness = 0;
            mAutoCorrectionUnderlineThickness = 0;
            mMisspelledUnderlineColor = Color.BLACK;
            mEasyCorrectUnderlineColor = Color.BLACK;
            mAutoCorrectionUnderlineColor = Color.BLACK;
            return;
        }

        int defStyle = com.android.internal.R.attr.textAppearanceMisspelledSuggestion;
        TypedArray typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyle, 0);
        mMisspelledUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mMisspelledUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);

        defStyle = com.android.internal.R.attr.textAppearanceEasyCorrectSuggestion;
        typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyle, 0);
        mEasyCorrectUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mEasyCorrectUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);

        defStyle = com.android.internal.R.attr.textAppearanceAutoCorrectionSuggestion;
        typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyle, 0);
        mAutoCorrectionUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mAutoCorrectionUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);
    }

    public SuggestionSpan(Parcel src) {
        mSuggestions = src.readStringArray();
        mFlags = src.readInt();
        mLocaleString = src.readString();
        mNotificationTargetClassName = src.readString();
        mHashCode = src.readInt();
        mEasyCorrectUnderlineColor = src.readInt();
        mEasyCorrectUnderlineThickness = src.readFloat();
        mMisspelledUnderlineColor = src.readInt();
        mMisspelledUnderlineThickness = src.readFloat();
        mAutoCorrectionUnderlineColor = src.readInt();
        mAutoCorrectionUnderlineThickness = src.readFloat();
    }

    /**
     * @return an array of suggestion texts for this span
     */
    public String[] getSuggestions() {
        return mSuggestions;
    }

    /**
     * @return the locale of the suggestions
     */
    public String getLocale() {
        return mLocaleString;
    }

    /**
     * @return The name of the class to notify. The class of the original IME package will receive
     * a notification when the user selects one of the suggestions. The notification will include
     * the original string, the suggested replacement string as well as the hashCode of this span.
     * The class will get notified by an intent that has those information.
     * This is an internal API because only the framework should know the class name.
     *
     * @hide
     */
    public String getNotificationTargetClassName() {
        return mNotificationTargetClassName;
    }

    public int getFlags() {
        return mFlags;
    }

    public void setFlags(int flags) {
        mFlags = flags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(mSuggestions);
        dest.writeInt(mFlags);
        dest.writeString(mLocaleString);
        dest.writeString(mNotificationTargetClassName);
        dest.writeInt(mHashCode);
        dest.writeInt(mEasyCorrectUnderlineColor);
        dest.writeFloat(mEasyCorrectUnderlineThickness);
        dest.writeInt(mMisspelledUnderlineColor);
        dest.writeFloat(mMisspelledUnderlineThickness);
        dest.writeInt(mAutoCorrectionUnderlineColor);
        dest.writeFloat(mAutoCorrectionUnderlineThickness);
    }

    @Override
    public int getSpanTypeId() {
        return TextUtils.SUGGESTION_SPAN;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof SuggestionSpan) {
            return ((SuggestionSpan)o).hashCode() == mHashCode;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    private static int hashCodeInternal(String[] suggestions, String locale,
            String notificationTargetClassName) {
        return Arrays.hashCode(new Object[] {Long.valueOf(SystemClock.uptimeMillis()), suggestions,
                locale, notificationTargetClassName});
    }

    public static final Parcelable.Creator<SuggestionSpan> CREATOR =
            new Parcelable.Creator<SuggestionSpan>() {
        @Override
        public SuggestionSpan createFromParcel(Parcel source) {
            return new SuggestionSpan(source);
        }

        @Override
        public SuggestionSpan[] newArray(int size) {
            return new SuggestionSpan[size];
        }
    };

    @Override
    public void updateDrawState(TextPaint tp) {
        final boolean misspelled = (mFlags & FLAG_MISSPELLED) != 0;
        final boolean easy = (mFlags & FLAG_EASY_CORRECT) != 0;
        final boolean autoCorrection = (mFlags & FLAG_AUTO_CORRECTION) != 0;
        if (easy) {
            if (!misspelled) {
                tp.setUnderlineText(mEasyCorrectUnderlineColor, mEasyCorrectUnderlineThickness);
            } else if (tp.underlineColor == 0) {
                // Spans are rendered in an arbitrary order. Since misspelled is less prioritary
                // than just easy, do not apply misspelled if an easy (or a mispelled) has been set
                tp.setUnderlineText(mMisspelledUnderlineColor, mMisspelledUnderlineThickness);
            }
        } else if (autoCorrection) {
            tp.setUnderlineText(mAutoCorrectionUnderlineColor, mAutoCorrectionUnderlineThickness);
        }
    }

    /**
     * @return The color of the underline for that span, or 0 if there is no underline
     *
     * @hide
     */
    public int getUnderlineColor() {
        // The order here should match what is used in updateDrawState
        final boolean misspelled = (mFlags & FLAG_MISSPELLED) != 0;
        final boolean easy = (mFlags & FLAG_EASY_CORRECT) != 0;
        final boolean autoCorrection = (mFlags & FLAG_AUTO_CORRECTION) != 0;
        if (easy) {
            if (!misspelled) {
                return mEasyCorrectUnderlineColor;
            } else {
                return mMisspelledUnderlineColor;
            }
        } else if (autoCorrection) {
            return mAutoCorrectionUnderlineColor;
        }
        return 0;
    }
}
