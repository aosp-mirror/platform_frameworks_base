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

/**
 * Parse the Android histogram event logs.
 * @hide
 */
public class HistogramParser extends CounterParser {
    private static final String TAG = "HistogramParser";
    private static final int EVENTLOG_TAG = 524291;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    protected void logCount(TronLogger logger, String name, int value) {
        logger.incrementIntHistogram("tron_varz_" + name, value);
    }
}
