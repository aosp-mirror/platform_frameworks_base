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

package com.android.server.accessibility.magnification;

import android.annotation.Nullable;
import android.view.MotionEvent;

import com.android.server.accessibility.EventStreamTransformation;

/**
 * A data structure to store the parameters of
 * {@link EventStreamTransformation#onMotionEvent(MotionEvent, MotionEvent, int)}.
 */
final class MotionEventInfo {

    public MotionEvent mEvent;
    public MotionEvent mRawEvent;
    public int mPolicyFlags;

    static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        return new MotionEventInfo(MotionEvent.obtain(event), MotionEvent.obtain(rawEvent),
                policyFlags);
    }

    MotionEventInfo(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        mEvent = event;
        mRawEvent = rawEvent;
        mPolicyFlags = policyFlags;

    }

    void recycle() {
        mEvent = recycleAndNullify(mEvent);
        mRawEvent = recycleAndNullify(mRawEvent);
    }

    @Override
    public String toString() {
        return MotionEvent.actionToString(mEvent.getAction()).replace("ACTION_", "");
    }

    private static MotionEvent recycleAndNullify(@Nullable MotionEvent event) {
        if (event != null) {
            event.recycle();
        }
        return null;
    }
}
