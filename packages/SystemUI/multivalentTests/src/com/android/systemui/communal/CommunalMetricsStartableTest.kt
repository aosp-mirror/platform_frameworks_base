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

package com.android.systemui.communal

import android.app.StatsManager
import android.app.StatsManager.StatsPullAtomCallback
import android.content.pm.UserInfo
import android.platform.test.annotations.EnableFlags
import android.util.StatsEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@EnableFlags(Flags.FLAG_COMMUNAL_HUB)
@RunWith(AndroidJUnit4::class)
class CommunalMetricsStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val metricsLogger = mock<CommunalMetricsLogger>()
    private val statsManager = mock<StatsManager>()

    private val callbackCaptor = argumentCaptor<StatsPullAtomCallback>()

    private val userTracker = kosmos.fakeUserTracker
    private val userRepository = kosmos.fakeUserRepository
    private val widgetsRepository = kosmos.fakeCommunalWidgetRepository

    private lateinit var underTest: CommunalMetricsStartable

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)

        // Set up an existing user, which is required for widgets to show
        val userInfos = listOf(UserInfo(0, "main", UserInfo.FLAG_MAIN))
        userRepository.setUserInfos(userInfos)
        userTracker.set(
            userInfos = userInfos,
            selectedUserIndex = 0,
        )

        underTest =
            CommunalMetricsStartable(
                kosmos.fakeExecutor,
                kosmos.communalSettingsInteractor,
                kosmos.communalInteractor,
                statsManager,
                metricsLogger,
            )
    }

    @Test
    fun start_communalFlagDisabled_doNotSetPullAtomCallback() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, false)

        underTest.start()

        verify(statsManager, never()).setPullAtomCallback(anyInt(), anyOrNull(), any(), any())
    }

    @Test
    fun onPullAtom_atomTagDoesNotMatch_pullSkip() {
        underTest.start()

        verify(statsManager)
            .setPullAtomCallback(anyInt(), anyOrNull(), any(), callbackCaptor.capture())
        val callback = callbackCaptor.firstValue

        // Atom tag doesn't match COMMUNAL_HUB_SNAPSHOT
        val result =
            callback.onPullAtom(SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED, mutableListOf())

        assertThat(result).isEqualTo(StatsManager.PULL_SKIP)
    }

    @Test
    fun onPullAtom_atomTagMatches_pullSuccess() =
        testScope.runTest {
            underTest.start()

            verify(statsManager)
                .setPullAtomCallback(anyInt(), anyOrNull(), any(), callbackCaptor.capture())
            val callback = callbackCaptor.firstValue

            // Populate some widgets
            widgetsRepository.addWidget(appWidgetId = 1, componentName = "pkg_1/cls_1")
            widgetsRepository.addWidget(appWidgetId = 2, componentName = "pkg_2/cls_2")

            val statsEvents = mutableListOf<StatsEvent>()
            val result = callback.onPullAtom(SysUiStatsLog.COMMUNAL_HUB_SNAPSHOT, statsEvents)

            verify(metricsLogger)
                .logWidgetsSnapshot(statsEvents, listOf("pkg_1/cls_1", "pkg_2/cls_2"))

            assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS)
        }
}
