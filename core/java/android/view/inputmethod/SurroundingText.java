/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information about the surrounding text around the cursor for use by an input method.
 *
 * <p>This contains information about the text and the selection relative to the text. </p>
 */
public final class SurroundingText implements Parcelable {
    /**
     * The surrounding text around the cursor.
     */
    @NonNull
    private final CharSequence mText;

    /**
     * The text offset of the start of the selection in the surrounding text.
     *
     * <p>This needs to be the position relative to the {@link #mText} instead of the real position
     * in the editor.</p>
     */
    @IntRange(from = 0)
    private final int mSelectionStart;

    /**
     * The text offset of the end of the selection in the surrounding text.
     *
     * <p>This needs to be the position relative to the {@link #mText} instead of the real position
     * in the editor.</p>
     */
    @IntRange(from = 0)
    private final int mSelectionEnd;

    /**
     * The text offset between the start of the editor's text and the start of the surrounding text.
     *
     * <p>-1 indicates the offset information is unknown.</p>
     */
    @IntRange(from = -1)
    private final int mOffset;

    /**
     * Constructor.
     *
     * @param text The surrounding text.
     * @param selectionStart The text offset of the start of the selection in the surrounding text.
     *                       Reversed selection is allowed.
     * @param selectionEnd The text offset of the end of the selection in the surrounding text.
     *                     Reversed selection is allowed.
     * @param offset The text offset between the start of the editor's text and the start of the
     *               surrounding text. -1 indicates the offset is unknown.
     */
    public SurroundingText(@NonNull final CharSequence text,
            @IntRange(from = 0) int selectionStart, @IntRange(from = 0) int selectionEnd,
            @IntRange(from = -1) int offset) {
        mText = copyWithParcelableSpans(text);
        mSelectionStart = selectionStart;
        mSelectionEnd = selectionEnd;
        mOffset = offset;
    }

    /**
     * Returns the surrounding text around the cursor.
     */
    @NonNull
    public CharSequence getText() {
        return mText;
    }

    /**
     * Returns the text offset of the start of the selection in the surrounding text.
     *
     * <p>A selection is the current range of the text that is selected by the user, or the current
     * position of the cursor. A cursor is a selection where the start and end are at the same
     * offset.<p>
     */
    @IntRange(from = 0)
    public int getSelectionStart() {
        return mSelectionStart;
    }

    /**
     * Returns the text offset of the end of the selection in the surrounding text.
     *
     * <p>A selection is the current range of the text that is selected by the user, or the current
     * position of the cursor. A cursor is a selection where the start and end are at the same
     * offset.<p>
     */
    @IntRange(from = 0)
    public int getSelectionEnd() {
        return mSelectionEnd;
    }

    /**
     * Returns text offset between the start of the editor's text and the start of the surrounding
     * text.
     *
     * <p>-1 indicates the offset information is unknown.</p>
     */
    @IntRange(from = -1)
    public int getOffset() {
        return mOffset;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        TextUtils.writeToParcel(mText, out, flags);
        out.writeInt(mSelectionStart);
        out.writeInt(mSelectionEnd);
        out.writeInt(mOffset);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<SurroundingText> CREATOR =
            new Parcelable.Creator<SurroundingText>() {
                @NonNull
                public SurroundingText createFromParcel(Parcel in) {
                    final CharSequence text =
                            TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    final int selectionHead = in.readInt();
                    final int selectionEnd = in.readInt();
                    final int offset = in.readInt();
                    return new SurroundingText(
                            text == null ? "" : text,  selectionHead, selectionEnd, offset);
                }

                @NonNull
                public SurroundingText[] newArray(int size) {
                    return new SurroundingText[size];
                }
            };

    /**
     * Create a copy of the given {@link CharSequence} object, with completely copy
     * {@link ParcelableSpan} instances.
     *
     * @param source the original {@link CharSequence} to be copied.
     * @return the copied {@link CharSequence}. {@code null} if {@code source} is {@code null}.
     */
    @Nullable
    private static CharSequence copyWithParcelableSpans(@Nullable CharSequence source) {
        if (source == null) {
            return null;
        }
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            TextUtils.writeToParcel(source, parcel, /* parcelableFlags= */ 0);
            parcel.setDataPosition(0);
            return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
