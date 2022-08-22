/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

// TODO(b/240619365): Update this test to use `runTest` when we update the testing library
@SmallTest
@RunWith(AndroidTestingRunner::class)
class NetworkCapabilitiesRepoTest : SysuiTestCase() {
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testOnCapabilitiesChanged_oneNewNetwork_networkStored() = runBlocking {
        // GIVEN a repo hooked up to [ConnectivityManager]
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = NetworkCapabilitiesRepo(
            connectivityManager = connectivityManager,
            scope = scope,
            logger = logger,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.dataStream.collect {
            }
        }

        val callback: NetworkCallback = withArgCaptor {
            verify(connectivityManager)
                .registerNetworkCallback(any(NetworkRequest::class.java), capture())
        }

        // WHEN a new network is added
        callback.onCapabilitiesChanged(NET_1, NET_1_CAPS)

        val currentMap = repo.dataStream.value

        // THEN it is emitted from the flow
        assertThat(currentMap[NET_1_ID]?.network).isEqualTo(NET_1)
        assertThat(currentMap[NET_1_ID]?.capabilities).isEqualTo(NET_1_CAPS)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun testOnCapabilitiesChanged_twoNewNetworks_bothStored() = runBlocking {
        // GIVEN a repo hooked up to [ConnectivityManager]
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = NetworkCapabilitiesRepo(
            connectivityManager = connectivityManager,
            scope = scope,
            logger = logger,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.dataStream.collect {
            }
        }

        val callback: NetworkCallback = withArgCaptor {
            verify(connectivityManager)
                .registerNetworkCallback(any(NetworkRequest::class.java), capture())
        }

        // WHEN two new networks are added
        callback.onCapabilitiesChanged(NET_1, NET_1_CAPS)
        callback.onCapabilitiesChanged(NET_2, NET_2_CAPS)

        val currentMap = repo.dataStream.value

        // THEN the current state of the flow reflects 2 networks
        assertThat(currentMap[NET_1_ID]?.network).isEqualTo(NET_1)
        assertThat(currentMap[NET_1_ID]?.capabilities).isEqualTo(NET_1_CAPS)
        assertThat(currentMap[NET_2_ID]?.network).isEqualTo(NET_2)
        assertThat(currentMap[NET_2_ID]?.capabilities).isEqualTo(NET_2_CAPS)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun testOnCapabilitesChanged_newCapabilitiesForExistingNetwork_areCaptured() = runBlocking {
        // GIVEN a repo hooked up to [ConnectivityManager]
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = NetworkCapabilitiesRepo(
            connectivityManager = connectivityManager,
            scope = scope,
            logger = logger,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.dataStream.collect {
            }
        }

        val callback: NetworkCallback = withArgCaptor {
            verify(connectivityManager)
                .registerNetworkCallback(any(NetworkRequest::class.java), capture())
        }

        // WHEN a network is added, and then its capabilities are changed
        callback.onCapabilitiesChanged(NET_1, NET_1_CAPS)
        callback.onCapabilitiesChanged(NET_1, NET_2_CAPS)

        val currentMap = repo.dataStream.value

        // THEN the current state of the flow reflects the new capabilities
        assertThat(currentMap[NET_1_ID]?.capabilities).isEqualTo(NET_2_CAPS)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun testOnLost_networkIsRemoved() = runBlocking {
        // GIVEN a repo hooked up to [ConnectivityManager]
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = NetworkCapabilitiesRepo(
            connectivityManager = connectivityManager,
            scope = scope,
            logger = logger,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.dataStream.collect {
            }
        }

        val callback: NetworkCallback = withArgCaptor {
            verify(connectivityManager)
                .registerNetworkCallback(any(NetworkRequest::class.java), capture())
        }

        // WHEN two new networks are added, and one is removed
        callback.onCapabilitiesChanged(NET_1, NET_1_CAPS)
        callback.onCapabilitiesChanged(NET_2, NET_2_CAPS)
        callback.onLost(NET_1)

        val currentMap = repo.dataStream.value

        // THEN the current state of the flow reflects only the remaining network
        assertThat(currentMap[NET_1_ID]).isNull()
        assertThat(currentMap[NET_2_ID]?.network).isEqualTo(NET_2)
        assertThat(currentMap[NET_2_ID]?.capabilities).isEqualTo(NET_2_CAPS)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun testOnLost_noNetworks_doesNotCrash() = runBlocking {
        // GIVEN a repo hooked up to [ConnectivityManager]
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = NetworkCapabilitiesRepo(
            connectivityManager = connectivityManager,
            scope = scope,
            logger = logger,
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.dataStream.collect {
            }
        }

        val callback: NetworkCallback = withArgCaptor {
            verify(connectivityManager)
                .registerNetworkCallback(any(NetworkRequest::class.java), capture())
        }

        // WHEN no networks are added, and one is removed
        callback.onLost(NET_1)

        val currentMap = repo.dataStream.value

        // THEN the current state of the flow shows no networks
        assertThat(currentMap).isEmpty()

        job.cancel()
        scope.cancel()
    }

    private val NET_1_ID = 100
    private val NET_1 = mock<Network>().also {
        whenever(it.getNetId()).thenReturn(NET_1_ID)
    }
    private val NET_2_ID = 200
    private val NET_2 = mock<Network>().also {
        whenever(it.getNetId()).thenReturn(NET_2_ID)
    }

    private val NET_1_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_CELLULAR)
        .addCapability(NET_CAPABILITY_VALIDATED)
        .build()

    private val NET_2_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_NOT_METERED)
        .addCapability(NET_CAPABILITY_VALIDATED)
        .build()
}
