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
import android.text.ParcelableSpan;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CorrectionSpan implements ParcelableSpan {

    /**
     * Flag for the default value.
     */
    public static final int FLAG_DEFAULT = 0x0000;
    /**
     * Flag for indicating that the input is verbatim. TextView refers to this flag to determine
     * how it displays a word with CorrectionSpan.
     */
    public static final int FLAG_VERBATIM = 0x0001;

    private static final int SUGGESTS_MAX_SIZE = 5;

    /*
     * TODO: Needs to check the validity and add a feature that TextView will change
     * the current IME to the other IME which is specified in CorrectionSpan.
     * An IME needs to set the span by specifying the target IME and Subtype of CorrectionSpan.
     * And the current IME might want to specify any IME as the target IME including other IMEs.
     */

    private final int mFlags;
    private final List<CharSequence> mSuggests = new ArrayList<CharSequence>();
    private final String mLocaleString;
    private final String mOriginalString;
    /*
     * TODO: If switching IME is required, needs to add parameters for ids of InputMethodInfo
     * and InputMethodSubtype.
     */

    /**
     * @param context Context for the application
     * @param suggests Suggests for the string under the span
     * @param flags Additional flags indicating how this span is handled in TextView
     */
    public CorrectionSpan(Context context, List<CharSequence> suggests, int flags) {
        this(context, null, suggests, flags, null);
    }

    /**
     * @param locale Locale of the suggestions
     * @param suggests Suggests for the string under the span
     * @param flags Additional flags indicating how this span is handled in TextView
     */
    public CorrectionSpan(Locale locale, List<CharSequence> suggests, int flags) {
        this(null, locale, suggests, flags, null);
    }

    /**
     * @param context Context for the application
     * @param locale locale Locale of the suggestions
     * @param suggests suggests Suggests for the string under the span
     * @param flags Additional flags indicating how this span is handled in TextView
     * @param originalString originalString for suggests
     */
    public CorrectionSpan(Context context, Locale locale, List<CharSequence> suggests, int flags,
            String originalString) {
        final int N = Math.min(SUGGESTS_MAX_SIZE, suggests.size());
        for (int i = 0; i < N; ++i) {
            mSuggests.add(suggests.get(i));
        }
        mFlags = flags;
        if (context != null && locale == null) {
            mLocaleString = context.getResources().getConfiguration().locale.toString();
        } else {
            mLocaleString = locale.toString();
        }
        mOriginalString = originalString;
    }

    public CorrectionSpan(Parcel src) {
        src.readList(mSuggests, null);
        mFlags = src.readInt();
        mLocaleString = src.readString();
        mOriginalString = src.readString();
    }

    /**
     * @return suggestions
     */
    public List<CharSequence> getSuggests() {
        return new ArrayList<CharSequence>(mSuggests);
    }

    /**
     * @return locale of suggestions
     */
    public String getLocale() {
        return mLocaleString;
    }

    /**
     * @return original string of suggestions
     */
    public String getOriginalString() {
        return mOriginalString;
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
        dest.writeList(mSuggests);
        dest.writeInt(mFlags);
        dest.writeString(mLocaleString);
        dest.writeString(mOriginalString);
    }

    @Override
    public int getSpanTypeId() {
        return TextUtils.CORRECTION_SPAN;
    }

    public static final Parcelable.Creator<CorrectionSpan> CREATOR =
            new Parcelable.Creator<CorrectionSpan>() {
        @Override
        public CorrectionSpan createFromParcel(Parcel source) {
            return new CorrectionSpan(source);
        }

        @Override
        public CorrectionSpan[] newArray(int size) {
            return new CorrectionSpan[size];
        }
    };
}
