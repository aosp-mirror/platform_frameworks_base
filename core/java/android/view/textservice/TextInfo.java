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
import android.text.TextUtils;

/**
 * This class contains a metadata of the input of TextService
 */
public final class TextInfo implements Parcelable {
    private final String mText;
    private final int mCookie;
    private final int mSequence;

    /**
     * Constructor.
     * @param text the text which will be input to TextService
     */
    public TextInfo(String text) {
        this(text, 0, 0);
    }

    /**
     * Constructor.
     * @param text the text which will be input to TextService
     * @param cookie the cookie for this TextInfo
     * @param sequence the sequence number for this TextInfo
     */
    public TextInfo(String text, int cookie, int sequence) {
        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException(text);
        }
        mText = text;
        mCookie = cookie;
        mSequence = sequence;
    }

    public TextInfo(Parcel source) {
        mText = source.readString();
        mCookie = source.readInt();
        mSequence = source.readInt();
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mText);
        dest.writeInt(mCookie);
        dest.writeInt(mSequence);
    }

    /**
     * @return the text which is an input of a text service
     */
    public String getText() {
        return mText;
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
        return mSequence;
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<TextInfo> CREATOR
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
