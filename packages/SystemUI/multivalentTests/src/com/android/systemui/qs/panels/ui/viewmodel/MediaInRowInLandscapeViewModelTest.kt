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

import android.content.res.Configuration
import android.content.res.mainResources
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.flags.setFlagValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.media.controls.ui.controller.mediaHostStatesManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
class MediaInRowInLandscapeViewModelTest(private val testData: TestData) : SysuiTestCase() {

    private val kosmos = testKosmos().apply { usingMediaInComposeFragment = testData.usingMedia }

    private val underTest by lazy {
        kosmos.mediaInRowInLandscapeViewModelFactory.create(TESTED_MEDIA_LOCATION)
    }

    @Before
    fun setUp() {
        mSetFlagsRule.setFlagValue(DualShade.FLAG_NAME, testData.shadeMode == ShadeMode.Dual)
    }

    @Test
    fun shouldMediaShowInRow() =
        with(kosmos) {
            testScope.runTest {
                underTest.activateIn(testScope)

                shadeRepository.setShadeLayoutWide(testData.shadeMode != ShadeMode.Single)
                val config =
                    Configuration(mainResources.configuration).apply {
                        orientation = testData.orientation
                        screenLayout = testData.screenLayoutLong
                    }
                fakeConfigurationRepository.onConfigurationChange(config)
                mainResources.configuration.updateFrom(config)
                mediaHostStatesManager.updateHostState(
                    testData.mediaLocation,
                    MediaHost.MediaHostStateHolder().apply { visible = testData.mediaVisible },
                )
                runCurrent()

                assertThat(underTest.shouldMediaShowInRow).isEqualTo(testData.mediaInRowExpected)
            }
        }

    data class TestData(
        val usingMedia: Boolean,
        val shadeMode: ShadeMode,
        val orientation: Int,
        val screenLayoutLong: Int,
        val mediaVisible: Boolean,
        @MediaLocation val mediaLocation: Int,
    ) {
        val mediaInRowExpected: Boolean
            get() =
                usingMedia &&
                    shadeMode == ShadeMode.Single &&
                    orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    screenLayoutLong == Configuration.SCREENLAYOUT_LONG_YES &&
                    mediaVisible &&
                    mediaLocation == TESTED_MEDIA_LOCATION
    }

    companion object {
        private const val TESTED_MEDIA_LOCATION = LOCATION_QS

        @JvmStatic
        @Parameters(name = "{0}")
        fun data(): Collection<TestData> {
            val usingMediaValues = setOf(true, false)
            val shadeModeValues = setOf(ShadeMode.Single, ShadeMode.Split, ShadeMode.Dual)
            val orientationValues =
                setOf(Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT)
            val screenLayoutLongValues =
                setOf(Configuration.SCREENLAYOUT_LONG_YES, Configuration.SCREENLAYOUT_LONG_NO)
            val mediaVisibleValues = setOf(true, false)
            val mediaLocationsValues = setOf(LOCATION_QS, LOCATION_QQS)

            return usingMediaValues.flatMap { usingMedia ->
                shadeModeValues.flatMap { shadeMode ->
                    orientationValues.flatMap { orientation ->
                        screenLayoutLongValues.flatMap { screenLayoutLong ->
                            mediaVisibleValues.flatMap { mediaVisible ->
                                mediaLocationsValues.map { mediaLocation ->
                                    TestData(
                                        usingMedia,
                                        shadeMode,
                                        orientation,
                                        screenLayoutLong,
                                        mediaVisible,
                                        mediaLocation,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
