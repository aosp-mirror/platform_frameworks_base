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
 * Parse the Android framework sysui view visibility logs.
 * @hide
 */
public class SysuiViewVisibilityParser extends TagParser {
    private static final String TAG = "SysuiViewVisibility";
    private static final int EVENTLOG_TAG = 524287;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 2) {
            try {
                int category = ((Integer) operands[0]).intValue();
                boolean visibility = ((Integer) operands[1]).intValue() != 0;

                LogMaker proto = logger.obtain();
                proto.setCategory(category);
                proto.setType(visibility ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE);
                proto.setTimestamp(eventTimeMs);
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
