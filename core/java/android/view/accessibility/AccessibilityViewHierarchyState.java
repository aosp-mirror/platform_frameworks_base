/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.accessibility;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Accessibility-related state of a {@link android.view.ViewRootImpl}
 *
 * @hide
 */
public class AccessibilityViewHierarchyState {
    private @Nullable SendViewScrolledAccessibilityEvent mSendViewScrolledAccessibilityEvent;
    private @Nullable SendWindowContentChangedAccessibilityEvent
            mSendWindowContentChangedAccessibilityEvent;

    /**
     * @return a {@link SendViewScrolledAccessibilityEvent}, creating one if needed
     */
    public @NonNull SendViewScrolledAccessibilityEvent getSendViewScrolledAccessibilityEvent() {
        if (mSendViewScrolledAccessibilityEvent == null) {
            mSendViewScrolledAccessibilityEvent = new SendViewScrolledAccessibilityEvent();
        }
        return mSendViewScrolledAccessibilityEvent;
    }

    public boolean isScrollEventSenderInitialized() {
        return mSendViewScrolledAccessibilityEvent != null;
    }

    /**
     * @return a {@link SendWindowContentChangedAccessibilityEvent}, creating one if needed
     */
    public @NonNull SendWindowContentChangedAccessibilityEvent
            getSendWindowContentChangedAccessibilityEvent() {
        if (mSendWindowContentChangedAccessibilityEvent == null) {
            mSendWindowContentChangedAccessibilityEvent =
                    new SendWindowContentChangedAccessibilityEvent();
        }
        return mSendWindowContentChangedAccessibilityEvent;
    }

    public boolean isWindowContentChangedEventSenderInitialized() {
        return mSendWindowContentChangedAccessibilityEvent != null;
    }
}
