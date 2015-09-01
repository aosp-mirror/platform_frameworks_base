/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Implements "Automatically click on mouse stop" feature.
 *
 * If enabled, it will observe motion events from mouse source, and send click event sequence short
 * while after mouse stops moving. The click will only be performed if mouse movement had been
 * actually detected.
 *
 * Movement detection has tolerance to jitter that may be caused by poor motor control to prevent:
 * <ul>
 *   <li>Initiating unwanted clicks with no mouse movement.</li>
 *   <li>Autoclick never occurring after mouse arriving at target.</li>
 * </ul>
 *
 * Non-mouse motion events, key events (excluding modifiers) and non-movement mouse events cancel
 * the automatic click.
 */
public class AutoclickController implements EventStreamTransformation {
    private EventStreamTransformation mNext;

    public AutoclickController(Context context) {}

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // TODO: Implement this.
        if (mNext != null) {
            mNext.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        // TODO: Implement this.
        if (mNext != null) {
          mNext.onKeyEvent(event, policyFlags);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void clearEvents(int inputSource) {
        if (mNext != null) {
            mNext.clearEvents(inputSource);
        }
    }

    @Override
    public void onDestroy() {
    }
}
