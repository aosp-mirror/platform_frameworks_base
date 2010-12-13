/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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
 * Information about a single text completion that an editor has reported to
 * an input method.
 */
public final class CompletionInfo implements Parcelable {
    private final long mId;
    private final int mPosition;
    private final CharSequence mText;
    private final CharSequence mLabel;

    /**
     * Create a simple completion with just text, no label.
     */
    public CompletionInfo(long id, int index, CharSequence text) {
        mId = id;
        mPosition = index;
        mText = text;
        mLabel = null;
    }

    /**
     * Create a full completion with both text and label.
     */
    public CompletionInfo(long id, int index, CharSequence text, CharSequence label) {
        mId = id;
        mPosition = index;
        mText = text;
        mLabel = label;
    }

    private CompletionInfo(Parcel source) {
        mId = source.readLong();
        mPosition = source.readInt();
        mText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }
    
    /**
     * Return the abstract identifier for this completion, typically
     * corresponding to the id associated with it in the original adapter.
     */
    public long getId() {
        return mId;
    }
    
    /**
     * Return the original position of this completion, typically
     * corresponding to its position in the original adapter.
     */
    public int getPosition() {
        return mPosition;
    }
    
    /**
     * Return the actual text associated with this completion.  This is the
     * real text that will be inserted into the editor if the user selects it.
     */
    public CharSequence getText() {
        return mText;
    }
    
    /**
     * Return the user-visible label for the completion, or null if the plain
     * text should be shown.  If non-null, this will be what the user sees as
     * the completion option instead of the actual text.
     */
    public CharSequence getLabel() {
        return mLabel;
    }
    
    @Override
    public String toString() {
        return "CompletionInfo{#" + mPosition + " \"" + mText
                + "\" id=" + mId + " label=" + mLabel + "}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mId);
        dest.writeInt(mPosition);
        TextUtils.writeToParcel(mText, dest, flags);
        TextUtils.writeToParcel(mLabel, dest, flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<CompletionInfo> CREATOR
            = new Parcelable.Creator<CompletionInfo>() {
        public CompletionInfo createFromParcel(Parcel source) {
            return new CompletionInfo(source);
        }

        public CompletionInfo[] newArray(int size) {
            return new CompletionInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
