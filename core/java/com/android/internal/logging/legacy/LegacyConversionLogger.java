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

import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

/** @hide */
public class LegacyConversionLogger implements TronLogger {
    public static final String VIEW_KEY = "view";
    public static final String TYPE_KEY = "type";
    public static final String EVENT_KEY = "data";

    public static final int TYPE_COUNTER = 1;
    public static final int TYPE_HISTOGRAM = 2;
    public static final int TYPE_EVENT = 3;

    private final Queue<LogMaker> mQueue;
    private HashMap<String, Boolean> mConfig;

    public LegacyConversionLogger() {
        mQueue = new LinkedList<>();
    }

    public Queue<LogMaker> getEvents() {
        return mQueue;
    }

    @Override
    public void increment(String counterName) {
        LogMaker b = new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_COUNTER)
                .setCounterName(counterName)
                .setCounterValue(1);
        mQueue.add(b);
    }

    @Override
    public void incrementBy(String counterName, int value) {
        LogMaker b = new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_COUNTER)
                .setCounterName(counterName)
                .setCounterValue(value);
        mQueue.add(b);
    }

    @Override
    public void incrementIntHistogram(String counterName, int bucket) {
        LogMaker b = new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM)
                .setCounterName(counterName)
                .setCounterBucket(bucket)
                .setCounterValue(1);
        mQueue.add(b);
    }

    @Override
    public void incrementLongHistogram(String counterName, long bucket) {
        LogMaker b = new LogMaker(MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM)
                .setCounterName(counterName)
                .setCounterBucket(bucket)
                .setCounterValue(1);
        mQueue.add(b);
    }

    @Override
    public LogMaker obtain() {
        return new LogMaker(MetricsEvent.VIEW_UNKNOWN);
    }

    @Override
    public void dispose(LogMaker proto) {
    }

    @Override
    public void addEvent(LogMaker proto) {
        mQueue.add(proto);
    }

    @Override
    public boolean getConfig(String configName) {
        if (mConfig != null && mConfig.containsKey(configName)) {
            return mConfig.get(configName);
        }
        return false;
    }

    @Override
    public void setConfig(String configName, boolean newValue) {
        if (mConfig == null) {
            mConfig = new HashMap<>();
        }
        mConfig.put(configName, newValue);
    }
}
