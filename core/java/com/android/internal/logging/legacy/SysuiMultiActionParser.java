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

/**
 * ...and one parser to rule them all.
 *
 * This should, at some point in the future, be the only parser.
 * @hide
 */
public class SysuiMultiActionParser extends TagParser {
    private static final String TAG = "SysuiMultiActionParser";
    private static final int EVENTLOG_TAG = 524292;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        try {
            logger.addEvent(new LogMaker(operands).setTimestamp(eventTimeMs));
        } catch (ClassCastException e) {
            if (debug) {
                Log.e(TAG, "unexpected operand type: ", e);
            }
        }
    }
}
