/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * Span that allows defining the alignment of text at the paragraph level.
 */
public interface AlignmentSpan extends ParagraphStyle {

    /**
     * Returns the alignment of the text.
     *
     * @return the text alignment
     */
    Layout.Alignment getAlignment();

    /**
     * Default implementation of the {@link AlignmentSpan}.
     * <p>
     * For example, a text written in a left to right language, like English, which is by default
     * aligned to the left, can be aligned opposite to the layout direction like this:
     * <pre>{@code SpannableString string = new SpannableString("Text with opposite alignment");
     *string.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0,
     *string.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
     * <img src="{@docRoot}reference/android/images/text/style/ltralignmentspan.png" />
     * <figcaption>Align left to right text opposite to the layout direction.</figcaption>
     * <p>
     * A text written in a right to left language, like Hebrew, which is by default aligned to the
     * right, can be aligned opposite to the layout direction like this:
     * <pre>{@code SpannableString string = new SpannableString("טקסט עם יישור הפוך");
     *string.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0,
     *string.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
     * <img src="{@docRoot}reference/android/images/text/style/rtlalignmentspan.png" />
     * <figcaption>Align right to left text opposite to the layout direction.</figcaption>
     */
    class Standard implements AlignmentSpan, ParcelableSpan {
        private final Layout.Alignment mAlignment;

        /**
         * Constructs a {@link Standard} from an alignment.
         */
        public Standard(@NonNull Layout.Alignment align) {
            mAlignment = align;
        }

        /**
         * Constructs a {@link Standard} from a parcel.
         */
        public Standard(@NonNull Parcel src) {
            mAlignment = Layout.Alignment.valueOf(src.readString());
        }

        @Override
        public int getSpanTypeId() {
            return getSpanTypeIdInternal();
        }

        /** @hide */
        @Override
        public int getSpanTypeIdInternal() {
            return TextUtils.ALIGNMENT_SPAN;
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
            dest.writeString(mAlignment.name());
        }

        @Override
        public Layout.Alignment getAlignment() {
            return mAlignment;
        }

        @Override
        public String toString() {
            return "AlignmentSpan.Standard{alignment=" + getAlignment() + '}';
        }
    }
}
