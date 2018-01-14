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
import android.view.View;

/**
 * Sender for {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} accessibility event.
 *
 * @hide
 */
public class SendViewScrolledAccessibilityEvent extends ThrottlingAccessibilityEventSender {

    public int mDeltaX;
    public int mDeltaY;

    /**
     * Post a scroll event to be sent for the given view
     */
    public void post(View source, int dx, int dy) {
        if (!isPendingFor(source)) sendNowIfPending();

        mDeltaX += dx;
        mDeltaY += dy;

        if (!isPendingFor(source)) scheduleFor(source);
    }

    @Override
    protected void performSendEvent(@NonNull View source) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setScrollDeltaX(mDeltaX);
        event.setScrollDeltaY(mDeltaY);
        source.sendAccessibilityEventUnchecked(event);
    }

    @Override
    protected void resetState(@NonNull View source) {
        mDeltaX = 0;
        mDeltaY = 0;
    }
}
