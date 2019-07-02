/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.textservice;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.ParcelableSpan;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.SpellCheckSpan;

/**
 * This class contains a metadata of the input of TextService
 */
public final class TextInfo implements Parcelable {
    private final CharSequence mCharSequence;
    private final int mCookie;
    private final int mSequenceNumber;

    private static final int DEFAULT_COOKIE = 0;
    private static final int DEFAULT_SEQUENCE_NUMBER = 0;

    /**
     * Constructor.
     * @param text the text which will be input to TextService
     */
    public TextInfo(String text) {
        this(text, 0, getStringLengthOrZero(text), DEFAULT_COOKIE, DEFAULT_SEQUENCE_NUMBER);
    }

    /**
     * Constructor.
     * @param text the text which will be input to TextService
     * @param cookie the cookie for this TextInfo
     * @param sequenceNumber the sequence number for this TextInfo
     */
    public TextInfo(String text, int cookie, int sequenceNumber) {
        this(text, 0, getStringLengthOrZero(text), cookie, sequenceNumber);
    }

    private static int getStringLengthOrZero(final String text) {
        return TextUtils.isEmpty(text) ? 0 : text.length();
    }

    /**
     * Constructor.
     * @param charSequence the text which will be input to TextService. Attached spans that
     * implement {@link ParcelableSpan} will also be marshaled alongside with the text.
     * @param start the beginning of the range of text (inclusive).
     * @param end the end of the range of text (exclusive).
     * @param cookie the cookie for this TextInfo
     * @param sequenceNumber the sequence number for this TextInfo
     */
    public TextInfo(CharSequence charSequence, int start, int end, int cookie, int sequenceNumber) {
        if (TextUtils.isEmpty(charSequence)) {
            throw new IllegalArgumentException("charSequence is empty");
        }
        // Create a snapshot of the text including spans in case they are updated outside later.
        final SpannableStringBuilder spannableString =
                new SpannableStringBuilder(charSequence, start, end);
        // SpellCheckSpan is for internal use. We do not want to marshal this for TextService.
        final SpellCheckSpan[] spans = spannableString.getSpans(0, spannableString.length(),
                SpellCheckSpan.class);
        for (int i = 0; i < spans.length; ++i) {
            spannableString.removeSpan(spans[i]);
        }

        mCharSequence = spannableString;
        mCookie = cookie;
        mSequenceNumber = sequenceNumber;
    }

    public TextInfo(Parcel source) {
        mCharSequence = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mCookie = source.readInt();
        mSequenceNumber = source.readInt();
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(mCharSequence, dest, flags);
        dest.writeInt(mCookie);
        dest.writeInt(mSequenceNumber);
    }

    /**
     * @return the text which is an input of a text service
     */
    public String getText() {
        if (mCharSequence == null) {
            return null;
        }
        return mCharSequence.toString();
    }

    /**
     * @return the charSequence which is an input of a text service. This may have some parcelable
     * spans.
     */
    public CharSequence getCharSequence() {
        return mCharSequence;
    }

    /**
     * @return the cookie of TextInfo
     */
    public int getCookie() {
        return mCookie;
    }

    /**
     * @return the sequence of TextInfo
     */
    public int getSequence() {
        return mSequenceNumber;
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<TextInfo> CREATOR
            = new Parcelable.Creator<TextInfo>() {
        @Override
        public TextInfo createFromParcel(Parcel source) {
            return new TextInfo(source);
        }

        @Override
        public TextInfo[] newArray(int size) {
            return new TextInfo[size];
        }
    };

    /**
     * Used to make this class parcelable.
     */
    @Override
    public int describeContents() {
        return 0;
    }
}
