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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoAddableSettingListTest : SysuiTestCase() {

    private val factory =
        object : AutoAddableSetting.Factory {
            override fun create(setting: String, spec: TileSpec): AutoAddableSetting {
                return AutoAddableSetting(
                    mock(),
                    mock(),
                    setting,
                    spec,
                )
            }
        }

    @Test
    fun correctLines_correctAutoAddables() {
        val setting1 = "setting1"
        val setting2 = "setting2"
        val spec1 = TileSpec.create("spec1")
        val spec2 = TileSpec.create(ComponentName("pkg", "cls"))

        context.orCreateTestableResources.addOverride(
            R.array.config_quickSettingsAutoAdd,
            arrayOf(toStringLine(setting1, spec1), toStringLine(setting2, spec2))
        )

        val autoAddables = AutoAddableSettingList.parseSettingsResource(context.resources, factory)

        assertThat(autoAddables)
            .containsExactly(factory.create(setting1, spec1), factory.create(setting2, spec2))
    }

    @Test
    fun malformedLine_ignored() {
        val setting = "setting"
        val spec = TileSpec.create("spec")

        context.orCreateTestableResources.addOverride(
            R.array.config_quickSettingsAutoAdd,
            arrayOf(toStringLine(setting, spec), "bad_line")
        )

        val autoAddables = AutoAddableSettingList.parseSettingsResource(context.resources, factory)

        assertThat(autoAddables).containsExactly(factory.create(setting, spec))
    }

    @Test
    fun invalidSpec_ignored() {
        val setting = "setting"
        val spec = TileSpec.create("spec")

        context.orCreateTestableResources.addOverride(
            R.array.config_quickSettingsAutoAdd,
            arrayOf(toStringLine(setting, spec), "invalid:")
        )

        val autoAddables = AutoAddableSettingList.parseSettingsResource(context.resources, factory)

        assertThat(autoAddables).containsExactly(factory.create(setting, spec))
    }

    companion object {
        private fun toStringLine(setting: String, spec: TileSpec) =
            "$setting$SETTINGS_SEPARATOR${spec.spec}"
        private const val SETTINGS_SEPARATOR = ":"
    }
}
