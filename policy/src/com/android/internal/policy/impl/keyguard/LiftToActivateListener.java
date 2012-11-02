/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

/**
 * Hover listener that implements lift-to-activate interaction for
 * accessibility. May be added to multiple views.
 */
class LiftToActivateListener implements View.OnHoverListener {
    /** Manager used to query accessibility enabled state. */
    private final AccessibilityManager mAccessibilityManager;

    private boolean mCachedClickableState;

    public LiftToActivateListener(Context context) {
        mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public boolean onHover(View v, MotionEvent event) {
        // When touch exploration is turned on, lifting a finger while
        // inside the view bounds should perform a click action.
        if (mAccessibilityManager.isEnabled()
                && mAccessibilityManager.isTouchExplorationEnabled()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    // Lift-to-type temporarily disables double-tap
                    // activation by setting the view as not clickable.
                    mCachedClickableState = v.isClickable();
                    v.setClickable(false);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    final int x = (int) event.getX();
                    final int y = (int) event.getY();
                    if ((x > v.getPaddingLeft()) && (y > v.getPaddingTop())
                            && (x < v.getWidth() - v.getPaddingRight())
                            && (y < v.getHeight() - v.getPaddingBottom())) {
                        v.performClick();
                    }
                    v.setClickable(mCachedClickableState);
                    break;
            }
        }

        // Pass the event to View.onHoverEvent() to handle accessibility.
        v.onHoverEvent(event);

        // Consume the event so it doesn't fall through to other views.
        return true;
    }
}