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
public class StatusBarStateParser extends TagParser {
    private static final String TAG = "StatusBarStateParser";
    private static final int EVENTLOG_TAG = 36004;

    // source of truth is com.android.systemui.statusbar.StatusBarState
    public static final int SHADE = 0;
    public static final int KEYGUARD = 1;
    public static final int SHADE_LOCKED = 2;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 6) {
            try {
                // [state, isShowing, isOccluded, isBouncerShowing, isSecure, isCurrentlyInsecure]
                int state = ((Integer) operands[0]).intValue();
                boolean isBouncerShowing = (((Integer) operands[3]).intValue()) == 1;
                int isSecure = ((Integer) operands[4]).intValue();

                int view = MetricsEvent.LOCKSCREEN;
                int type = MetricsEvent.TYPE_OPEN;
                if (state == SHADE) {
                    type = MetricsEvent.TYPE_CLOSE;
                } else if (isBouncerShowing) {
                    view = MetricsEvent.BOUNCER;
                }

                LogMaker proto = logger.obtain();
                proto.setCategory(view);
                proto.setType(type);
                proto.setTimestamp(eventTimeMs);
                proto.setSubtype(isSecure);
                logger.addEvent(proto);
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
