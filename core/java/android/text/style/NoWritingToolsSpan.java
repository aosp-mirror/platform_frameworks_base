/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.text.style;

import static android.view.inputmethod.Flags.FLAG_WRITING_TOOLS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A span that signals to IMEs that writing tools should not modify the text.
 *
 * <p>For example, a text field may contain a mix of user input text and quoted text. The app
 * can apply {@code NoWritingToolsSpan} to the quoted text so that the IME knows that writing
 * tools should only rewrite the user input text, and not modify the quoted text.
 */
@FlaggedApi(FLAG_WRITING_TOOLS)
public final class NoWritingToolsSpan implements ParcelableSpan {

    /**
     * Creates a {@link NoWritingToolsSpan}.
     */
    public NoWritingToolsSpan() {
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.NO_WRITING_TOOLS_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    @Override
    public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
    }

    @Override
    public String toString() {
        return "NoWritingToolsSpan{}";
    }

    @NonNull
    public static final Creator<NoWritingToolsSpan> CREATOR = new Creator<>() {

        @Override
        public NoWritingToolsSpan createFromParcel(Parcel source) {
            return new NoWritingToolsSpan();
        }

        @Override
        public NoWritingToolsSpan[] newArray(int size) {
            return new NoWritingToolsSpan[size];
        }
    };
}
