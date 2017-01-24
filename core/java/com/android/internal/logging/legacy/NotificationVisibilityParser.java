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
 * Parse the new Android notification visibility event logs.
 * @hide
 */
public class NotificationVisibilityParser extends TagParser {
    private static final String TAG = "NotificationVisibility";
    private static final int EVENTLOG_TAG = 27531;

    private final NotificationKey mKey;

    public NotificationVisibilityParser() {
        mKey = new NotificationKey();
    }

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length > 1) {
            try {
                final String keyString = (String) operands[0];
                final boolean visible = ((Integer) operands[1]) == 1;
                parseTimes(operands, 2);
                int index = 0;
                if (operands.length > 5 && operands[5] instanceof Integer) {
                    index = (Integer) operands[5];
                }

                if (mKey.parse(keyString)) {
                    LogMaker proto = logger.obtain();
                    proto.setCategory(MetricsEvent.NOTIFICATION_ITEM);
                    proto.setType(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE);
                    proto.setTimestamp(eventTimeMs);
                    proto.setPackageName(mKey.mPackageName);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_ID, mKey.mId);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_TAG, mKey.mTag);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, index);
                    filltimes(proto);
                    logger.addEvent(proto);
                } else {
                    if (debug) {
                        Log.e(TAG, "unable to parse key: " + keyString);
                    }
                }
            } catch (ClassCastException e) {
                if (debug) {
                    Log.e(TAG, "unexpected operand type: ", e);
                }
                return;
            }
        } else if (debug) {
            Log.w(TAG, "wrong number of operands: " + operands.length);
        }
    }
}
