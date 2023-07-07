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
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * A span that underlines the text it's attached to.
 * <p>
 * The span can be used like this:
 * <pre>{@code
 * SpannableString string = new SpannableString("Text with underline span");
 *string.setSpan(new UnderlineSpan(), 10, 19, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/underlinespan.png" />
 * <figcaption>Underlined text.</figcaption>
 */
public class UnderlineSpan extends CharacterStyle
        implements UpdateAppearance, ParcelableSpan {

    /**
     * Creates an {@link UnderlineSpan}.
     */
    public UnderlineSpan() {
    }

    /**
     * Creates an {@link UnderlineSpan} from a parcel.
     */
    public UnderlineSpan(@NonNull Parcel src) {
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.UNDERLINE_SPAN;
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
    public void updateDrawState(@NonNull TextPaint ds) {
        ds.setUnderlineText(true);
    }

    @Override
    public String toString() {
        return "UnderlineSpan{}";
    }
}
