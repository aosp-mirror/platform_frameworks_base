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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
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

    private static final String TAG = "SuggestionSpan";

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

    /**
     * Sets this flag if the suggestions apply to a grammar error. This type of suggestion is
     * rendered differently to highlight the error.
     */
    public static final int FLAG_GRAMMAR_ERROR = 0x0008;

    /**
     * This action is deprecated in {@link android.os.Build.VERSION_CODES#Q}.
     *
     * @deprecated For IMEs to receive this kind of user interaction signals, implement IMEs' own
     *             suggestion picker UI instead of relying on {@link SuggestionSpan}. To retrieve
     *             bounding boxes for each character of the composing text, use
     *             {@link android.view.inputmethod.CursorAnchorInfo}.
     */
    @Deprecated
    public static final String ACTION_SUGGESTION_PICKED = "android.text.style.SUGGESTION_PICKED";

    /**
     * This is deprecated in {@link android.os.Build.VERSION_CODES#Q}.
     *
     * @deprecated See {@link #ACTION_SUGGESTION_PICKED}.
     */
    @Deprecated
    public static final String SUGGESTION_SPAN_PICKED_AFTER = "after";
    /**
     * This is deprecated in {@link android.os.Build.VERSION_CODES#Q}.
     *
     * @deprecated See {@link #ACTION_SUGGESTION_PICKED}.
     */
    @Deprecated
    public static final String SUGGESTION_SPAN_PICKED_BEFORE = "before";
    /**
     * This is deprecated in {@link android.os.Build.VERSION_CODES#Q}.
     *
     * @deprecated See {@link #ACTION_SUGGESTION_PICKED}.
     */
    @Deprecated
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
    /**
     * Kept for compatibility for apps that rely on invalid locale strings e.g.
     * {@code new Locale(" an ", " i n v a l i d ", "data")}, which cannot be handled by
     * {@link #mLanguageTag}.
     */
    @NonNull
    private final String mLocaleStringForCompatibility;
    @NonNull
    private final String mLanguageTag;
    private final int mHashCode;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mEasyCorrectUnderlineThickness;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mEasyCorrectUnderlineColor;

    private float mMisspelledUnderlineThickness;
    private int mMisspelledUnderlineColor;

    private float mAutoCorrectionUnderlineThickness;
    private int mAutoCorrectionUnderlineColor;

    private float mGrammarErrorUnderlineThickness;
    private int mGrammarErrorUnderlineColor;

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
     * {@link SuggestionSpan#SUGGESTIONS_MAX_SIZE} will be considered. Null values not permitted.
     * @param flags Additional flags indicating how this span is handled in TextView
     * @param notificationTargetClass if not null, this class will get notified when the user
     *                                selects one of the suggestions.  On Android
     *                                {@link android.os.Build.VERSION_CODES#Q} and later this
     *                                parameter is always ignored.
     */
    public SuggestionSpan(Context context, Locale locale, String[] suggestions, int flags,
            Class<?> notificationTargetClass) {
        final int N = Math.min(SUGGESTIONS_MAX_SIZE, suggestions.length);
        mSuggestions = Arrays.copyOf(suggestions, N);
        mFlags = flags;
        final Locale sourceLocale;
        if (locale != null) {
            sourceLocale = locale;
        } else if (context != null) {
            // TODO: Consider to context.getResources().getResolvedLocale() instead.
            sourceLocale = context.getResources().getConfiguration().locale;
        } else {
            Log.e("SuggestionSpan", "No locale or context specified in SuggestionSpan constructor");
            sourceLocale = null;
        }
        mLocaleStringForCompatibility = sourceLocale == null ? "" : sourceLocale.toString();
        mLanguageTag = sourceLocale == null ? "" : sourceLocale.toLanguageTag();
        mHashCode = hashCodeInternal(mSuggestions, mLanguageTag, mLocaleStringForCompatibility);

        initStyle(context);
    }

    private void initStyle(Context context) {
        if (context == null) {
            mMisspelledUnderlineThickness = 0;
            mGrammarErrorUnderlineThickness = 0;
            mEasyCorrectUnderlineThickness = 0;
            mAutoCorrectionUnderlineThickness = 0;
            mMisspelledUnderlineColor = Color.BLACK;
            mGrammarErrorUnderlineColor = Color.BLACK;
            mEasyCorrectUnderlineColor = Color.BLACK;
            mAutoCorrectionUnderlineColor = Color.BLACK;
            return;
        }

        int defStyleAttr = com.android.internal.R.attr.textAppearanceMisspelledSuggestion;
        TypedArray typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyleAttr, 0);
        mMisspelledUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mMisspelledUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);
        typedArray.recycle();

        defStyleAttr = com.android.internal.R.attr.textAppearanceGrammarErrorSuggestion;
        typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyleAttr, 0);
        mGrammarErrorUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mGrammarErrorUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);
        typedArray.recycle();

        defStyleAttr = com.android.internal.R.attr.textAppearanceEasyCorrectSuggestion;
        typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyleAttr, 0);
        mEasyCorrectUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mEasyCorrectUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);
        typedArray.recycle();

        defStyleAttr = com.android.internal.R.attr.textAppearanceAutoCorrectionSuggestion;
        typedArray = context.obtainStyledAttributes(
                null, com.android.internal.R.styleable.SuggestionSpan, defStyleAttr, 0);
        mAutoCorrectionUnderlineThickness = typedArray.getDimension(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineThickness, 0);
        mAutoCorrectionUnderlineColor = typedArray.getColor(
                com.android.internal.R.styleable.SuggestionSpan_textUnderlineColor, Color.BLACK);
        typedArray.recycle();
    }

    public SuggestionSpan(Parcel src) {
        mSuggestions = src.readStringArray();
        mFlags = src.readInt();
        mLocaleStringForCompatibility = src.readString();
        mLanguageTag = src.readString();
        mHashCode = src.readInt();
        mEasyCorrectUnderlineColor = src.readInt();
        mEasyCorrectUnderlineThickness = src.readFloat();
        mMisspelledUnderlineColor = src.readInt();
        mMisspelledUnderlineThickness = src.readFloat();
        mAutoCorrectionUnderlineColor = src.readInt();
        mAutoCorrectionUnderlineThickness = src.readFloat();
        mGrammarErrorUnderlineColor = src.readInt();
        mGrammarErrorUnderlineThickness = src.readFloat();
    }

    /**
     * @return an array of suggestion texts for this span
     */
    public String[] getSuggestions() {
        return mSuggestions;
    }

    /**
     * @deprecated use {@link #getLocaleObject()} instead.
     * @return the locale of the suggestions. An empty string is returned if no locale is specified.
     */
    @NonNull
    @Deprecated
    public String getLocale() {
        return mLocaleStringForCompatibility;
    }

    /**
     * Returns a well-formed BCP 47 language tag representation of the suggestions, as a
     * {@link Locale} object.
     *
     * <p><b>Caveat</b>: The returned object is guaranteed to be a  a well-formed BCP 47 language tag
     * representation.  For example, this method can return an empty locale rather than returning a
     * malformed data when this object is initialized with an malformed {@link Locale} object, e.g.
     * {@code new Locale(" a ", " b c d ", " "}.</p>
     *
     * @return the locale of the suggestions. {@code null} is returned if no locale is specified.
     */
    @Nullable
    public Locale getLocaleObject() {
        return mLanguageTag.isEmpty() ? null : Locale.forLanguageTag(mLanguageTag);
    }

    /**
     * @return {@code null}.
     *
     * @hide
     * @deprecated Do not use. Always returns {@code null}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public String getNotificationTargetClassName() {
        return null;
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
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeStringArray(mSuggestions);
        dest.writeInt(mFlags);
        dest.writeString(mLocaleStringForCompatibility);
        dest.writeString(mLanguageTag);
        dest.writeInt(mHashCode);
        dest.writeInt(mEasyCorrectUnderlineColor);
        dest.writeFloat(mEasyCorrectUnderlineThickness);
        dest.writeInt(mMisspelledUnderlineColor);
        dest.writeFloat(mMisspelledUnderlineThickness);
        dest.writeInt(mAutoCorrectionUnderlineColor);
        dest.writeFloat(mAutoCorrectionUnderlineThickness);
        dest.writeInt(mGrammarErrorUnderlineColor);
        dest.writeFloat(mGrammarErrorUnderlineThickness);
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    public int getSpanTypeIdInternal() {
        return TextUtils.SUGGESTION_SPAN;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof SuggestionSpan) {
            return ((SuggestionSpan)o).hashCode() == mHashCode;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    private static int hashCodeInternal(String[] suggestions, @NonNull String languageTag,
            @NonNull String localeStringForCompatibility) {
        return Arrays.hashCode(new Object[] {Long.valueOf(SystemClock.uptimeMillis()), suggestions,
                languageTag, localeStringForCompatibility});
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SuggestionSpan> CREATOR =
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
        final boolean grammarError = (mFlags & FLAG_GRAMMAR_ERROR) != 0;
        if (easy) {
            if (!misspelled && !grammarError) {
                tp.setUnderlineText(mEasyCorrectUnderlineColor, mEasyCorrectUnderlineThickness);
            } else if (tp.underlineColor == 0) {
                // Spans are rendered in an arbitrary order. Since misspelled is less prioritary
                // than just easy, do not apply misspelled if an easy (or a mispelled) has been set
                if (grammarError) {
                    tp.setUnderlineText(
                            mGrammarErrorUnderlineColor, mGrammarErrorUnderlineThickness);
                } else {
                    tp.setUnderlineText(mMisspelledUnderlineColor, mMisspelledUnderlineThickness);
                }
            }
        } else if (autoCorrection) {
            tp.setUnderlineText(mAutoCorrectionUnderlineColor, mAutoCorrectionUnderlineThickness);
        } else if (misspelled) {
            tp.setUnderlineText(mMisspelledUnderlineColor, mMisspelledUnderlineThickness);
        } else if (grammarError) {
            tp.setUnderlineText(mGrammarErrorUnderlineColor, mGrammarErrorUnderlineThickness);
        }
    }

    /**
     * @return The color of the underline for that span, or 0 if there is no underline
     */
    @ColorInt
    public int getUnderlineColor() {
        // The order here should match what is used in updateDrawState
        final boolean misspelled = (mFlags & FLAG_MISSPELLED) != 0;
        final boolean easy = (mFlags & FLAG_EASY_CORRECT) != 0;
        final boolean autoCorrection = (mFlags & FLAG_AUTO_CORRECTION) != 0;
        final boolean grammarError = (mFlags & FLAG_GRAMMAR_ERROR) != 0;
        if (easy) {
            if (grammarError) {
                return mGrammarErrorUnderlineColor;
            } else if (misspelled) {
                return mMisspelledUnderlineColor;
            } else {
                return mEasyCorrectUnderlineColor;
            }
        } else if (autoCorrection) {
            return mAutoCorrectionUnderlineColor;
        } else if (misspelled) {
            return mMisspelledUnderlineColor;
        } else if (grammarError) {
            return mGrammarErrorUnderlineColor;
        }
        return 0;
    }

    /**
     * Does nothing.
     *
     * @deprecated this is deprecated in {@link android.os.Build.VERSION_CODES#Q}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
    public void notifySelection(Context context, String original, int index) {
        Log.w(TAG, "notifySelection() is deprecated.  Does nothing.");
    }
}
