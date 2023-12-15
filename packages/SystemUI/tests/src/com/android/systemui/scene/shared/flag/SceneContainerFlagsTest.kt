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

package com.android.systemui.scene.shared.flag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.flags.Flags
import com.android.systemui.flags.setFlagValue
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.media.controls.util.MediaInSceneContainerFlag
import com.google.common.truth.Truth
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
internal class SceneContainerFlagsTest : SysuiTestCase() {

    @Before
    fun setUp() {
        // TODO(b/283300105): remove this reflection setting once the hard-coded
        //  Flags.SCENE_CONTAINER_ENABLED is no longer needed.
        val field = Flags::class.java.getField("SCENE_CONTAINER_ENABLED")
        field.isAccessible = true
        field.set(null, true) // note: this does not work with multivalent tests
    }

    private fun setAconfigFlagsEnabled(enabled: Boolean) {
        listOf(
                com.android.systemui.Flags.FLAG_SCENE_CONTAINER,
                com.android.systemui.Flags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR,
                KeyguardShadeMigrationNssl.FLAG_NAME,
                MediaInSceneContainerFlag.FLAG_NAME,
            )
            .forEach { flagName -> mSetFlagsRule.setFlagValue(flagName, enabled) }
    }

    @Test
    fun isNotEnabled_withoutAconfigFlags() {
        setAconfigFlagsEnabled(false)
        Truth.assertThat(SceneContainerFlag.isEnabled).isEqualTo(false)
        Truth.assertThat(SceneContainerFlagsImpl().isEnabled()).isEqualTo(false)
    }

    @Test
    fun isEnabled_withAconfigFlags_withCompose() {
        Assume.assumeTrue(ComposeFacade.isComposeAvailable())
        setAconfigFlagsEnabled(true)
        Truth.assertThat(SceneContainerFlag.isEnabled).isEqualTo(true)
        Truth.assertThat(SceneContainerFlagsImpl().isEnabled()).isEqualTo(true)
    }

    @Test
    fun isNotEnabled_withAconfigFlags_withoutCompose() {
        Assume.assumeFalse(ComposeFacade.isComposeAvailable())
        setAconfigFlagsEnabled(true)
        Truth.assertThat(SceneContainerFlag.isEnabled).isEqualTo(false)
        Truth.assertThat(SceneContainerFlagsImpl().isEnabled()).isEqualTo(false)
    }
}
