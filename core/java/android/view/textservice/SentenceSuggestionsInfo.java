/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.util.Arrays;

/**
 * This class contains a metadata of suggestions returned from a text service
 * (e.g. {@link android.service.textservice.SpellCheckerService}).
 * The text service uses this class to return the suggestions
 * for a sentence. See {@link SuggestionsInfo} which is used for suggestions for a word.
 * This class extends the functionality of {@link SuggestionsInfo} as far as this class enables
 * you to put multiple {@link SuggestionsInfo}s on a sentence with the offsets and the lengths
 * of all {@link SuggestionsInfo}s.
 */
public final class SentenceSuggestionsInfo implements Parcelable {

    private final SuggestionsInfo[] mSuggestionsInfos;
    private final int[] mOffsets;
    private final int[] mLengths;

    /**
     * Constructor.
     * @param suggestionsInfos from the text service
     * @param offsets the array of offsets of suggestions
     * @param lengths the array of lengths of suggestions
     */
    public SentenceSuggestionsInfo(
            SuggestionsInfo[] suggestionsInfos, int[] offsets, int[] lengths) {
        if (suggestionsInfos == null || offsets == null || lengths == null) {
            throw new NullPointerException();
        }
        if (suggestionsInfos.length != offsets.length || offsets.length != lengths.length) {
            throw new IllegalArgumentException();
        }
        final int infoSize = suggestionsInfos.length;
        mSuggestionsInfos = Arrays.copyOf(suggestionsInfos, infoSize);
        mOffsets = Arrays.copyOf(offsets, infoSize);
        mLengths = Arrays.copyOf(lengths, infoSize);
    }

    public SentenceSuggestionsInfo(Parcel source) {
        final int infoSize = source.readInt();
        mSuggestionsInfos = new SuggestionsInfo[infoSize];
        source.readTypedArray(mSuggestionsInfos, SuggestionsInfo.CREATOR);
        mOffsets = new int[mSuggestionsInfos.length];
        source.readIntArray(mOffsets);
        mLengths = new int[mSuggestionsInfos.length];
        source.readIntArray(mLengths);
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        final int infoSize = mSuggestionsInfos.length;
        dest.writeInt(infoSize);
        dest.writeTypedArray(mSuggestionsInfos, 0);
        dest.writeIntArray(mOffsets);
        dest.writeIntArray(mLengths);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return the count of {@link SuggestionsInfo}s this instance holds.
     */
    public int getSuggestionsCount() {
        return mSuggestionsInfos.length;
    }

    /**
     * @param i the id of {@link SuggestionsInfo}s this instance holds.
     * @return a {@link SuggestionsInfo} at the specified id
     */
    public SuggestionsInfo getSuggestionsInfoAt(int i) {
        if (i >= 0 && i < mSuggestionsInfos.length) {
            return mSuggestionsInfos[i];
        }
        return null;
    }

    /**
     * @param i the id of {@link SuggestionsInfo}s this instance holds
     * @return the offset of the specified {@link SuggestionsInfo}
     */
    public int getOffsetAt(int i) {
        if (i >= 0 && i < mOffsets.length) {
            return mOffsets[i];
        }
        return -1;
    }

    /**
     * @param i the id of {@link SuggestionsInfo}s this instance holds
     * @return the length of the specified {@link SuggestionsInfo}
     */
    public int getLengthAt(int i) {
        if (i >= 0 && i < mLengths.length) {
            return mLengths[i];
        }
        return -1;
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<SentenceSuggestionsInfo> CREATOR
            = new Parcelable.Creator<SentenceSuggestionsInfo>() {
        @Override
        public SentenceSuggestionsInfo createFromParcel(Parcel source) {
            return new SentenceSuggestionsInfo(source);
        }

        @Override
        public SentenceSuggestionsInfo[] newArray(int size) {
            return new SentenceSuggestionsInfo[size];
        }
    };
}
