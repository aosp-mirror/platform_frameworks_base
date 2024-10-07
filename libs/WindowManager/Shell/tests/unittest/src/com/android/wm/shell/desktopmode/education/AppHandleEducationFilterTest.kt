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

package com.android.wm.shell.desktopmode.education

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import android.testing.TestableResources
import androidx.test.filters.SmallTest
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository
import com.android.wm.shell.util.GMAIL_PACKAGE_NAME
import com.android.wm.shell.util.YOUTUBE_PACKAGE_NAME
import com.android.wm.shell.util.createAppHandleState
import com.android.wm.shell.util.createTaskInfo
import com.android.wm.shell.util.createWindowingEducationProto
import com.google.common.truth.Truth.assertThat
import kotlin.Int.Companion.MAX_VALUE
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

/** Tests of [AppHandleEducationFilter]
 * Usage: atest AppHandleEducationFilterTest */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class AppHandleEducationFilterTest : ShellTestCase() {
  @Mock private lateinit var datastoreRepository: AppHandleEducationDatastoreRepository
  @Mock private lateinit var mockUsageStatsManager: UsageStatsManager
  private lateinit var educationFilter: AppHandleEducationFilter
  private lateinit var testableResources: TestableResources
  private lateinit var testableContext: TestableContext

  @Before
  fun setup() {
    MockitoAnnotations.initMocks(this)
    testableContext = TestableContext(mContext)
    testableResources =
        testableContext.orCreateTestableResources.apply {
          addOverride(
              R.array.desktop_windowing_app_handle_education_allowlist_apps,
              arrayOf(GMAIL_PACKAGE_NAME))
          addOverride(R.integer.desktop_windowing_education_required_time_since_setup_seconds, 0)
          addOverride(R.integer.desktop_windowing_education_min_app_launch_count, 3)
          addOverride(
              R.integer.desktop_windowing_education_app_usage_cache_interval_seconds, MAX_VALUE)
          addOverride(R.integer.desktop_windowing_education_app_launch_interval_seconds, 100)
        }
    testableContext.addMockSystemService(Context.USAGE_STATS_SERVICE, mockUsageStatsManager)
    educationFilter = AppHandleEducationFilter(testableContext, datastoreRepository)
  }

  @Test
  fun shouldShowAppHandleEducation_isTriggerValid_returnsTrue() = runTest {
    // setup() makes sure that all of the conditions satisfy and #shouldShowAppHandleEducation
    // should return true
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    assertThat(result).isTrue()
  }

  @Test
  fun shouldShowAppHandleEducation_focusAppNotInAllowlist_returnsFalse() = runTest {
    // Pass Youtube as current focus app, it is not in allowlist hence #shouldShowAppHandleEducation
    // should return false
    testableResources.addOverride(
        R.array.desktop_windowing_app_handle_education_allowlist_apps, arrayOf(GMAIL_PACKAGE_NAME))
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(YOUTUBE_PACKAGE_NAME to 4),
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    val captionState =
        createAppHandleState(createTaskInfo(runningTaskPackageName = YOUTUBE_PACKAGE_NAME))
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(captionState)

    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_timeSinceSetupIsNotSufficient_returnsFalse() = runTest {
    // Time required to have passed setup is > 100 years, hence #shouldShowAppHandleEducation should
    // return false
    testableResources.addOverride(
        R.integer.desktop_windowing_education_required_time_since_setup_seconds, MAX_VALUE)
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_educationViewedBefore_returnsFalse() = runTest {
    // Education has been viewed before, hence #shouldShowAppHandleEducation should return false
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            educationViewedTimestampMillis = 123L,
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_featureUsedBefore_returnsFalse() = runTest {
    // Feature has been used before, hence #shouldShowAppHandleEducation should return false
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            featureUsedTimestampMillis = 123L,
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_doesNotHaveMinAppUsage_returnsFalse() = runTest {
    // Simulate that gmail app has been launched twice before, minimum app launch count is 3, hence
    // #shouldShowAppHandleEducation should return false
    testableResources.addOverride(R.integer.desktop_windowing_education_min_app_launch_count, 3)
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 2),
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_appUsageStatsStale_queryAppUsageStats() = runTest {
    // UsageStats caching interval is set to 0ms, that means caching should happen very frequently
    testableResources.addOverride(
        R.integer.desktop_windowing_education_app_usage_cache_interval_seconds, 0)
    // The DataStore currently holds a proto object where Gmail's app launch count is recorded as 4.
    // This value exceeds the minimum required count of 3.
    testableResources.addOverride(R.integer.desktop_windowing_education_min_app_launch_count, 3)
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            appUsageStatsLastUpdateTimestampMillis = 0)
    // The mocked UsageStatsManager is configured to return a launch count of 2 for Gmail.
    // This value is below the minimum required count of 3.
    `when`(mockUsageStatsManager.queryAndAggregateUsageStats(anyLong(), anyLong()))
        .thenReturn(mapOf(GMAIL_PACKAGE_NAME to UsageStats().apply { mAppLaunchCount = 2 }))
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(createAppHandleState())

    // Result should be false as queried usage stats should be considered to determine the result
    // instead of cached stats
    assertThat(result).isFalse()
  }

  @Test
  fun shouldShowAppHandleEducation_appHandleMenuExpanded_returnsFalse() = runTest {
    val windowingEducationProto =
        createWindowingEducationProto(
            appUsageStats = mapOf(GMAIL_PACKAGE_NAME to 4),
            appUsageStatsLastUpdateTimestampMillis = Long.MAX_VALUE)
    // Simulate app handle menu is expanded
    val captionState = createAppHandleState(isHandleMenuExpanded = true)
    `when`(datastoreRepository.windowingEducationProto()).thenReturn(windowingEducationProto)

    val result = educationFilter.shouldShowAppHandleEducation(captionState)

    // We should not show app handle education if app menu is expanded
    assertThat(result).isFalse()
  }
}
