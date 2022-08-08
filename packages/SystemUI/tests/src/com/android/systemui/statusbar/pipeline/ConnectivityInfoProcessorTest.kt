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

package com.android.systemui.statusbar.pipeline

import android.net.NetworkCapabilities
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.wifi.data.repository.NetworkCapabilityInfo
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when` as whenever

@OptIn(InternalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class ConnectivityInfoProcessorTest : SysuiTestCase() {

    private val statusBarPipelineFlags = mock<StatusBarPipelineFlags>()

    @Before
    fun setUp() {
        whenever(statusBarPipelineFlags.isNewPipelineEnabled()).thenReturn(true)
    }

    @Test
    fun collectorInfoUpdated_processedInfoAlsoUpdated() = runBlocking {
        // GIVEN a processor hooked up to a collector
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val collector = FakeConnectivityInfoCollector()
        val processor = ConnectivityInfoProcessor(
                collector,
                context,
                scope,
                statusBarPipelineFlags,
                mock(),
        )

        var mostRecentValue: ProcessedConnectivityInfo? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            processor.processedInfoFlow.collect {
                mostRecentValue = it
            }
        }

        // WHEN the collector emits a value
        val networkCapabilityInfo = mapOf(
                10 to NetworkCapabilityInfo(mock(), NetworkCapabilities.Builder().build())
        )
        collector.emitValue(RawConnectivityInfo(networkCapabilityInfo))
        // Because our job uses [CoroutineStart.UNDISPATCHED], it executes in the same thread as
        // this test. So, our test needs to yield to let the job run.
        // Note: Once we upgrade our Kotlin coroutines testing library, we won't need this.
        yield()

        // THEN the processor receives it
        assertThat(mostRecentValue?.networkCapabilityInfo).isEqualTo(networkCapabilityInfo)

        job.cancel()
        scope.cancel()
    }
}
