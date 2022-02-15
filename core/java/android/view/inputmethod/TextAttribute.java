/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The data class that IME can take extra information to applications when setting the text.
 *
 * See {@link InputConnection#commitText(CharSequence, int, TextAttribute)} and
 * {@link InputConnection#setComposingRegion(int, int, TextAttribute)} and
 * {@link InputConnection#setComposingText(CharSequence, int, TextAttribute)}
 */
public final class TextAttribute implements Parcelable {
    private final @NonNull List<String> mTextConversionSuggestions;
    private final @NonNull PersistableBundle mExtras;

    private TextAttribute(Builder builder) {
        mTextConversionSuggestions = builder.mTextConversionSuggestions;
        mExtras = builder.mExtras;
    }

    private TextAttribute(Parcel source) {
        mTextConversionSuggestions = source.createStringArrayList();
        mExtras = source.readPersistableBundle();
    }

    /**
     * Get the list of text conversion suggestions. More text conversion details in
     * {@link Builder#setTextConversionSuggestions(List)}.
     *
     * @return List of text conversion suggestions. If the list is empty, it means that IME not set
     * this field or IME didn't have suggestions for applications.
     */
    public @NonNull List<String> getTextConversionSuggestions() {
        return mTextConversionSuggestions;
    }

    /**
     * Get the extras data. More extras data details in
     * {@link Builder#setExtras(PersistableBundle)}.
     *
     * @return Extras data. If the Bundle is empty, it means that IME not set this field or IME
     * didn't have extras data.
     */
    public @NonNull PersistableBundle getExtras() {
        return mExtras;
    }

    /**
     * Builder for creating a {@link TextAttribute}.
     */
    public static final class Builder {
        private List<String> mTextConversionSuggestions = new ArrayList<>();
        private PersistableBundle mExtras = new PersistableBundle();

        /**
         * Sets text conversion suggestions.
         *
         * <p>Text conversion suggestion is for some transliteration languages which has
         * pronunciation characters and target characters. When the user is typing the pronunciation
         * characters, the input method can insert possible target characters into this list so that
         * the editor authors can provide suggestion before the user enters the complete
         * pronunciation characters.</p>
         *
         * @param textConversionSuggestions The list of text conversion suggestions.
         * @return This builder
         */
        public @NonNull Builder setTextConversionSuggestions(
                @NonNull List<String> textConversionSuggestions) {
            mTextConversionSuggestions = Collections.unmodifiableList(textConversionSuggestions);
            return this;
        }

        /**
         * Sets extras data.
         *
         * <p>Any extra data to supply to the applications. This field is for extended communication
         * with IME if there is data not defined in framework.</p>
         *
         * @return This builder.
         */
        public @NonNull Builder setExtras(@NonNull PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * @return a new {@link TextAttribute}.
         */
        public @NonNull TextAttribute build() {
            return new TextAttribute(this);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringList(mTextConversionSuggestions);
        dest.writePersistableBundle(mExtras);
    }

    public static final @NonNull Parcelable.Creator<TextAttribute> CREATOR =
            new Parcelable.Creator<TextAttribute>() {
        @Override
        public TextAttribute createFromParcel(Parcel source) {
            return new TextAttribute(source);
        }

        @Override
        public TextAttribute[] newArray(int size) {
            return new TextAttribute[size];
        }
    };
}
