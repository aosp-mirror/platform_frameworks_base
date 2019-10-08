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
 *
 * <p>This class encapsulates a completion offered by an application
 * that wants it to be presented to the user by the IME. Usually, apps
 * present their completions directly in a scrolling list for example
 * (UI developers will usually use or extend
 * {@see android.widget.AutoCompleteTextView} to implement this).
 * However, in some cases, the editor may not be visible, as in the
 * case in extract mode where the IME has taken over the full
 * screen. In this case, the editor can choose to send their
 * completions to the IME for display.
 *
 * <p>Most applications who want to send completions to an IME should use
 * {@link android.widget.AutoCompleteTextView} as this class makes this
 * process easy. In this case, the application would not have to deal directly
 * with this class.
 *
 * <p>An application who implements its own editor and wants direct control
 * over this would create an array of CompletionInfo objects, and send it to the IME using
 * {@link InputMethodManager#displayCompletions(View, CompletionInfo[])}.
 * The IME would present the completions however they see fit, and
 * call back to the editor through
 * {@link InputConnection#commitCompletion(CompletionInfo)}.
 * The application can then pick up the commit event by overriding
 * {@link android.widget.TextView#onCommitCompletion(CompletionInfo)}.
 */
public final class CompletionInfo implements Parcelable {
    private final long mId;
    private final int mPosition;
    private final CharSequence mText;
    private final CharSequence mLabel;

    /**
     * Create a simple completion with just text, no label.
     *
     * @param id An id that get passed as is (to the editor's discretion)
     * @param index An index that get passed as is. Typically this is the
     * index in the list of completions inside the editor.
     * @param text The text that should be inserted into the editor when
     * this completion is chosen.
     */
    public CompletionInfo(long id, int index, CharSequence text) {
        mId = id;
        mPosition = index;
        mText = text;
        mLabel = null;
    }

    /**
     * Create a full completion with both text and label. The text is
     * what will get inserted into the editor, while the label is what
     * the IME should display. If they are the same, use the version
     * of the constructor without a `label' argument.
     *
     * @param id An id that get passed as is (to the editor's discretion)
     * @param index An index that get passed as is. Typically this is the
     * index in the list of completions inside the editor.
     * @param text The text that should be inserted into the editor when
     * this completion is chosen.
     * @param label The text that the IME should be showing among the
     * completions list.
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
    public static final @android.annotation.NonNull Parcelable.Creator<CompletionInfo> CREATOR
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
