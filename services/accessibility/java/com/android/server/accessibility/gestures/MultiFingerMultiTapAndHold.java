/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * This class matches gestures of the form multi-finger multi-tap and hold. The number of fingers
 * and taps for each instance is specified in the constructor.
 */
class MultiFingerMultiTapAndHold extends MultiFingerMultiTap {

    MultiFingerMultiTapAndHold(
            Context context,
            int fingers,
            int taps,
            int gestureId,
            GestureMatcher.StateChangeListener listener) {
        super(context, fingers, taps, gestureId, listener);
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        super.onPointerDown(event, rawEvent, policyFlags);
        if (mIsTargetFingerCountReached && mCompletedTapCount + 1 == mTargetTapCount) {
            completeAfterLongPressTimeout(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mCompletedTapCount + 1 == mTargetFingerCount) {
            // Calling super.onUp  would complete the multi-tap version of this.
            cancelGesture(event, rawEvent, policyFlags);
        } else {
            super.onUp(event, rawEvent, policyFlags);
            cancelAfterDoubleTapTimeout(event, rawEvent, policyFlags);
        }
    }

    @Override
    public String getGestureName() {
        final StringBuilder builder = new StringBuilder();
        builder.append(mTargetFingerCount).append("-Finger ");
        if (mTargetTapCount == 1) {
            builder.append("Single");
        } else if (mTargetTapCount == 2) {
            builder.append("Double");
        } else if (mTargetTapCount == 3) {
            builder.append("Triple");
        } else if (mTargetTapCount > 3) {
            builder.append(mTargetTapCount);
        }
        return builder.append(" Tap and hold").toString();
    }
}
