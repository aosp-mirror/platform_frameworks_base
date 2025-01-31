/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.shadeModeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class QuickSettingsContainerViewModelTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            usingMediaInComposeFragment = false // This is not for the compose fragment
        }
    private val testScope = kosmos.testScope

    private val shadeModeInteractor = kosmos.shadeModeInteractor

    private val underTest by lazy {
        kosmos.quickSettingsContainerViewModelFactory.create(supportsBrightnessMirroring = false)
    }

    @Before
    fun setUp() {
        kosmos.sceneContainerStartable.start()
        kosmos.enableDualShade()
        underTest.activateIn(testScope)
    }

    @Test
    fun showHeader_showsOnNarrowScreen() =
        testScope.runTest {
            kosmos.enableDualShade(wideLayout = false)
            val isShadeLayoutWide by collectLastValue(shadeModeInteractor.isShadeLayoutWide)
            assertThat(isShadeLayoutWide).isFalse()

            assertThat(underTest.showHeader).isTrue()
        }

    @Test
    fun showHeader_hidesOnWideScreen() =
        testScope.runTest {
            kosmos.enableDualShade(wideLayout = true)
            val isShadeLayoutWide by collectLastValue(shadeModeInteractor.isShadeLayoutWide)
            assertThat(isShadeLayoutWide).isTrue()

            assertThat(underTest.showHeader).isFalse()
        }
}
