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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class contains a metadata of suggestions from the text service
 */
public final class SuggestionsInfo implements Parcelable {
    private static final String[] EMPTY = ArrayUtils.emptyArray(String.class);

    /**
     * An internal annotation to indicate that one ore more combinations of
     * <code>RESULT_ATTR_</code> flags are expected.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RESULT_ATTR_" }, value = {
            RESULT_ATTR_IN_THE_DICTIONARY,
            RESULT_ATTR_LOOKS_LIKE_TYPO,
            RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
            RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR,
            RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS,
    })
    public @interface ResultAttrs {}

    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the requested word was found
     * in the dictionary in the text service.
     */
    public static final int RESULT_ATTR_IN_THE_DICTIONARY = 0x0001;
    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the text service thinks the requested
     * word looks like a typo.
     */
    public static final int RESULT_ATTR_LOOKS_LIKE_TYPO = 0x0002;
    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the text service thinks
     * the result suggestions include highly recommended ones.
     */
    public static final int RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS = 0x0004;

    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the text service thinks the requested
     * sentence contains a grammar error.
     */
    public static final int RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR = 0x0008;

    /**
     * Flag of the attributes of the suggestions that can be obtained by
     * {@link #getSuggestionsAttributes}: this tells that the text service has an alternative way to
     * show UI for the list of correction suggestions to the user. When this flag is set, the
     * receiver of the result suggestions should mark the erroneous part of the text with a text
     * signifier (for example, underline), but should not show any UI for the list of correction
     * suggestions to the user (for example, in a popup window).
     */
    public static final int RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS = 0x0010;

    private final @ResultAttrs int mSuggestionsAttributes;
    private final String[] mSuggestions;
    private final boolean mSuggestionsAvailable;
    private int mCookie;
    private int mSequence;

    /**
     * Constructor.
     * @param suggestionsAttributes from the text service
     * @param suggestions from the text service
     */
    public SuggestionsInfo(int suggestionsAttributes, String[] suggestions) {
        this(suggestionsAttributes, suggestions, 0, 0);
    }

    /**
     * Constructor.
     * @param suggestionsAttributes from the text service
     * @param suggestions from the text service
     * @param cookie the cookie of the input TextInfo
     * @param sequence the cookie of the input TextInfo
     */
    public SuggestionsInfo(@ResultAttrs int suggestionsAttributes, String[] suggestions, int cookie,
            int sequence) {
        if (suggestions == null) {
            mSuggestions = EMPTY;
            mSuggestionsAvailable = false;
        } else {
            mSuggestions = suggestions;
            mSuggestionsAvailable = true;
        }
        mSuggestionsAttributes = suggestionsAttributes;
        mCookie = cookie;
        mSequence = sequence;
    }

    public SuggestionsInfo(Parcel source) {
        mSuggestionsAttributes = source.readInt();
        mSuggestions = source.readStringArray();
        mCookie = source.readInt();
        mSequence = source.readInt();
        mSuggestionsAvailable = source.readInt() == 1;
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
        dest.writeInt(mSuggestionsAvailable ? 1 : 0);
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
    public @ResultAttrs int getSuggestionsAttributes() {
        return mSuggestionsAttributes;
    }

    /**
     * @return the count of the suggestions. If there's no suggestions at all, this method returns
     * -1. Even if this method returns 0, it doesn't necessarily mean that there are no suggestions
     * for the requested word. For instance, the caller could have been asked to limit the maximum
     * number of suggestions returned.
     */
    public int getSuggestionsCount() {
        if (!mSuggestionsAvailable) {
            return -1;
        }
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
    public static final @android.annotation.NonNull Parcelable.Creator<SuggestionsInfo> CREATOR
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
