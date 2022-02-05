/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.smartspace.uitemplatedata;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.smartspace.SmartspaceUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Holds the information for a Smartspace-card text: the text content and
 * the truncate_at information.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceText implements Parcelable {

    @NonNull
    private final CharSequence mText;

    private final TextUtils.TruncateAt mTruncateAtType;

    SmartspaceText(Parcel in) {
        mText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mTruncateAtType = TextUtils.TruncateAt.valueOf(in.readString());
    }

    private SmartspaceText(@NonNull CharSequence text, TextUtils.TruncateAt truncateAtType) {
        mText = text;
        mTruncateAtType = truncateAtType;
    }

    @NonNull
    public CharSequence getText() {
        return mText;
    }

    @NonNull
    public TextUtils.TruncateAt getTruncateAtType() {
        return mTruncateAtType;
    }

    @NonNull
    public static final Creator<SmartspaceText> CREATOR = new Creator<SmartspaceText>() {
        @Override
        public SmartspaceText createFromParcel(Parcel in) {
            return new SmartspaceText(in);
        }

        @Override
        public SmartspaceText[] newArray(int size) {
            return new SmartspaceText[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceText)) return false;
        SmartspaceText that = (SmartspaceText) o;
        return mTruncateAtType == that.mTruncateAtType && SmartspaceUtils.isEqual(mText,
                that.mText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mText, mTruncateAtType);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        TextUtils.writeToParcel(mText, out, flags);
        out.writeString(mTruncateAtType.name());
    }

    /**
     * A builder for {@link SmartspaceText} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private final CharSequence mText;
        private TextUtils.TruncateAt mTruncateAtType;

        /**
         * A builder for {@link SmartspaceText}, which sets TruncateAtType to AT_END by default.
         */
        public Builder(@NonNull CharSequence text) {
            mText = Objects.requireNonNull(text);
            mTruncateAtType = TextUtils.TruncateAt.END;
        }

        /**
         * A builder for {@link SmartspaceText}.
         */
        public Builder(@NonNull CharSequence text, @NonNull TextUtils.TruncateAt truncateAtType) {
            mText = Objects.requireNonNull(text);
            mTruncateAtType = Objects.requireNonNull(truncateAtType);
        }

        /**
         * Sets truncateAtType.
         */
        @NonNull
        public Builder setTruncateAtType(@NonNull TextUtils.TruncateAt truncateAtType) {
            mTruncateAtType = Objects.requireNonNull(truncateAtType);
            return this;
        }

        /**
         * Builds a new SmartspaceText instance.
         */
        @NonNull
        public SmartspaceText build() {
            return new SmartspaceText(mText, mTruncateAtType);
        }
    }
}
