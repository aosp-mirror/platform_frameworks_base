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

/**
 * This class contains a metadata of suggestions from the text service
 */
public final class SuggestionsInfo implements Parcelable {
    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the requested word was found
     * in the dictionary in the text service.
     */
    public static final int RESULT_ATTR_IN_THE_DICTIONARY = 0x0001;
    /** Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that there are one or more suggestions
     * available for the requested word.  This doesn't necessarily mean that the suggestions
     * are actually in this SuggestionsInfo.  For instance, the caller could have been asked to
     * limit the maximum number of suggestions returned.
     */
    public static final int RESULT_ATTR_SUGGESTIONS_AVAILABLE = 0x0002;
    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the text service thinks the requested
     * word looks a typo.
     */
    public static final int RESULT_ATTR_LOOKS_TYPO = 0x0004;
    private final int mSuggestionsAttributes;
    private final String[] mSuggestions;
    private int mCookie;
    private int mSequence;

    /**
     * Constructor.
     * @param suggestionsAttributes from the text service
     * @param suggestions from the text service
     */
    public SuggestionsInfo(int suggestionsAttributes, String[] suggestions) {
        if (suggestions == null) {
            throw new NullPointerException();
        }
        mSuggestionsAttributes = suggestionsAttributes;
        mSuggestions = suggestions;
        mCookie = 0;
        mSequence = 0;
    }

    /**
     * Constructor.
     * @param suggestionsAttributes from the text service
     * @param suggestions from the text service
     * @param cookie the cookie of the input TextInfo
     * @param sequence the cookie of the input TextInfo
     */
    public SuggestionsInfo(
            int suggestionsAttributes, String[] suggestions, int cookie, int sequence) {
        if (suggestions == null) {
            throw new NullPointerException();
        }
        mSuggestionsAttributes = suggestionsAttributes;
        mSuggestions = suggestions;
        mCookie = cookie;
        mSequence = sequence;
    }

    public SuggestionsInfo(Parcel source) {
        mSuggestionsAttributes = source.readInt();
        mSuggestions = source.readStringArray();
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
        dest.writeInt(mSuggestionsAttributes);
        dest.writeStringArray(mSuggestions);
        dest.writeInt(mCookie);
        dest.writeInt(mSequence);
    }

    /**
     * Set the cookie and the sequence of SuggestionsInfo which are set to TextInfo from a client
     * application
     * @param cookie the cookie of an input TextInfo
     * @param sequence the cookie of an input TextInfo
     */
    public void setCookieAndSequence(int cookie, int sequence) {
        mCookie = cookie;
        mSequence = sequence;
    }

    /**
     * @return the cookie which may be set by a client application
     */
    public int getCookie() {
        return mCookie;
    }

    /**
     * @return the sequence which may be set by a client application
     */
    public int getSequence() {
        return mSequence;
    }

    /**
     * @return the attributes of suggestions. This includes whether the spell checker has the word
     * in its dictionary or not and whether the spell checker has confident suggestions for the
     * word or not.
     */
    public int getSuggestionsAttributes() {
        return mSuggestionsAttributes;
    }

    /**
     * @return the count of suggestions
     */
    public int getSuggestionsCount() {
        return mSuggestions.length;
    }

    /**
     * @param i the id of suggestions
     * @return the suggestion at the specified id
     */
    public String getSuggestionAt(int i) {
        return mSuggestions[i];
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<SuggestionsInfo> CREATOR
            = new Parcelable.Creator<SuggestionsInfo>() {
        @Override
        public SuggestionsInfo createFromParcel(Parcel source) {
            return new SuggestionsInfo(source);
        }

        @Override
        public SuggestionsInfo[] newArray(int size) {
            return new SuggestionsInfo[size];
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
