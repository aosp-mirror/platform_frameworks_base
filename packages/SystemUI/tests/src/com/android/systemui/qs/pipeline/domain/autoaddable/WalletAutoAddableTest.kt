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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.QuickAccessWalletTile
import com.android.systemui.statusbar.policy.WalletController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class WalletAutoAddableTest : SysuiTestCase() {

    @Mock private lateinit var walletController: WalletController

    private lateinit var underTest: WalletAutoAddable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = WalletAutoAddable(walletController)
    }

    @Test
    fun strategyIfNotAdded() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.IfNotAdded(SPEC))
    }

    @Test
    fun walletPositionNull_noSignal() = runTest {
        whenever(walletController.getWalletPosition()).thenReturn(null)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        assertThat(signal).isNull()
    }

    @Test
    fun walletPositionNumber_addedInThatPosition() = runTest {
        val position = 4
        whenever(walletController.getWalletPosition()).thenReturn(4)

        val signal by collectLastValue(underTest.autoAddSignal(0))

        assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC, position))
    }

    companion object {
        private val SPEC = TileSpec.create(QuickAccessWalletTile.TILE_SPEC)
    }
}
