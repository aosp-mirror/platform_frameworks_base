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

package com.android.systemui.common.usagestats.domain.interactor

import android.annotation.CurrentTimeMillisLong
import android.app.usage.UsageEvents
import android.content.pm.UserInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.usagestats.data.repository.fakeUsageStatsRepository
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel.Lifecycle
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class UsageStatsInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val userTracker = kosmos.fakeUserTracker
    private val systemClock = kosmos.fakeSystemClock
    private val repository = kosmos.fakeUsageStatsRepository

    private val underTest = kosmos.usageStatsInteractor

    @Before
    fun setUp() {
        userTracker.set(listOf(MAIN_USER, SECONDARY_USER), 0)
    }

    @Test
    fun testQueryWithBeginAndEndTime() =
        testScope.runTest {
            // This event is outside the queried time, and therefore should
            // not be returned.
            addEvent(
                instanceId = 1,
                type = UsageEvents.Event.ACTIVITY_RESUMED,
                timestamp = 5,
            )
            addEvent(
                type = UsageEvents.Event.ACTIVITY_PAUSED,
                timestamp = 10,
                instanceId = 1,
            )
            addEvent(
                type = UsageEvents.Event.ACTIVITY_STOPPED,
                timestamp = 20,
                instanceId = 2,
            )
            // This event is outside the queried time, and therefore should
            // not be returned.
            addEvent(
                type = UsageEvents.Event.ACTIVITY_DESTROYED,
                timestamp = 50,
                instanceId = 2,
            )

            assertThat(underTest.queryActivityEvents(startTime = 10, endTime = 50))
                .containsExactly(
                    ActivityEventModel(
                        instanceId = 1,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.PAUSED,
                        timestamp = 10,
                    ),
                    ActivityEventModel(
                        instanceId = 2,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.STOPPED,
                        timestamp = 20,
                    ),
                )
        }

    @Test
    fun testQueryForDifferentUsers() =
        testScope.runTest {
            addEvent(
                user = MAIN_USER.userHandle,
                type = UsageEvents.Event.ACTIVITY_PAUSED,
                timestamp = 10,
                instanceId = 1,
            )
            addEvent(
                user = SECONDARY_USER.userHandle,
                type = UsageEvents.Event.ACTIVITY_RESUMED,
                timestamp = 11,
                instanceId = 2,
            )

            assertThat(underTest.queryActivityEvents(startTime = 10, endTime = 15))
                .containsExactly(
                    ActivityEventModel(
                        instanceId = 1,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.PAUSED,
                        timestamp = 10,
                    ),
                )
        }

    @Test
    fun testQueryWithUserSpecified() =
        testScope.runTest {
            addEvent(
                user = MAIN_USER.userHandle,
                type = UsageEvents.Event.ACTIVITY_PAUSED,
                timestamp = 10,
                instanceId = 1,
            )
            addEvent(
                user = SECONDARY_USER.userHandle,
                type = UsageEvents.Event.ACTIVITY_RESUMED,
                timestamp = 11,
                instanceId = 2,
            )

            assertThat(
                    underTest.queryActivityEvents(
                        startTime = 10,
                        endTime = 15,
                        userHandle = SECONDARY_USER.userHandle,
                    ),
                )
                .containsExactly(
                    ActivityEventModel(
                        instanceId = 2,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.RESUMED,
                        timestamp = 11,
                    ),
                )
        }

    @Test
    fun testQueryForSpecificPackages() =
        testScope.runTest {
            addEvent(
                packageName = DEFAULT_PACKAGE,
                type = UsageEvents.Event.ACTIVITY_PAUSED,
                timestamp = 10,
                instanceId = 1,
            )
            addEvent(
                packageName = OTHER_PACKAGE,
                type = UsageEvents.Event.ACTIVITY_RESUMED,
                timestamp = 11,
                instanceId = 2,
            )

            assertThat(
                    underTest.queryActivityEvents(
                        startTime = 10,
                        endTime = 10000,
                        packageNames = listOf(OTHER_PACKAGE),
                    ),
                )
                .containsExactly(
                    ActivityEventModel(
                        instanceId = 2,
                        packageName = OTHER_PACKAGE,
                        lifecycle = Lifecycle.RESUMED,
                        timestamp = 11,
                    ),
                )
        }

    @Test
    fun testNonActivityEvent() =
        testScope.runTest {
            addEvent(
                type = UsageEvents.Event.CHOOSER_ACTION,
                timestamp = 10,
                instanceId = 1,
            )

            assertThat(underTest.queryActivityEvents(startTime = 1, endTime = 20)).isEmpty()
        }

    @Test
    fun testNoEndTimeSpecified() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(30)

            addEvent(
                type = UsageEvents.Event.ACTIVITY_PAUSED,
                timestamp = 10,
                instanceId = 1,
            )
            addEvent(
                type = UsageEvents.Event.ACTIVITY_STOPPED,
                timestamp = 20,
                instanceId = 2,
            )
            // This event is outside the queried time, and therefore should
            // not be returned.
            addEvent(
                type = UsageEvents.Event.ACTIVITY_DESTROYED,
                timestamp = 50,
                instanceId = 2,
            )

            assertThat(underTest.queryActivityEvents(startTime = 1))
                .containsExactly(
                    ActivityEventModel(
                        instanceId = 1,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.PAUSED,
                        timestamp = 10,
                    ),
                    ActivityEventModel(
                        instanceId = 2,
                        packageName = DEFAULT_PACKAGE,
                        lifecycle = Lifecycle.STOPPED,
                        timestamp = 20,
                    ),
                )
        }

    private fun addEvent(
        instanceId: Int,
        user: UserHandle = MAIN_USER.userHandle,
        packageName: String = DEFAULT_PACKAGE,
        @UsageEvents.Event.EventType type: Int,
        @CurrentTimeMillisLong timestamp: Long,
    ) {
        repository.addEvent(
            instanceId = instanceId,
            user = user,
            packageName = packageName,
            type = type,
            timestamp = timestamp,
        )
    }

    private companion object {
        const val DEFAULT_PACKAGE = "pkg.default"
        const val OTHER_PACKAGE = "pkg.other"
        val MAIN_USER: UserInfo = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val SECONDARY_USER: UserInfo = UserInfo(10, "secondary", 0)
    }
}
