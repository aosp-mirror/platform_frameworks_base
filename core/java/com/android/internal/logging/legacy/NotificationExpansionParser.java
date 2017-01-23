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
 * Parse the Android notification expansion event logs.
 * @hide
 */
public class NotificationExpansionParser extends TagParser {
    private static final String TAG = "NotificationExpansion";
    private static final int EVENTLOG_TAG = 27511;

    private final NotificationKey mKey;

    public NotificationExpansionParser() {
        mKey = new NotificationKey();
    }

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length > 2) {
            try {
                if (mKey.parse((String) operands[0])) {
                    boolean byUser = ((Integer) operands[1]) == 1;
                    boolean expanded = ((Integer) operands[2]) == 1;
                    parseTimes(operands, 3);

                    if (!byUser || !expanded) {
                        return;
                    }
                    LogMaker proto = logger.obtain();
                    proto.setCategory(MetricsEvent.NOTIFICATION_ITEM);
                    proto.setType(MetricsEvent.TYPE_DETAIL);
                    proto.setTimestamp(eventTimeMs);
                    proto.setPackageName(mKey.mPackageName);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_ID, mKey.mId);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_TAG, mKey.mTag);
                    filltimes(proto);
                    logger.addEvent(proto);
                } else if (debug) {
                    Log.e(TAG, "unable to parse key.");
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
