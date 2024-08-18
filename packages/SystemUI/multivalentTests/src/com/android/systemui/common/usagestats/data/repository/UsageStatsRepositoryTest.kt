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

package com.android.systemui.common.usagestats.data.repository

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.usagestats.data.model.UsageStatsQuery
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel.Lifecycle
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class UsageStatsRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val fakeUsageStatsManager = FakeUsageStatsManager()

    private val usageStatsManager =
        mock<UsageStatsManager> {
            on { queryEvents(any()) } doAnswer
                { inv ->
                    val query = inv.getArgument(0) as UsageEventsQuery
                    fakeUsageStatsManager.queryEvents(query)
                }
        }

    private val underTest by lazy {
        UsageStatsRepositoryImpl(
            bgContext = kosmos.backgroundCoroutineContext,
            usageStatsManager = usageStatsManager,
        )
    }

    @Test
    fun testQueryWithBeginAndEndTime() =
        testScope.runTest {
            with(fakeUsageStatsManager) {
                // This event is outside the queried time, and therefore should
                // not be returned.
                addEvent(
                    type = UsageEvents.Event.ACTIVITY_RESUMED,
                    timestamp = 5,
                    instanceId = 1,
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
            }

            assertThat(
                    underTest.queryActivityEvents(
                        UsageStatsQuery(MAIN_USER, startTime = 10, endTime = 50),
                    ),
                )
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
            with(fakeUsageStatsManager) {
                addEvent(
                    user = MAIN_USER,
                    type = UsageEvents.Event.ACTIVITY_PAUSED,
                    timestamp = 10,
                    instanceId = 1,
                )
                addEvent(
                    user = SECONDARY_USER,
                    type = UsageEvents.Event.ACTIVITY_RESUMED,
                    timestamp = 11,
                    instanceId = 2,
                )
            }

            assertThat(
                    underTest.queryActivityEvents(
                        UsageStatsQuery(MAIN_USER, startTime = 10, endTime = 15),
                    ),
                )
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
    fun testQueryForSpecificPackages() =
        testScope.runTest {
            with(fakeUsageStatsManager) {
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
            }

            assertThat(
                    underTest.queryActivityEvents(
                        UsageStatsQuery(
                            MAIN_USER,
                            startTime = 10,
                            endTime = 10000,
                            packageNames = listOf(OTHER_PACKAGE),
                        ),
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
            with(fakeUsageStatsManager) {
                addEvent(
                    type = UsageEvents.Event.CHOOSER_ACTION,
                    timestamp = 10,
                    instanceId = 1,
                )
            }

            assertThat(
                    underTest.queryActivityEvents(
                        UsageStatsQuery(
                            MAIN_USER,
                            startTime = 1,
                            endTime = 20,
                        ),
                    ),
                )
                .isEmpty()
        }

    private class FakeUsageStatsManager() {
        private val events = mutableMapOf<Int, MutableList<UsageEvents.Event>>()

        fun queryEvents(query: UsageEventsQuery): UsageEvents {
            val results =
                events
                    .getOrDefault(query.userId, emptyList())
                    .filter { event ->
                        query.packageNames.isEmpty() ||
                            query.packageNames.contains(event.packageName)
                    }
                    .filter { event ->
                        event.timeStamp in query.beginTimeMillis until query.endTimeMillis
                    }
                    .filter { event ->
                        query.eventTypes.isEmpty() || query.eventTypes.contains(event.eventType)
                    }
            return UsageEvents(results, emptyArray())
        }

        fun addEvent(
            type: Int,
            instanceId: Int = 0,
            user: UserHandle = MAIN_USER,
            packageName: String = DEFAULT_PACKAGE,
            timestamp: Long,
        ) {
            events
                .getOrPut(user.identifier) { mutableListOf() }
                .add(
                    UsageEvents.Event(type, timestamp).apply {
                        mPackage = packageName
                        mInstanceId = instanceId
                    }
                )
        }
    }

    private companion object {
        const val DEFAULT_PACKAGE = "pkg.default"
        const val OTHER_PACKAGE = "pkg.other"
        val MAIN_USER: UserHandle = UserHandle.of(0)
        val SECONDARY_USER: UserHandle = UserHandle.of(1)
    }
}
