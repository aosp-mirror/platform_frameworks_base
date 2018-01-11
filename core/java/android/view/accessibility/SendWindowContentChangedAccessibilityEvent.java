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


import static com.android.internal.util.ObjectUtils.firstNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.View;
import android.view.ViewParent;

import java.util.HashSet;

/**
 * @hide
 */
public class SendWindowContentChangedAccessibilityEvent
        extends ThrottlingAccessibilityEventSender {

    private int mChangeTypes = 0;

    private HashSet<View> mTempHashSet;

    @Override
    protected void performSendEvent(@NonNull View source) {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(mChangeTypes);
        source.sendAccessibilityEventUnchecked(event);
    }

    @Override
    protected void resetState(@Nullable View source) {
        if (source != null) {
            source.resetSubtreeAccessibilityStateChanged();
        }
        mChangeTypes = 0;
    }

    /**
     * Post the {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event with the given
     * {@link AccessibilityEvent#getContentChangeTypes change type} for the given view
     */
    public void runOrPost(View source, int changeType) {
        if (source.getAccessibilityLiveRegion() != View.ACCESSIBILITY_LIVE_REGION_NONE) {
            sendNowIfPending();
            mChangeTypes = changeType;
            sendNow(source);
        } else {
            mChangeTypes |= changeType;
            scheduleFor(source);
        }
    }

    @Override
    protected @Nullable View tryMerge(@NonNull View oldSource, @NonNull View newSource) {
        // If there is no common predecessor, then oldSource points to
        // a removed view, hence in this case always prefer the newSource.
        return firstNotNull(
                getCommonPredecessor(oldSource, newSource),
                newSource);
    }

    private View getCommonPredecessor(View first, View second) {
        if (mTempHashSet == null) {
            mTempHashSet = new HashSet<>();
        }
        HashSet<View> seen = mTempHashSet;
        seen.clear();
        View firstCurrent = first;
        while (firstCurrent != null) {
            seen.add(firstCurrent);
            ViewParent firstCurrentParent = firstCurrent.getParent();
            if (firstCurrentParent instanceof View) {
                firstCurrent = (View) firstCurrentParent;
            } else {
                firstCurrent = null;
            }
        }
        View secondCurrent = second;
        while (secondCurrent != null) {
            if (seen.contains(secondCurrent)) {
                seen.clear();
                return secondCurrent;
            }
            ViewParent secondCurrentParent = secondCurrent.getParent();
            if (secondCurrentParent instanceof View) {
                secondCurrent = (View) secondCurrentParent;
            } else {
                secondCurrent = null;
            }
        }
        seen.clear();
        return null;
    }
}
