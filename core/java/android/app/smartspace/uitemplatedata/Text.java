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
public final class Text implements Parcelable {

    @NonNull
    private final CharSequence mText;

    private final TextUtils.TruncateAt mTruncateAtType;

    private final int mMaxLines;

    Text(Parcel in) {
        mText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mTruncateAtType = TextUtils.TruncateAt.valueOf(in.readString());
        mMaxLines = in.readInt();
    }

    private Text(@NonNull CharSequence text, TextUtils.TruncateAt truncateAtType, int maxLines) {
        mText = text;
        mTruncateAtType = truncateAtType;
        mMaxLines = maxLines;
    }

    /** Returns the text content. */
    @NonNull
    public CharSequence getText() {
        return mText;
    }

    /** Returns the {@link TextUtils.TruncateAt} type of the text content. */
    @NonNull
    public TextUtils.TruncateAt getTruncateAtType() {
        return mTruncateAtType;
    }

    /** Returns the allowed max lines for presenting the text content. */
    public int getMaxLines() {
        return mMaxLines;
    }

    @NonNull
    public static final Creator<Text> CREATOR = new Creator<Text>() {
        @Override
        public Text createFromParcel(Parcel in) {
            return new Text(in);
        }

        @Override
        public Text[] newArray(int size) {
            return new Text[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Text)) return false;
        Text that = (Text) o;
        return mTruncateAtType == that.mTruncateAtType && SmartspaceUtils.isEqual(mText,
                that.mText) && mMaxLines == that.mMaxLines;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mText, mTruncateAtType, mMaxLines);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        TextUtils.writeToParcel(mText, out, flags);
        out.writeString(mTruncateAtType.name());
        out.writeInt(mMaxLines);
    }

    @Override
    public String toString() {
        return "Text{"
                + "mText=" + mText
                + ", mTruncateAtType=" + mTruncateAtType
                + ", mMaxLines=" + mMaxLines
                + '}';
    }

    /**
     * A builder for {@link Text} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private final CharSequence mText;
        private TextUtils.TruncateAt mTruncateAtType;
        private int mMaxLines;

        /**
         * A builder for {@link Text}, which by default sets TruncateAtType to AT_END, and the max
         * lines to 1.
         */
        public Builder(@NonNull CharSequence text) {
            mText = Objects.requireNonNull(text);
            mTruncateAtType = TextUtils.TruncateAt.END;
            mMaxLines = 1;
        }

        /**
         * Sets truncateAtType, where the text content should be truncated if not all the content
         * can be presented.
         */
        @NonNull
        public Builder setTruncateAtType(@NonNull TextUtils.TruncateAt truncateAtType) {
            mTruncateAtType = Objects.requireNonNull(truncateAtType);
            return this;
        }

        /**
         * Sets the allowed max lines for the text content.
         */
        @NonNull
        public Builder setMaxLines(int maxLines) {
            mMaxLines = maxLines;
            return this;
        }

        /**
         * Builds a new SmartspaceText instance.
         */
        @NonNull
        public Text build() {
            return new Text(mText, mTruncateAtType, mMaxLines);
        }
    }
}
