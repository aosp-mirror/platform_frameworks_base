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

package android.text.style;

import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.text.LineBreakConfig;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextUtils;

import java.util.Objects;

/**
 * LineBreakSpan for changing line break style of the specific region of the text.
 */
@FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
public final class LineBreakConfigSpan implements ParcelableSpan {
    private final LineBreakConfig mLineBreakConfig;

    /**
     * Construct a new {@link LineBreakConfigSpan}
     * @param lineBreakConfig a line break config
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public LineBreakConfigSpan(@NonNull LineBreakConfig lineBreakConfig) {
        mLineBreakConfig = lineBreakConfig;
    }

    /**
     * Gets an associated line break config.
     * @return associated line break config.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public @NonNull LineBreakConfig getLineBreakConfig() {
        return mLineBreakConfig;
    }

    /**
     * A specialized {@link LineBreakConfigSpan} that used for preventing line break.
     *
     * This is useful when you want to preserve some words in the same line.
     * Note that even if this style is specified, the grapheme based line break is still performed
     * for preventing clipping text.
     *
     * @see LineBreakConfigSpan
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static @NonNull LineBreakConfigSpan createNoBreakSpan() {
        return new LineBreakConfigSpan(sNoBreakConfig);
    }

    /**
     * A specialized {@link LineBreakConfigSpan} that used for preventing hyphenation.
     */
    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static @NonNull LineBreakConfigSpan createNoHyphenationSpan() {
        return new LineBreakConfigSpan(sNoHyphenationConfig);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineBreakConfigSpan)) return false;
        LineBreakConfigSpan that = (LineBreakConfigSpan) o;
        return Objects.equals(mLineBreakConfig, that.mLineBreakConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakConfig);
    }

    @Override
    public String toString() {
        return "LineBreakConfigSpan{mLineBreakConfig=" + mLineBreakConfig + '}';
    }

    private static final LineBreakConfig sNoHyphenationConfig = new LineBreakConfig.Builder()
            .setHyphenation(LineBreakConfig.HYPHENATION_DISABLED)
            .build();

    private static final LineBreakConfig sNoBreakConfig = new LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NO_BREAK)
            .build();

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.LINE_BREAK_CONFIG_SPAN;
    }

    /** @hide */
    @Override
    public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mLineBreakConfig, flags);
    }

    @NonNull
    public static final Creator<LineBreakConfigSpan> CREATOR = new Creator<>() {

        @Override
        public LineBreakConfigSpan createFromParcel(Parcel source) {
            LineBreakConfig lbc = source.readParcelable(
                    LineBreakConfig.class.getClassLoader(), LineBreakConfig.class);
            return new LineBreakConfigSpan(lbc);
        }

        @Override
        public LineBreakConfigSpan[] newArray(int size) {
            return new LineBreakConfigSpan[size];
        }
    };
}
