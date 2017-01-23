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
 * Parse the Android framework sysui action logs.
 * @hide
 */
public class SysuiActionParser extends TagParser {
    private static final String TAG = "SysuiActionParser";
    private static final int EVENTLOG_TAG = 524288;

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        try {
            String packageName = null;
            int subType = -1;
            boolean hasSubType = false;
            if (operands.length > 1) {
                String arg = (String) operands[1];
                if (arg.equals("true")) {
                    hasSubType = true;
                    subType = 1;
                } else if (arg.equals("false")) {
                    hasSubType = true;
                    subType = 0;
                } else if (arg.matches("^-?\\d+$")) {
                    try {
                        subType = Integer.valueOf(arg);
                        hasSubType = true;
                    } catch (NumberFormatException e) {
                    }
                } else {
                    packageName = arg;
                }
            }
            if (operands.length > 0) {
                int category = ((Integer) operands[0]).intValue();
                LogMaker proto = logger.obtain();
                proto.setCategory(category);
                proto.setType(MetricsEvent.TYPE_ACTION);
                proto.setTimestamp(eventTimeMs);
                if (packageName != null) {
                    proto.setPackageName(packageName);
                }
                if (hasSubType) {
                    proto.setSubtype(subType);
                }
                logger.addEvent(proto);
            }
        } catch (ClassCastException e) {
            if (debug) {
                Log.e(TAG, "unexpected operand type: ", e);
            }
        }
    }
}
