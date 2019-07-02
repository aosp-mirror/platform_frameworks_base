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
import static android.view.accessibility.AccessibilityNodeInfo.UNDEFINED_CONNECTION_ID;
import static android.view.accessibility.AccessibilityNodeInfo.UNDEFINED_NODE_ID;
import static android.view.accessibility.AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.ParcelableSpan;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

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

    private int mWindowId = UNDEFINED_WINDOW_ID;
    private long mSourceNodeId = UNDEFINED_NODE_ID;
    private int mConnectionId = UNDEFINED_CONNECTION_ID;

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
     * Configure this object to perform clicks on the view that contains the original span.
     *
     * @param accessibilityNodeInfo The info corresponding to the view containing the original
     *                              span.
     */
    public void copyConnectionDataFrom(AccessibilityNodeInfo accessibilityNodeInfo) {
        mConnectionId = accessibilityNodeInfo.getConnectionId();
        mWindowId = accessibilityNodeInfo.getWindowId();
        mSourceNodeId = accessibilityNodeInfo.getSourceNodeId();
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
        Bundle arguments = new Bundle();
        arguments.putParcelable(ACTION_ARGUMENT_ACCESSIBLE_CLICKABLE_SPAN, this);

        if ((mWindowId == UNDEFINED_WINDOW_ID) || (mSourceNodeId == UNDEFINED_NODE_ID)
                || (mConnectionId == UNDEFINED_CONNECTION_ID)) {
            throw new RuntimeException(
                    "ClickableSpan for accessibility service not properly initialized");
        }

        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        client.performAccessibilityAction(mConnectionId, mWindowId, mSourceNodeId,
                R.id.accessibilityActionClickOnClickableSpan, arguments);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AccessibilityClickableSpan> CREATOR =
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
