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
 * limitations under the License.
 */

package com.android.systemui.recents.misc;

import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import com.android.systemui.recents.Constants;

/**
 * A trigger for catching a debug chord.
 * We currently use volume up then volume down to trigger this mode.
 */
public class DebugTrigger {

    Handler mHandler;
    Runnable mTriggeredRunnable;

    int mLastKeyCode;
    long mLastKeyCodeTime;

    public DebugTrigger(Runnable triggeredRunnable) {
        mHandler = new Handler();
        mTriggeredRunnable = triggeredRunnable;
    }

    /** Resets the debug trigger */
    void reset() {
        mLastKeyCode = 0;
        mLastKeyCodeTime = 0;
    }

    /**
     * Processes a key event and tests if it is a part of the trigger. If the chord is complete,
     * then we just call the callback.
     */
    public void onKeyEvent(int keyCode) {
        if (!Constants.DebugFlags.App.EnableDebugMode) return;

        if (mLastKeyCode == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                mLastKeyCode = keyCode;
                mLastKeyCodeTime = SystemClock.uptimeMillis();
                return;
            }
        } else {
            if (mLastKeyCode == KeyEvent.KEYCODE_VOLUME_UP &&
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if ((SystemClock.uptimeMillis() - mLastKeyCodeTime) < 750) {
                    mTriggeredRunnable.run();
                }
            }
        }
        reset();
    }
}
