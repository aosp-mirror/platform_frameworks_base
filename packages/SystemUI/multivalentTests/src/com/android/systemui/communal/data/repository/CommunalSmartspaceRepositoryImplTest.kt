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

package com.android.systemui.communal.data.repository

import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.flags.Flags.FLAG_REMOTE_VIEWS
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_TIMER_FLICKER_FIX
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.smartspace.CommunalSmartspaceController
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalSmartspaceRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val listenerCaptor = argumentCaptor<SmartspaceTargetListener>()

    private val smartspaceController = mock<CommunalSmartspaceController>()
    private val fakeExecutor = kosmos.fakeExecutor
    private val systemClock = kosmos.fakeSystemClock

    private lateinit var underTest: CommunalSmartspaceRepositoryImpl

    @Before
    fun setUp() {
        underTest =
            CommunalSmartspaceRepositoryImpl(
                smartspaceController,
                fakeExecutor,
                systemClock,
                logcatLogBuffer("CommunalSmartspaceRepositoryImplTest"),
            )
    }

    @DisableFlags(FLAG_REMOTE_VIEWS)
    @Test
    fun startListening_remoteViewsFlagDisabled_doNotListenForSmartspaceUpdates() =
        testScope.runTest {
            underTest.startListening()
            fakeExecutor.runAllReady()

            verify(smartspaceController, never()).addListener(any())
        }

    @EnableFlags(FLAG_REMOTE_VIEWS)
    @Test
    fun startListening_remoteViewsFlagEnabled_listenForSmartspaceUpdates() =
        testScope.runTest {
            underTest.startListening()
            fakeExecutor.runAllReady()

            // Verify listener added
            val listener = captureSmartspaceTargetListener()

            underTest.stopListening()
            fakeExecutor.runAllReady()

            // Verify listener removed
            verify(smartspaceController).removeListener(listener)
        }

    @EnableFlags(FLAG_REMOTE_VIEWS)
    @DisableFlags(FLAG_COMMUNAL_TIMER_FLICKER_FIX)
    @Test
    fun communalTimers_onlyShowTimersWithRemoteViews() =
        testScope.runTest {
            underTest.startListening()

            val communalTimers by collectLastValue(underTest.timers)
            runCurrent()
            fakeExecutor.runAllReady()

            with(captureSmartspaceTargetListener()) {
                onSmartspaceTargetsUpdated(
                    listOf(
                        // Invalid. Not a timer
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("weather")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_WEATHER)
                        },
                        // Invalid. RemoteViews absent
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("timer-0-started")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                            on { remoteViews }.doReturn(null)
                            on { creationTimeMillis }.doReturn(1000)
                        },
                        // Valid
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("timer-1-started")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                            on { remoteViews }.doReturn(mock())
                            on { creationTimeMillis }.doReturn(2000)
                        },
                    )
                )
            }
            runCurrent()

            // Verify that only the valid target is listed
            assertThat(communalTimers).hasSize(1)
            assertThat(communalTimers?.first()?.smartspaceTargetId).isEqualTo("timer-1-started")
        }

    @EnableFlags(FLAG_REMOTE_VIEWS, FLAG_COMMUNAL_TIMER_FLICKER_FIX)
    @Test
    fun communalTimers_onlyShowTimersWithRemoteViews_timerFlickerFix() =
        testScope.runTest {
            underTest.startListening()

            val communalTimers by collectLastValue(underTest.timers)
            runCurrent()
            fakeExecutor.runAllReady()

            with(captureSmartspaceTargetListener()) {
                onSmartspaceTargetsUpdated(
                    listOf(
                        // Invalid. Not a timer
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("weather")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_WEATHER)
                        },
                        // Invalid. RemoteViews absent
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("timer-0-started")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                            on { remoteViews }.doReturn(null)
                            on { creationTimeMillis }.doReturn(1000)
                        },
                        // Valid
                        mock<SmartspaceTarget> {
                            on { smartspaceTargetId }.doReturn("timer-1-started")
                            on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                            on { remoteViews }.doReturn(mock())
                            on { creationTimeMillis }.doReturn(2000)
                        },
                    )
                )
            }
            runCurrent()

            // Verify that only the valid target is listed
            assertThat(communalTimers).hasSize(1)
            assertThat(communalTimers?.first()?.smartspaceTargetId).isEqualTo("timer-1")
        }

    @EnableFlags(FLAG_REMOTE_VIEWS)
    @Test
    fun communalTimers_cacheCreationTime() =
        testScope.runTest {
            underTest.startListening()

            val communalTimers by collectLastValue(underTest.timers)
            runCurrent()
            fakeExecutor.runAllReady()

            val listener = captureSmartspaceTargetListener()
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-0-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(1000)
                    },
                )
            )
            runCurrent()

            // Verify that the creation time is the current time, not the creation time passed in
            // the target, because this value can be inaccurate (due to b/318535930).
            val currentTime = systemClock.currentTimeMillis()
            assertThat(communalTimers?.get(0)?.createdTimestampMillis).isEqualTo(currentTime)
            assertThat(communalTimers?.get(0)?.createdTimestampMillis).isNotEqualTo(1000)

            // A second timer is added.
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-0-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(2000)
                    },
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-1-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(3000)
                    },
                )
            )
            runCurrent()

            // Verify that the created timestamp for the first time is consistent
            assertThat(communalTimers?.get(0)?.createdTimestampMillis).isEqualTo(currentTime)

            // Verify that the second timer has a new creation time
            assertThat(communalTimers?.get(1)?.createdTimestampMillis)
                .isEqualTo(systemClock.currentTimeMillis())
        }

    @EnableFlags(FLAG_REMOTE_VIEWS)
    @Test
    fun communalTimers_creationTimeRemovedFromCacheWhenTimerRemoved() =
        testScope.runTest {
            underTest.startListening()

            val communalTimers by collectLastValue(underTest.timers)
            runCurrent()
            fakeExecutor.runAllReady()

            // Start timer 0
            val listener = captureSmartspaceTargetListener()
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-0-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(1000)
                    },
                )
            )
            runCurrent()

            // Verify timer 0 creation time
            val expectedCreationTimeForTimer0 = systemClock.currentTimeMillis()
            assertThat(communalTimers?.first()?.createdTimestampMillis)
                .isEqualTo(expectedCreationTimeForTimer0)

            // Advance some time
            systemClock.advanceTime(1000)

            // Start timer 1
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-0-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(1000)
                    },
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-1-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(2000)
                    },
                )
            )
            runCurrent()

            // Verify timer 1 creation time is new
            val expectedCreationTimeForTimer1 = expectedCreationTimeForTimer0 + 1000
            assertThat(communalTimers?.get(1)?.createdTimestampMillis)
                .isEqualTo(expectedCreationTimeForTimer1)

            // Removed timer 0
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-1-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(2000)
                    },
                )
            )
            runCurrent()

            // Verify timer 0 removed, and timer 1 creation time is correct
            assertThat(communalTimers).hasSize(1)
            assertThat(communalTimers?.first()?.createdTimestampMillis)
                .isEqualTo(expectedCreationTimeForTimer1)

            // Advance some time
            systemClock.advanceTime(1000)

            // Start timer 0 again. Technically this is a new timer, but timers can reused stable
            // ids.
            listener.onSmartspaceTargetsUpdated(
                listOf(
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-1-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(2000)
                    },
                    mock<SmartspaceTarget> {
                        on { smartspaceTargetId }.doReturn("timer-0-started")
                        on { featureType }.doReturn(SmartspaceTarget.FEATURE_TIMER)
                        on { remoteViews }.doReturn(mock())
                        on { creationTimeMillis }.doReturn(3000)
                    },
                )
            )
            runCurrent()

            // Verify new timer added, and timer 1 creation time is still correct
            assertThat(communalTimers).hasSize(2)
            assertThat(communalTimers?.get(0)?.createdTimestampMillis)
                .isEqualTo(expectedCreationTimeForTimer1)

            // Verify creation time for the new timer is new, meaning that cache for timer 0 was
            // removed when it was removed
            assertThat(communalTimers?.get(1)?.createdTimestampMillis)
                .isEqualTo(expectedCreationTimeForTimer1 + 1000)
        }

    @Test
    fun stableId() {
        assertThat(CommunalSmartspaceRepositoryImpl.stableId("timer-0-12345-started"))
            .isEqualTo("timer-0")
        assertThat(CommunalSmartspaceRepositoryImpl.stableId("timer-1-67890-paused"))
            .isEqualTo("timer-1")
        assertThat(CommunalSmartspaceRepositoryImpl.stableId("i_am_an_unexpected_id"))
            .isEqualTo("i_am_an_unexpected_id")
    }

    private fun captureSmartspaceTargetListener(): SmartspaceTargetListener {
        verify(smartspaceController).addListener(listenerCaptor.capture())
        return listenerCaptor.firstValue
    }
}
