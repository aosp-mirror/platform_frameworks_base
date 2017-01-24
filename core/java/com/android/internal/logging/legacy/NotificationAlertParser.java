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
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Parse the new Android notification alert event logs.
 * @hide
 */
public class NotificationAlertParser extends TagParser {
    private static final String TAG = "NotificationAlertParser";
    private static final int EVENTLOG_TAG = 27532;

    @VisibleForTesting
    static final int BUZZ = 0x00000001;
    @VisibleForTesting
    static final int BEEP = 0x00000002;
    @VisibleForTesting
    static final int BLINK = 0x00000004;

    private final NotificationKey mKey;

    public NotificationAlertParser() {
        mKey = new NotificationKey();
    }

    @Override
    public int getTag() {
        return EVENTLOG_TAG;
    }

    @Override
    public void parseEvent(TronLogger logger, long eventTimeMs, Object[] operands) {
        final boolean debug = Util.debug();
        if (operands.length > 3) {
            try {
                final String keyString = (String) operands[0];
                final boolean buzz = ((Integer) operands[1]) == 1;
                final boolean beep = ((Integer) operands[2]) == 1;
                final boolean blink = ((Integer) operands[3]) == 1;

                if (mKey.parse(keyString)) {
                    LogMaker proto = logger.obtain();
                    proto.setCategory(MetricsEvent.NOTIFICATION_ALERT);
                    proto.setType(MetricsEvent.TYPE_OPEN);
                    proto.setSubtype((buzz ? BUZZ : 0) | (beep ? BEEP : 0) | (blink ? BLINK : 0));
                    proto.setTimestamp(eventTimeMs);
                    proto.setPackageName(mKey.mPackageName);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_ID, mKey.mId);
                    proto.addTaggedData(MetricsEvent.NOTIFICATION_TAG, mKey.mTag);
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
