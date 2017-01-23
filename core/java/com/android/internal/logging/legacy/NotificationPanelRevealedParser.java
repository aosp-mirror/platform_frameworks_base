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
 * Parse the Android notification panel visibility event logs.
 * @hide
 */
public class NotificationPanelRevealedParser extends TagParser {
    private static final String TAG = "NotificationPanelRevea";
    private static final int EVENTLOG_TAG = 27500;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 1) {
            try {
                int load = ((Integer) operands[0]).intValue();
                //logger.incrementBy(TronCounters.TRON_NOTIFICATION_LOAD, load);
            } catch (ClassCastException e) {
                if (debug) {
                    Log.e(TAG, "unexpected operand type: ", e);
                }
            }
        }

        LogMaker proto = logger.obtain();
        proto.setCategory(MetricsEvent.NOTIFICATION_PANEL);
        proto.setType(MetricsEvent.TYPE_OPEN);
        proto.setTimestamp(eventTimeMs);
        logger.addEvent(proto);
    }
}
