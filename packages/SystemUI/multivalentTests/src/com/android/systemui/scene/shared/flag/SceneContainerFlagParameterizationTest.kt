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

package com.android.systemui.scene.shared.flag

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMPOSE_LOCKSCREEN
import com.android.systemui.Flags.FLAG_EXAMPLE_FLAG
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
internal class SceneContainerFlagParameterizationTest : SysuiTestCase() {

    @Test
    fun emptyAndSceneContainer() {
        val result = FlagsParameterization.allCombinationsOf().andSceneContainer()
        Truth.assertThat(result).hasSize(2)
        Truth.assertThat(result[0].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[1].mOverrides[FLAG_SCENE_CONTAINER]).isTrue()
    }

    @Test
    fun parameterizeSceneContainer() {
        val result = parameterizeSceneContainerFlag()
        Truth.assertThat(result).hasSize(2)
        Truth.assertThat(result[0].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[1].mOverrides[FLAG_SCENE_CONTAINER]).isTrue()
    }

    @Test
    fun oneUnrelatedAndSceneContainer() {
        val unrelatedFlag = FLAG_EXAMPLE_FLAG
        val result = FlagsParameterization.allCombinationsOf(unrelatedFlag).andSceneContainer()
        Truth.assertThat(result).hasSize(4)
        Truth.assertThat(result[0].mOverrides[unrelatedFlag]).isFalse()
        Truth.assertThat(result[0].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[1].mOverrides[unrelatedFlag]).isFalse()
        Truth.assertThat(result[1].mOverrides[FLAG_SCENE_CONTAINER]).isTrue()
        Truth.assertThat(result[2].mOverrides[unrelatedFlag]).isTrue()
        Truth.assertThat(result[2].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[3].mOverrides[unrelatedFlag]).isTrue()
        Truth.assertThat(result[3].mOverrides[FLAG_SCENE_CONTAINER]).isTrue()
    }

    @Test
    fun oneDependencyAndSceneContainer() {
        val dependentFlag = FLAG_COMPOSE_LOCKSCREEN
        val result = FlagsParameterization.allCombinationsOf(dependentFlag).andSceneContainer()
        Truth.assertThat(result).hasSize(3)
        Truth.assertThat(result[0].mOverrides[dependentFlag]).isFalse()
        Truth.assertThat(result[0].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[1].mOverrides[dependentFlag]).isTrue()
        Truth.assertThat(result[1].mOverrides[FLAG_SCENE_CONTAINER]).isFalse()
        Truth.assertThat(result[2].mOverrides[dependentFlag]).isTrue()
        Truth.assertThat(result[2].mOverrides[FLAG_SCENE_CONTAINER]).isTrue()
    }
}
