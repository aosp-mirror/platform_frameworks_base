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
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.ParcelableSpan;
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
 * These spans should typically be created by the input method to privide correction and alternates
 * for the text.
 *
 * @see TextView#setSuggestionsEnabled(boolean)
 */
public class SuggestionSpan implements ParcelableSpan {
    /**
     * Flag for indicating that the input is verbatim. TextView refers to this flag to determine
     * how it displays a word with SuggestionSpan.
     */
    public static final int FLAG_VERBATIM = 0x0001;

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

    private final int mFlags;
    private final String[] mSuggestions;
    private final String mLocaleString;
    private final String mNotificationTargetClassName;
    private final int mHashCode;

    /*
     * TODO: If switching IME is required, needs to add parameters for ids of InputMethodInfo
     * and InputMethodSubtype.
     */

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
     * @param suggestions Suggestions for the string under the span
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
        mHashCode = hashCodeInternal(
                mFlags, mSuggestions, mLocaleString, mNotificationTargetClassName);
    }

    public SuggestionSpan(Parcel src) {
        mSuggestions = src.readStringArray();
        mFlags = src.readInt();
        mLocaleString = src.readString();
        mNotificationTargetClassName = src.readString();
        mHashCode = src.readInt();
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

    private static int hashCodeInternal(int flags, String[] suggestions,String locale,
            String notificationTargetClassName) {
        return Arrays.hashCode(new Object[] {SystemClock.uptimeMillis(), flags, suggestions, locale,
                notificationTargetClassName});
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
}
