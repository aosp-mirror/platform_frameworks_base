/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.util

import com.android.wm.shell.desktopmode.education.data.WindowingEducationProto

/**
 * Constructs a [WindowingEducationProto] object, populating its fields with the provided
 * parameters.
 *
 * Any fields without corresponding parameters will retain their default values.
 */
fun createWindowingEducationProto(
    educationViewedTimestampMillis: Long? = null,
    featureUsedTimestampMillis: Long? = null,
    appUsageStats: Map<String, Int>? = null,
    appUsageStatsLastUpdateTimestampMillis: Long? = null
): WindowingEducationProto =
    WindowingEducationProto.newBuilder()
        .apply {
          if (educationViewedTimestampMillis != null) {
            setEducationViewedTimestampMillis(educationViewedTimestampMillis)
          }
          if (featureUsedTimestampMillis != null) {
            setFeatureUsedTimestampMillis(featureUsedTimestampMillis)
          }
          setAppHandleEducation(
              createAppHandleEducationProto(appUsageStats, appUsageStatsLastUpdateTimestampMillis))
        }
        .build()

/**
 * Constructs a [WindowingEducationProto.AppHandleEducation] object, populating its fields with the
 * provided parameters.
 *
 * Any fields without corresponding parameters will retain their default values.
 */
fun createAppHandleEducationProto(
    appUsageStats: Map<String, Int>? = null,
    appUsageStatsLastUpdateTimestampMillis: Long? = null
): WindowingEducationProto.AppHandleEducation =
    WindowingEducationProto.AppHandleEducation.newBuilder()
        .apply {
          if (appUsageStats != null) putAllAppUsageStats(appUsageStats)
          if (appUsageStatsLastUpdateTimestampMillis != null) {
            setAppUsageStatsLastUpdateTimestampMillis(appUsageStatsLastUpdateTimestampMillis)
          }
        }
        .build()
