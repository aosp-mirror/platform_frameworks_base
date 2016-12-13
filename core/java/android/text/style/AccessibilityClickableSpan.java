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

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.ParcelableSpan;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

import java.lang.ref.WeakReference;


/**
 * {@link ClickableSpan} cannot be parceled, but accessibility services need to be able to cause
 * their callback handlers to be called. This class serves as a parcelable placeholder for the
 * real spans.
 *
 * This span is also passed back to an app's process when an accessibility service tries to click
 * it. It contains enough information to track down the original clickable span so it can be
 * called.
 *
 * @hide
 */
public class AccessibilityClickableSpan extends ClickableSpan
        implements ParcelableSpan {
    // The id of the span this one replaces
    private final int mOriginalClickableSpanId;

    // Only retain a weak reference to the node to avoid referencing cycles that could create memory
    // leaks.
    private WeakReference<AccessibilityNodeInfo> mAccessibilityNodeInfoRef;


    /**
     * @param originalClickableSpanId The id of the span this one replaces
     */
    public AccessibilityClickableSpan(int originalClickableSpanId) {
        mOriginalClickableSpanId = originalClickableSpanId;
    }

    public AccessibilityClickableSpan(Parcel p) {
        mOriginalClickableSpanId = p.readInt();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.ACCESSIBILITY_CLICKABLE_SPAN;
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
        dest.writeInt(mOriginalClickableSpanId);
    }

    /**
     * Find the ClickableSpan that matches the one used to create this object.
     *
     * @param text The text that contains the original ClickableSpan.
     * @return The ClickableSpan that matches this object, or {@code null} if no such object
     * can be found.
     */
    public ClickableSpan findClickableSpan(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return null;
        }
        Spanned sp = (Spanned) text;
        ClickableSpan[] os = sp.getSpans(0, text.length(), ClickableSpan.class);
        for (int i = 0; i < os.length; i++) {
            if (os[i].getId() == mOriginalClickableSpanId) {
                return os[i];
            }
        }
        return null;
    }

    /**
     * Set the accessibilityNodeInfo that this placeholder belongs to. This node is not
     * included in the parceling logic, and must be set to allow the onClick handler to function.
     *
     * @param accessibilityNodeInfo The info this span is part of
     */
    public void setAccessibilityNodeInfo(AccessibilityNodeInfo accessibilityNodeInfo) {
        mAccessibilityNodeInfoRef = new WeakReference<>(accessibilityNodeInfo);
    }

    /**
     * Perform the click from an accessibility service. Will not work unless
     * setAccessibilityNodeInfo is called with a properly initialized node.
     *
     * @param unused This argument is required by the superclass but is unused. The real view will
     * be determined by the AccessibilityNodeInfo.
     */
    @Override
    public void onClick(View unused) {
        if (mAccessibilityNodeInfoRef == null) {
            return;
        }
        AccessibilityNodeInfo info = mAccessibilityNodeInfoRef.get();
        if (info == null) {
            return;
        }
        Bundle arguments = new Bundle();
        arguments.putParcelable(ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN, this);

        info.performAction(R.id.accessibilityActionClickOnClickableSpan, arguments);
    }

    public static final Parcelable.Creator<AccessibilityClickableSpan> CREATOR =
            new Parcelable.Creator<AccessibilityClickableSpan>() {
                @Override
                public AccessibilityClickableSpan createFromParcel(Parcel parcel) {
                    return new AccessibilityClickableSpan(parcel);
                }

                @Override
                public AccessibilityClickableSpan[] newArray(int size) {
                    return new AccessibilityClickableSpan[size];
                }
            };
}
