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

package com.android.systemui.qs.panels.ui.viewmodel

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.pipeline.data.repository.tileSpecRepository
import com.android.systemui.qs.pipeline.domain.interactor.FakeQSTile
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TileGridViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos().apply { qsTileFactory = FakeQSFactory(::tileCreator) }
    private val underTest = with(kosmos) { tileGridViewModel }

    @Test
    fun noIconTiles() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.tileViewModels)

                tileSpecRepository.setTiles(
                    0,
                    listOf(
                        TileSpec.create("bluetooth"),
                        TileSpec.create("internet"),
                        TileSpec.create("alarm")
                    )
                )

                latest!!.forEach { Assert.assertFalse(it.iconOnly) }
            }
        }

    @Test
    fun withIconTiles() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.tileViewModels)

                tileSpecRepository.setTiles(
                    0,
                    listOf(
                        TileSpec.create("airplane"),
                        TileSpec.create("flashlight"),
                        TileSpec.create("rotation")
                    )
                )

                latest!!.forEach { Assert.assertTrue(it.iconOnly) }
            }
        }

    private fun tileCreator(spec: String): QSTile {
        return FakeQSTile(0).apply { tileSpec = spec }
    }
}
