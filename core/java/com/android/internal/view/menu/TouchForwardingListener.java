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

package com.android.internal.view.menu;

import android.view.MotionEvent;
import android.view.View;

/**
 * Touch listener used to intercept touches and forward them out of a view.
 */
abstract class TouchForwardingListener implements View.OnTouchListener {
    /** Whether this listener is currently forwarding touch events. */
    private boolean mForwarding;

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        final int actionMasked = ev.getActionMasked();

        if (mForwarding) {
            // Rejecting the event or ending the stream stops forwarding.
            if (!onTouchForwarded(v, ev) || actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL) {
                stopForwarding();
            }
        } else {
            if (onTouchObserved(v, ev)) {
                startForwarding();
            }
        }

        return mForwarding;
    }

    public void startForwarding() {
        mForwarding = true;
    }

    public void stopForwarding() {
        mForwarding = false;
    }

    /**
     * Attempts to start forwarding motion events.
     *
     * @param v The view that triggered forwarding.
     * @return True to start forwarding motion events, or false to cancel.
     */
    public abstract boolean onTouchObserved(View v, MotionEvent ev);

    /**
     * Handles forwarded motion events.
     *
     * @param v The view from which the event was forwarded.
     * @param ev The forwarded motion event.
     * @return True to continue forwarding motion events, or false to cancel.
     */
    public abstract boolean onTouchForwarded(View v, MotionEvent ev);
}
