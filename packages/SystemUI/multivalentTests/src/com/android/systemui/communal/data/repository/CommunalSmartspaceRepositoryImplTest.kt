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
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.smartspace.CommunalSmartspaceController
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.testKosmos
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

    private lateinit var underTest: CommunalSmartspaceRepositoryImpl

    @Before
    fun setUp() {
        underTest = CommunalSmartspaceRepositoryImpl(smartspaceController, fakeExecutor)
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
            assertThat(communalTimers?.size).isEqualTo(1)
            assertThat(communalTimers?.first()?.smartspaceTargetId).isEqualTo("timer-1-started")
        }

    private fun captureSmartspaceTargetListener(): SmartspaceTargetListener {
        verify(smartspaceController).addListener(listenerCaptor.capture())
        return listenerCaptor.firstValue
    }
}
