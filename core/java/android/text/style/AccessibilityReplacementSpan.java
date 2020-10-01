/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.ParcelableSpan;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * This class serves as a parcelable placeholder for the {@link ReplacementSpan}.
 *
 * It is used to replace ReplacementSpans in {@link AccessibilityNodeInfo#setText(CharSequence)}.
 *
 * @hide
 */
public class AccessibilityReplacementSpan extends ReplacementSpan
        implements ParcelableSpan {

    /**
     * Sets the content description to the parent class.
     *
     * @param contentDescription The content description of the span this one replaces
     */
    public AccessibilityReplacementSpan(CharSequence contentDescription) {
        this.setContentDescription(contentDescription);
    }

    /**
     * Sets the content description to the parent class.
     *
     * @param p The parcel to de-serialize from
     */
    public AccessibilityReplacementSpan(Parcel p) {
        final CharSequence contentDescription = p.readCharSequence();
        this.setContentDescription(contentDescription);
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.ACCESSIBILITY_REPLACEMENT_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    @Override
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeCharSequence(this.getContentDescription());
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
            Paint.FontMetricsInt fm) {
        return 0;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
            int bottom, Paint paint) {
    }

    public static final @android.annotation.NonNull
    Parcelable.Creator<AccessibilityReplacementSpan> CREATOR =
            new Parcelable.Creator<AccessibilityReplacementSpan>() {
        @Override
        public AccessibilityReplacementSpan createFromParcel(Parcel parcel) {
            return new AccessibilityReplacementSpan(parcel);
        }

        @Override
        public AccessibilityReplacementSpan[] newArray(int size) {
            return new AccessibilityReplacementSpan[size];
        }
    };
}
