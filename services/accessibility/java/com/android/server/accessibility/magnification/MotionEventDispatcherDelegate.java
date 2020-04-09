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

import static android.view.MotionEvent.ACTION_DOWN;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.R;

import java.util.List;

/**
 * Responsible for dispatching delayed events.
 */
class MotionEventDispatcherDelegate {

    private static final String TAG = MotionEventDispatcherDelegate.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final EventDispatcher mEventDispatcher;
    private final int mMultiTapMaxDelay;
    private long mLastDelegatedDownEventTime;

    interface  EventDispatcher {
        void dispatchMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags);
    }

    MotionEventDispatcherDelegate(Context context,
            EventDispatcher eventDispatcher) {
        mEventDispatcher = eventDispatcher;
        mMultiTapMaxDelay = ViewConfiguration.getDoubleTapTimeout()
                + context.getResources().getInteger(
                R.integer.config_screen_magnification_multi_tap_adjustment);
    }

    void sendDelayedMotionEvents(List<MotionEventInfo> delayedEventQueue,
            long lastDetectingDownEventTime) {
        if (delayedEventQueue == null) {
            return;
        }
        // Adjust down time to prevent subsequent modules being misleading, and also limit
        // the maximum offset to mMultiTapMaxDelay to prevent the down time of 2nd tap is
        // in the future when multi-tap happens.
        final long offset = Math.min(
                SystemClock.uptimeMillis() - lastDetectingDownEventTime, mMultiTapMaxDelay);

        for (MotionEventInfo info: delayedEventQueue) {
            info.mEvent.setDownTime(info.mEvent.getDownTime() + offset);
            dispatchMotionEvent(info.mEvent, info.mRawEvent, info.mPolicyFlags);
            info.recycle();
        }
    }

    void dispatchMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Ensure that the state at the end of delegation is consistent with the last delegated
        // UP/DOWN event in queue: still delegating if pointer is down, detecting otherwise
        if (event.getActionMasked() == ACTION_DOWN) {
            mLastDelegatedDownEventTime = event.getDownTime();
            if (DBG) {
                Log.d(TAG, "dispatchMotionEvent mLastDelegatedDownEventTime time = "
                        + mLastDelegatedDownEventTime);
            }
        }

        // We cache some events to see if the user wants to trigger magnification.
        // If no magnification is triggered we inject these events with adjusted
        // time and down time to prevent subsequent transformations being confused
        // by stale events. After the cached events, which always have a down, are
        // injected we need to also update the down time of all subsequent non cached
        // events. All delegated events cached and non-cached are delivered here.
        if (DBG) {
            Log.d(TAG, "dispatchMotionEvent original down time = " + event.getDownTime());
        }
        event.setDownTime(mLastDelegatedDownEventTime);
        mEventDispatcher.dispatchMotionEvent(event, rawEvent, policyFlags);
    }
}
