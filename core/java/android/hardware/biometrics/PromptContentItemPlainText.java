/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A list item with plain text shown on {@link PromptVerticalListContentView}.
 */
public final class PromptContentItemPlainText implements PromptContentItemParcelable {
    private final String mText;

    /**
     * A list item with plain text shown on {@link PromptVerticalListContentView}.
     *
     * @param text The text of this list item.
     */
    public PromptContentItemPlainText(@NonNull String text) {
        mText = text;
    }

    /**
     * @hide
     */
    @NonNull
    public String getText() {
        return mText;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mText);
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<PromptContentItemPlainText> CREATOR = new Creator<>() {
        @Override
        public PromptContentItemPlainText createFromParcel(Parcel in) {
            return new PromptContentItemPlainText(in.readString());
        }

        @Override
        public PromptContentItemPlainText[] newArray(int size) {
            return new PromptContentItemPlainText[size];
        }
    };
}
