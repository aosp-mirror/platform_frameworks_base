/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class HotspotAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var hotspotController: HotspotController
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<HotspotController.Callback>

    private lateinit var underTest: HotspotAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = HotspotAutoAddable(hotspotController)
    }

    @Test
    fun enabled_addSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(hotspotController).addCallback(capture(callbackCaptor))
        callbackCaptor.value.onHotspotChanged(/* enabled = */ true, /* numDevices = */ 5)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    @Test
    fun notEnabled_noSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(hotspotController).addCallback(capture(callbackCaptor))
        callbackCaptor.value.onHotspotChanged(/* enabled = */ false, /* numDevices = */ 0)

        assertThat(signal).isNull()
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(HotspotTile.TILE_SPEC) }
    }
}
