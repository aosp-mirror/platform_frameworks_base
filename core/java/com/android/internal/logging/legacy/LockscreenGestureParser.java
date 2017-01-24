/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.logging.legacy;

import android.util.Log;

import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Parse the Android lockscreen gesture logs.
 * @hide
 */
public class LockscreenGestureParser extends TagParser {
    private static final String TAG = "LockscreenGestureParser";
    private static final int EVENTLOG_TAG = 36021;

    // source of truth is com.android.systemui.EventLogConstants
    public static final int[] GESTURE_TYPE_MAP = {
            MetricsEvent.VIEW_UNKNOWN,  // there is no type 0
            MetricsEvent.ACTION_LS_UNLOCK,  // SYSUI_LOCKSCREEN_GESTURE_SWIPE_UP_UNLOCK = 1
            MetricsEvent.ACTION_LS_SHADE,  // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_FULL_SHADE = 2
            MetricsEvent.ACTION_LS_HINT,  // SYSUI_LOCKSCREEN_GESTURE_TAP_UNLOCK_HINT = 3
            MetricsEvent.ACTION_LS_CAMERA,  // SYSUI_LOCKSCREEN_GESTURE_SWIPE_CAMERA = 4
            MetricsEvent.ACTION_LS_DIALER,  // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DIALER = 5
            MetricsEvent.ACTION_LS_LOCK,  // SYSUI_LOCKSCREEN_GESTURE_TAP_LOCK = 6
            MetricsEvent.ACTION_LS_NOTE,  // SYSUI_LOCKSCREEN_GESTURE_TAP_NOTIFICATION_ACTIVATE = 7
            MetricsEvent.ACTION_LS_QS,  // SYSUI_LOCKSCREEN_GESTURE_SWIPE_DOWN_QS = 8
            MetricsEvent.ACTION_SHADE_QS_PULL,  // SYSUI_SHADE_GESTURE_SWIPE_DOWN_QS = 9
            MetricsEvent.ACTION_SHADE_QS_TAP  // SYSUI_TAP_TO_OPEN_QS = 10
    };

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 1) {
            try {
                int type = ((Integer) operands[0]).intValue();
                // ignore gesture length in operands[1]
                // ignore gesture velocity in operands[2]

                int category = MetricsEvent.VIEW_UNKNOWN;
                if (type < GESTURE_TYPE_MAP.length) {
                    category = GESTURE_TYPE_MAP[type];
                }
                if (category != MetricsEvent.VIEW_UNKNOWN) {
                    LogMaker proto = logger.obtain();
                    proto.setCategory(category);
                    proto.setType(MetricsEvent.TYPE_ACTION);
                    proto.setTimestamp(eventTimeMs);
                    logger.addEvent(proto);
                }
            } catch (ClassCastException e) {
                if (debug) {
                    Log.e(TAG, "unexpected operand type: ", e);
                }
            }
        } else if (debug) {
            Log.w(TAG, "wrong number of operands: " + operands.length);
        }
    }
}
