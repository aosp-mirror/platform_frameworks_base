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

package com.android.systemui.dreams.homecontrols.service

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dreams.homecontrols.dagger.HomeControlsRemoteServiceComponent
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsComponentInfo
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.service.ObservableServiceConnection
import com.android.systemui.util.service.PersistentConnectionManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class RemoteHomeControlsDataSourceDelegatorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val proxy = kosmos.homeControlsRemoteProxy
    private val fakeBinder = kosmos.fakeHomeControlsRemoteBinder

    private val callbackCaptor =
        argumentCaptor<ObservableServiceConnection.Callback<HomeControlsRemoteProxy>>()

    private val connectionManager =
        mock<PersistentConnectionManager<HomeControlsRemoteProxy>> {
            on { start() } doAnswer { simulateConnect() }
            on { stop() } doAnswer { simulateDisconnect() }
        }
    private val serviceComponent =
        mock<HomeControlsRemoteServiceComponent> {
            on { connectionManager } doReturn connectionManager
        }

    private val underTest by lazy { kosmos.remoteHomeControlsDataSourceDelegator }

    @Before
    fun setUp() {
        kosmos.homeControlsRemoteServiceFactory =
            mock<HomeControlsRemoteServiceComponent.Factory>().stub {
                on { create(callbackCaptor.capture()) } doReturn serviceComponent
            }
    }

    @Test
    fun testQueriesComponentInfoFromBinder() =
        testScope.runTest {
            assertThat(fakeBinder.callbacks).isEmpty()

            val componentInfo by collectLastValue(underTest.componentInfo)

            assertThat(componentInfo).isNull()
            assertThat(fakeBinder.callbacks).hasSize(1)

            fakeBinder.notifyCallbacks(TEST_COMPONENT, allowTrivialControlsOnLockscreen = true)
            assertThat(componentInfo)
                .isEqualTo(
                    HomeControlsComponentInfo(
                        TEST_COMPONENT,
                        allowTrivialControlsOnLockscreen = true,
                    )
                )
        }

    @Test
    fun testOnlyConnectToServiceOnSubscription() =
        testScope.runTest {
            verify(connectionManager, never()).start()

            val job = launch { underTest.componentInfo.collect {} }
            runCurrent()
            verify(connectionManager, times(1)).start()
            verify(connectionManager, never()).stop()

            job.cancel()
            runCurrent()
            verify(connectionManager, times(1)).start()
            verify(connectionManager, times(1)).stop()
        }

    private fun simulateConnect() {
        callbackCaptor.lastValue.onConnected(mock(), proxy)
    }

    private fun simulateDisconnect() {
        callbackCaptor.lastValue.onDisconnected(mock(), 0)
    }

    private companion object {
        val TEST_COMPONENT = ComponentName("pkg.test", "class.test")
    }
}
