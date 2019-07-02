/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.os;

import android.metrics.LogMaker;
import android.os.Process;
import android.util.StatsLog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import dalvik.system.VMRuntime.HiddenApiUsageLogger;

class StatsdHiddenApiUsageLogger implements HiddenApiUsageLogger {

    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private static final StatsdHiddenApiUsageLogger sInstance = new StatsdHiddenApiUsageLogger();
    private int mHiddenApiAccessLogSampleRate = 0;
    private int mHiddenApiAccessStatslogSampleRate = 0;

    static void setHiddenApiAccessLogSampleRates(int sampleRate, int newSampleRate) {
        if (sampleRate != -1) {
            sInstance.mHiddenApiAccessLogSampleRate = sampleRate;
        }
        if (newSampleRate != -1) {
            sInstance.mHiddenApiAccessStatslogSampleRate = newSampleRate;
        }
    }

    static StatsdHiddenApiUsageLogger getInstance() {
        return StatsdHiddenApiUsageLogger.sInstance;
    }

    public void hiddenApiUsed(int sampledValue, String packageName, String signature,
            int accessMethod, boolean accessDenied) {
        if (sampledValue < mHiddenApiAccessLogSampleRate) {
            logUsage(packageName, signature, accessMethod, accessDenied);
        }

        if (sampledValue < mHiddenApiAccessStatslogSampleRate) {
            newLogUsage(signature, accessMethod, accessDenied);
        }
    }

    private void logUsage(String packageName, String signature, int accessMethod,
            boolean accessDenied) {
        int accessMethodMetric = HiddenApiUsageLogger.ACCESS_METHOD_NONE;
        switch(accessMethod) {
            case HiddenApiUsageLogger.ACCESS_METHOD_NONE:
                accessMethodMetric = MetricsEvent.ACCESS_METHOD_NONE;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_REFLECTION:
                accessMethodMetric = MetricsEvent.ACCESS_METHOD_REFLECTION;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_JNI:
                accessMethodMetric = MetricsEvent.ACCESS_METHOD_JNI;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_LINKING:
                accessMethodMetric = MetricsEvent.ACCESS_METHOD_LINKING;
                break;
        }

        LogMaker logMaker = new LogMaker(MetricsEvent.ACTION_HIDDEN_API_ACCESSED)
                .setPackageName(packageName)
                .addTaggedData(MetricsEvent.FIELD_HIDDEN_API_SIGNATURE, signature)
                .addTaggedData(MetricsEvent.FIELD_HIDDEN_API_ACCESS_METHOD,
                    accessMethodMetric);

        if (accessDenied) {
            logMaker.addTaggedData(MetricsEvent.FIELD_HIDDEN_API_ACCESS_DENIED, 1);
        }

        mMetricsLogger.write(logMaker);
    }

    private void newLogUsage(String signature, int accessMethod, boolean accessDenied) {
        int accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__NONE;
        switch(accessMethod) {
            case HiddenApiUsageLogger.ACCESS_METHOD_NONE:
                accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__NONE;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_REFLECTION:
                accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__REFLECTION;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_JNI:
                accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__JNI;
                break;
            case HiddenApiUsageLogger.ACCESS_METHOD_LINKING:
                accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__LINKING;
                break;
        }

        int uid = Process.myUid();
        StatsLog.write(StatsLog.HIDDEN_API_USED, uid, signature, accessMethodProto, accessDenied);
    }
}
