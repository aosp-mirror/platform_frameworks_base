/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSTileConfigProviderTest : SysuiTestCase() {

    private val underTest =
        createQSTileConfigProviderImpl(
            mapOf(VALID_SPEC.spec to QSTileConfigTestBuilder.build { tileSpec = VALID_SPEC }),
        )

    @Test
    fun providerReturnsConfig() {
        assertThat(underTest.getConfig(VALID_SPEC.spec)).isNotNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsForInvalidSpec() {
        underTest.getConfig(INVALID_SPEC.spec)
    }

    @Test
    fun hasConfigReturnsTrueForValidSpec() {
        assertThat(underTest.hasConfig(VALID_SPEC.spec)).isTrue()
    }

    @Test
    fun hasConfigReturnsFalseForInvalidSpec() {
        assertThat(underTest.hasConfig(INVALID_SPEC.spec)).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun validatesSpecUponCreation() {
        createQSTileConfigProviderImpl(
            mapOf(VALID_SPEC.spec to QSTileConfigTestBuilder.build { tileSpec = INVALID_SPEC })
        )
    }

    private fun createQSTileConfigProviderImpl(
        configs: Map<String, QSTileConfig>
    ): QSTileConfigProviderImpl =
        QSTileConfigProviderImpl(
            configs,
            mock<QsEventLogger>(),
        )

    private companion object {

        val VALID_SPEC = TileSpec.create("valid_tile_spec")
        val INVALID_SPEC = TileSpec.create("invalid_tile_spec")
    }
}
