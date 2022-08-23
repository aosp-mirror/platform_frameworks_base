/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.accessibility.cursor;

import android.content.Context;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;

import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.BaseEventStreamTransformation;

/**
 * Handles touch input for the Software Cursor accessibility feature.
 *
 * The behavior is as follows:
 *
 * <ol>
 *   <li> 1. Enable Software Cursor by swiping from the trigger zone on the edge of the screen.
 *   <li> 2. Move the cursor by swiping anywhere on the touch screen. Select by tapping anywhere on
 *   the touch screen.
 *   <li> 3. Put the cursor away by swiping it past either edge of the screen.
 * </ol>
 *
 * TODO(b/243552818): Determine how to handle multi-display.
 */
public final class SoftwareCursorGestureHandler extends BaseEventStreamTransformation {


    private static final String LOG_TAG = "SWCursorGestureHandler";
    private static final boolean DEBUG_ALL = Log.isLoggable("SWCursorGestureHandler",
            Log.DEBUG);

    Context mContext;
    SoftwareCursorManager mSoftwareCursorManager;
    AccessibilityTraceManager mTraceManager;

    public SoftwareCursorGestureHandler(Context context,
                          SoftwareCursorManager softwareCursorManager,
                          AccessibilityTraceManager traceManager) {
        mContext = context;
        mSoftwareCursorManager = softwareCursorManager;
        mTraceManager = traceManager;
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "onMotionEvent(" + event + ")");
        }
        // TODO: Add logic.
        // TODO: Prevent users from enabling this filter in conjuntion with TouchExplorer.
        super.onMotionEvent(event, rawEvent, policyFlags);
    }


}
