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
package com.android.internal.os.statsd.libstats;

import static com.google.common.truth.Truth.assertThat;

import android.app.StatsManager;
import android.util.Log;

import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.AppBreadcrumbReported;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.GaugeBucketInfo;
import com.android.os.StatsLog.GaugeMetricData;
import com.android.os.StatsLog.StatsLogReport;
import com.android.os.StatsLog.StatsdStatsReport;
import com.android.os.StatsLog.StatsdStatsReport.ConfigStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Util class for constructing statsd configs.
 */
public class StatsConfigUtils {
    public static final String TAG = "statsd.StatsConfigUtils";
    public static final int SHORT_WAIT = 2_000; // 2 seconds.

    /**
     * @return An empty StatsdConfig in serialized proto format.
     */
    public static StatsdConfig.Builder getSimpleTestConfig(long configId) {
        return StatsdConfig.newBuilder().setId(configId)
                .addAllowedLogSource(StatsConfigUtils.class.getPackage().getName());
    }


    public static boolean verifyValidConfigExists(StatsManager statsManager, long configId) {
        StatsdStatsReport report = null;
        try {
            report = StatsdStatsReport.parser().parseFrom(statsManager.getStatsMetadata());
        } catch (Exception e) {
            Log.e(TAG, "getMetadata failed", e);
        }
        assertThat(report).isNotNull();
        boolean foundConfig = false;
        for (ConfigStats configStats : report.getConfigStatsList()) {
            if (configStats.getId() == configId && configStats.getIsValid()
                    && configStats.getDeletionTimeSec() == 0) {
                foundConfig = true;
            }
        }
        return foundConfig;
    }

    public static AtomMatcher getAppBreadcrumbMatcher(long id, int label) {
        return AtomMatcher.newBuilder()
                .setId(id)
                .setSimpleAtomMatcher(
                        SimpleAtomMatcher.newBuilder()
                                .setAtomId(Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER)
                                .addFieldValueMatcher(FieldValueMatcher.newBuilder()
                                        .setField(AppBreadcrumbReported.LABEL_FIELD_NUMBER)
                                        .setEqInt(label)
                                )
                )
                .build();
    }

    public static ConfigMetricsReport getConfigMetricsReport(StatsManager statsManager,
            long configId) {
        ConfigMetricsReportList reportList = null;
        try {
            reportList = ConfigMetricsReportList.parser()
                    .parseFrom(statsManager.getReports(configId));
        } catch (Exception e) {
            Log.e(TAG, "getData failed", e);
        }
        assertThat(reportList).isNotNull();
        assertThat(reportList.getReportsCount()).isEqualTo(1);
        ConfigMetricsReport report = reportList.getReports(0);
        assertThat(report.getDumpReportReason())
                .isEqualTo(ConfigMetricsReport.DumpReportReason.GET_DATA_CALLED);
        return report;

    }
    public static List<Atom> getGaugeMetricDataList(ConfigMetricsReport report) {
        List<Atom> data = new ArrayList<>();
        for (StatsLogReport metric : report.getMetricsList()) {
            for (GaugeMetricData gaugeMetricData : metric.getGaugeMetrics().getDataList()) {
                for (GaugeBucketInfo bucketInfo : gaugeMetricData.getBucketInfoList()) {
                    for (Atom atom : bucketInfo.getAtomList()) {
                        data.add(atom);
                    }
                }
            }
        }
        return data;
    }

    public static List<Atom> getGaugeMetricDataList(StatsManager statsManager, long configId) {
        ConfigMetricsReport report = getConfigMetricsReport(statsManager, configId);
        return getGaugeMetricDataList(report);
    }
}

