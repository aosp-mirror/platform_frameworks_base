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

/**
 * Parse the Android counter event logs.
 * @hide
 */
public class CounterParser extends TagParser {
    private static final String TAG = "CounterParser";
    private static final int EVENTLOG_TAG = 524290;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length >= 2) {
            try {
                String name = ((String) operands[0]);
                int value = (Integer) operands[1];
                logCount(logger, name, value);
            } catch (ClassCastException e) {
                if (debug) {
                    Log.d(TAG, "unexpected operand type", e);
                }
            }
        } else if (debug) {
            Log.d(TAG, "wrong number of operands: " + operands.length);
        }
    }

    protected void logCount(TronLogger logger, String name, int value) {
        logger.incrementBy(TronCounters.TRON_AOSP_PREFIX + name, value);
    }
}
