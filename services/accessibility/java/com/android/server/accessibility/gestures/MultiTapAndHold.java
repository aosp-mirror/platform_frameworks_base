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

package com.android.server.accessibility.gestures;

import android.content.Context;
import android.view.MotionEvent;

/**
 * This class matches gestures of the form multi-tap and hold. The number of taps for each instance
 * is specified in the constructor.
 * @hide
 */
public class MultiTapAndHold extends MultiTap {
    public MultiTapAndHold(
            Context context, int taps, int gesture, GestureMatcher.StateChangeListener listener) {
        super(context, taps, gesture, listener);
    }

    public MultiTapAndHold(Context context, int taps, int gesture, int multiTapTimeout,
            GestureMatcher.StateChangeListener listener) {
        super(context, taps, gesture, multiTapTimeout, listener);
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        super.onDown(event, rawEvent, policyFlags);
        if (mCurrentTaps + 1 == mTargetTaps) {
            completeAfterLongPressTimeout(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        super.onUp(event, rawEvent, policyFlags);
    }

    @Override
    public String getGestureName() {
        switch (mTargetTaps) {
            case 2:
                return "Double Tap and Hold";
            case 3:
                return "Triple Tap and Hold";
            default:
                return Integer.toString(mTargetTaps) + " Taps and Hold";
        }
    }
}
