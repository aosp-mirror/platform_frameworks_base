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
 * Parse the Android notification cancellation event logs.
 * @hide
 */
public class NotificationCanceledParser extends TagParser {
    private static final String TAG = "NotificationCanceled";
    private static final int EVENTLOG_TAG = 27530;

    // from com.android.server.notification.NotificationManagerService
    static final int REASON_DELEGATE_CLICK = 1;
    static final int REASON_DELEGATE_CANCEL = 2;
    static final int REASON_DELEGATE_CANCEL_ALL = 3;
    static final int REASON_PACKAGE_BANNED = 7;
    static final int REASON_LISTENER_CANCEL = 10;
    static final int REASON_LISTENER_CANCEL_ALL = 11;

    private final NotificationKey mKey;

    public NotificationCanceledParser() {
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
                final int reason = (Integer) operands[1];
                parseTimes(operands, 2);

                // handle old style log
                // TODO: delete once M is launched
                if (operands.length < 5) {
                    mSinceVisibleMillis = mSinceUpdateMillis;
                    mSinceUpdateMillis = 0;
                }

                boolean intentional = true;
                switch (reason) {
                    case REASON_DELEGATE_CANCEL:
                    case REASON_DELEGATE_CANCEL_ALL:
                    case REASON_LISTENER_CANCEL:
                    case REASON_LISTENER_CANCEL_ALL:
                    case REASON_DELEGATE_CLICK:
                    case REASON_PACKAGE_BANNED:
                        break;
                    default:
                        intentional = false;
                }

                if (mKey.parse(keyString)) {
                    if (intentional) {
                        LogMaker proto = logger.obtain();
                        proto.setCategory(MetricsEvent.NOTIFICATION_ITEM);
                        proto.setType(MetricsEvent.TYPE_DISMISS);
                        proto.setSubtype(reason);
                        proto.setTimestamp(eventTimeMs);
                        proto.setPackageName(mKey.mPackageName);
                        proto.addTaggedData(MetricsEvent.NOTIFICATION_ID, mKey.mId);
                        proto.addTaggedData(MetricsEvent.NOTIFICATION_TAG, mKey.mTag);
                        filltimes(proto);
                        logger.addEvent(proto);
                    }
                } else if (debug) {
                    Log.e(TAG, "unable to parse key: " + keyString);
                }
            } catch (ClassCastException e) {
                if (debug) {
                    Log.e(TAG, "unexpected operand type: ", e);
                }
            }
        }
    }
}
