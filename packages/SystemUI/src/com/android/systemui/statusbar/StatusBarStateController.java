/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.util.ArraySet;

/**
 * Tracks and reports on {@link StatusBarState}.
 */
public class StatusBarStateController {

    private static final int MAX_STATE = StatusBarState.FULLSCREEN_USER_SWITCHER;
    private static final int MIN_STATE = StatusBarState.SHADE;

    private final ArraySet<StateListener> mListeners = new ArraySet<>();
    private int mState;
    private int mLastState;
    private boolean mLeaveOpenOnKeyguardHide;

    public int getState() {
        return mState;
    }

    public void setState(int state) {
        if (state > MAX_STATE || state < MIN_STATE) {
            throw new IllegalArgumentException("Invalid state " + state);
        }
        if (state == mState) {
            return;
        }
        synchronized (mListeners) {
            for (StateListener listener : mListeners) {
                listener.onStatePreChange(mState, state);
            }
            mLastState = mState;
            mState = state;
            for (StateListener listener : mListeners) {
                listener.onStateChanged(mState);
            }
        }
    }

    public boolean goingToFullShade() {
        return mState == StatusBarState.SHADE && mLeaveOpenOnKeyguardHide;
    }

    public void setLeaveOpenOnKeyguardHide(boolean leaveOpen) {
        mLeaveOpenOnKeyguardHide = leaveOpen;
    }

    public boolean leaveOpenOnKeyguardHide() {
        return mLeaveOpenOnKeyguardHide;
    }

    public boolean fromShadeLocked() {
        return mLastState == StatusBarState.SHADE_LOCKED;
    }

    public void addListener(StateListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    public void removeListener(StateListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    public interface StateListener {
        public default void onStatePreChange(int oldState, int newState) {
        }

        public void onStateChanged(int newState);
    }
}
