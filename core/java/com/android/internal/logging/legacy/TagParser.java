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
 * Abstraction layer between EventLog static classes and the actual TagParsers.
 * @hide
 */
public abstract class TagParser {
    private static final String TAG = "TagParser";

    protected int mSinceCreationMillis;
    protected int mSinceUpdateMillis;
    protected int mSinceVisibleMillis;

    abstract int getTag();

    @VisibleForTesting
    abstract public void parseEvent(TronLogger logger, long eventTimeMs, Object[] objects);

    /**
     * Parse the event into the proto: return true if proto was modified.
     */
    public void  parseEvent(TronLogger logger, EventLogCollector.Event event) {
        final boolean debug = Util.debug();
        Object data = event.getData();
        Object[] objects;
        if (data instanceof Object[]) {
            objects = (Object[]) data;
            for (int i = 0; i < objects.length; i++) {
                if (objects[i] == null) {
                    if (debug) {
                        Log.d(TAG, "unexpected null value:" + event.getTag());
                    }
                    return;
                }
            }
        } else {
            // wrap scalar objects
            objects = new Object[1];
            objects[0] = data;
        }

        parseEvent(logger, event.getTimeNanos() / 1000000, objects);
    }

    protected void resetTimes() {
        mSinceCreationMillis = 0;
        mSinceUpdateMillis = 0;
        mSinceVisibleMillis = 0;
    }

    public void parseTimes(Object[] operands, int index) {
        resetTimes();

        if (operands.length > index && operands[index] instanceof Integer) {
            mSinceCreationMillis = (Integer) operands[index];
        }

        index++;
        if (operands.length > index && operands[index] instanceof Integer) {
            mSinceUpdateMillis = (Integer) operands[index];
        }

        index++;
        if (operands.length > index && operands[index] instanceof Integer) {
            mSinceVisibleMillis = (Integer) operands[index];
        }
    }

   public void filltimes(LogMaker proto) {
        if (mSinceCreationMillis != 0) {
            proto.addTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS,
                    mSinceCreationMillis);
        }
        if (mSinceUpdateMillis != 0) {
            proto.addTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS,
                    mSinceUpdateMillis);
        }
        if (mSinceVisibleMillis != 0) {
            proto.addTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS,
                    mSinceVisibleMillis);
        }
    }
}
