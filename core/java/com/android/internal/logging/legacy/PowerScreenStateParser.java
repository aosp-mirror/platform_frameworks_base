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
public class PowerScreenStateParser extends TagParser {
    private static final String TAG = "PowerScreenStateParser";
    private static final int EVENTLOG_TAG = 2728;

    // source of truth is android.view.WindowManagerPolicy, why:
    // 0: on
    // 1: OFF_BECAUSE_OF_ADMIN
    // 2: OFF_BECAUSE_OF_USER
    // 3: OFF_BECAUSE_OF_TIMEOUT

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 2) {
            try {
                // (offOrOn|1|5),(becauseOfUser|1|5),(totalTouchDownTime|2|3),(touchCycles|1|1)
                boolean state = (((Integer) operands[0]).intValue()) == 1;
                int why = ((Integer) operands[1]).intValue();

                LogMaker proto = logger.obtain();
                proto.setCategory(MetricsEvent.SCREEN);
                proto.setType(state ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE);
                proto.setTimestamp(eventTimeMs);
                proto.setSubtype(why);
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
