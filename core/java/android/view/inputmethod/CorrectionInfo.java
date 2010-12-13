/*
 * Copyright (C) 2007-2010 The Android Open Source Project
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

package android.view.inputmethod;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information about a single text correction that an editor has reported to
 * an input method.
 */
public final class CorrectionInfo implements Parcelable {
    private final int mOffset;
    private final CharSequence mOldText;
    private final CharSequence mNewText;

    /**
     * @param offset The offset in the edited text where the old and new text start.
     * @param oldText The old text that has been replaced.
     * @param newText The replacement text.
     */
    public CorrectionInfo(int offset, CharSequence oldText, CharSequence newText) {
        mOffset = offset;
        mOldText = oldText;
        mNewText = newText;
    }

    private CorrectionInfo(Parcel source) {
        mOffset = source.readInt();
        mOldText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mNewText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }

    /**
     * Return the offset position of this correction in the text. Both the {@link #getOldText()} and
     * {@link #getNewText()} start at this offset.
     */
    public int getOffset() {
        return mOffset;
    }

    /**
     * Return the text that has actually been typed by the user, and which has been corrected.
     */
    public CharSequence getOldText() {
        return mOldText;
    }

    /**
     * Return the new text that corrects what was typed by the user.
     */
    public CharSequence getNewText() {
        return mNewText;
    }

    @Override
    public String toString() {
        return "CorrectionInfo{#" + mOffset + " \"" + mOldText + "\" -> \"" + mNewText + "\"}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mOffset);
        TextUtils.writeToParcel(mOldText, dest, flags);
        TextUtils.writeToParcel(mNewText, dest, flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<CorrectionInfo> CREATOR
            = new Parcelable.Creator<CorrectionInfo>() {
        public CorrectionInfo createFromParcel(Parcel source) {
            return new CorrectionInfo(source);
        }

        public CorrectionInfo[] newArray(int size) {
            return new CorrectionInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
