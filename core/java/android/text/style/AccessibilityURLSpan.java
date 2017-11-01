/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * URLSpan's onClick method does not work from an accessibility service. This version of it does.
 * It is used to replace URLSpans in {@link AccessibilityNodeInfo#setText(CharSequence)}
 * @hide
 */
public class AccessibilityURLSpan extends URLSpan implements Parcelable {
    final AccessibilityClickableSpan mAccessibilityClickableSpan;

    /**
     * @param spanToReplace The original span
     */
    public AccessibilityURLSpan(URLSpan spanToReplace) {
        super(spanToReplace.getURL());
        mAccessibilityClickableSpan =
                new AccessibilityClickableSpan(spanToReplace.getId());
    }

    public AccessibilityURLSpan(Parcel p) {
        super(p);
        mAccessibilityClickableSpan = new AccessibilityClickableSpan(p);
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.ACCESSIBILITY_URL_SPAN;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    @Override
    public void writeToParcelInternal(Parcel dest, int flags) {
        super.writeToParcelInternal(dest, flags);
        mAccessibilityClickableSpan.writeToParcel(dest, flags);
    }

    @Override
    public void onClick(View unused) {
        mAccessibilityClickableSpan.onClick(unused);
    }

    /**
     * Delegated to AccessibilityClickableSpan
     * @param accessibilityNodeInfo
     */
    public void copyConnectionDataFrom(AccessibilityNodeInfo accessibilityNodeInfo) {
        mAccessibilityClickableSpan.copyConnectionDataFrom(accessibilityNodeInfo);
    }
}
