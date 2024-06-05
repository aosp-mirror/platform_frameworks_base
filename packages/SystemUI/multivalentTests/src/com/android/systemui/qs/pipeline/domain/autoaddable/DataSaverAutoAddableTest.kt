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

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.DataSaverTile
import com.android.systemui.statusbar.policy.DataSaverController
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
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class DataSaverAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var dataSaverController: DataSaverController
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<DataSaverController.Listener>

    private lateinit var underTest: DataSaverAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = DataSaverAutoAddable(dataSaverController)
    }

    @Test
    fun dataSaverNotEnabled_NoSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(dataSaverController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onDataSaverChanged(false)

        assertThat(signal).isNull()
    }

    @Test
    fun dataSaverEnabled_addSignal() = runTest {
        val signal by collectLastValue(underTest.autoAddSignal(0))
        runCurrent()

        verify(dataSaverController).addCallback(capture(callbackCaptor))

        callbackCaptor.value.onDataSaverChanged(true)

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
    }

    companion object {
        private val SPEC by lazy { TileSpec.create(DataSaverTile.TILE_SPEC) }
    }
}
