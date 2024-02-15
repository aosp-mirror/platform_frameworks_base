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

import android.platform.test.annotations.DisableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
internal class SceneContainerFlagsTest : SysuiTestCase() {

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun isNotEnabled_withoutAconfigFlags() {
        Truth.assertThat(SceneContainerFlag.isEnabled).isEqualTo(false)
        Truth.assertThat(SceneContainerFlagsImpl().isEnabled()).isEqualTo(false)
    }

    @Test
    @EnableSceneContainer
    fun isEnabled_withAconfigFlags() {
        Truth.assertThat(SceneContainerFlag.isEnabled).isEqualTo(true)
        Truth.assertThat(SceneContainerFlagsImpl().isEnabled()).isEqualTo(true)
    }
}
