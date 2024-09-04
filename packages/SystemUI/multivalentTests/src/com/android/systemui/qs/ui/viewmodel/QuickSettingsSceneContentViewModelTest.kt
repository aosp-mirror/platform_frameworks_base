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

package com.android.systemui.qs.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.testing.TestableLooper.RunWithLooper
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewmodel.brightnessMirrorViewModelFactory
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.ui.viewmodel.shadeHeaderViewModelFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableSceneContainer
@DisableFlags(com.android.systemui.Flags.FLAG_DUAL_SHADE)
class QuickSettingsSceneContentViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val qsFlexiglassAdapter = FakeQSSceneAdapter({ mock() })
    private val footerActionsViewModel = mock<FooterActionsViewModel>()
    private val footerActionsViewModelFactory =
        mock<FooterActionsViewModel.Factory> {
            whenever(create(any<LifecycleOwner>())).thenReturn(footerActionsViewModel)
        }
    private val footerActionsController = mock<FooterActionsController>()

    private val sceneContainerStartable = kosmos.sceneContainerStartable
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val shadeInteractor by lazy { kosmos.shadeInteractor }

    private lateinit var underTest: QuickSettingsSceneContentViewModel

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(Flags.NEW_NETWORK_SLICE_UI, false)

        sceneContainerStartable.start()
        underTest =
            QuickSettingsSceneContentViewModel(
                brightnessMirrorViewModelFactory = kosmos.brightnessMirrorViewModelFactory,
                shadeHeaderViewModelFactory = kosmos.shadeHeaderViewModelFactory,
                qsSceneAdapter = qsFlexiglassAdapter,
                footerActionsViewModelFactory = footerActionsViewModelFactory,
                footerActionsController = footerActionsController,
                mediaCarouselInteractor = kosmos.mediaCarouselInteractor,
                shadeInteractor = shadeInteractor,
                sceneInteractor = sceneInteractor,
            )
        underTest.activateIn(testScope)
    }

    @Test
    fun gettingViewModelInitializesControllerOnlyOnce() {
        underTest.getFooterActionsViewModel(mock())
        underTest.getFooterActionsViewModel(mock())

        verify(footerActionsController, times(1)).init()
    }

    @Test
    fun addAndRemoveMedia_mediaVisibilityIsUpdated() =
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            val isMediaVisible by collectLastValue(underTest.isMediaVisible)
            val userMedia = MediaData(active = true)

            assertThat(isMediaVisible).isFalse()

            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(isMediaVisible).isTrue()

            kosmos.mediaFilterRepository.removeSelectedUserMediaEntry(userMedia.instanceId)

            assertThat(isMediaVisible).isFalse()
        }

    @Test
    fun addInactiveMedia_mediaVisibilityIsUpdated() =
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)
            val isMediaVisible by collectLastValue(underTest.isMediaVisible)
            val userMedia = MediaData(active = false)

            assertThat(isMediaVisible).isFalse()

            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(isMediaVisible).isTrue()
        }

    @Test
    fun shadeModeChange_switchToShadeScene() =
        testScope.runTest {
            val scene by collectLastValue(sceneInteractor.currentScene)

            // switch to split shade
            kosmos.shadeRepository.setShadeLayoutWide(true)
            runCurrent()

            assertThat(scene).isEqualTo(Scenes.Shade)
        }
}
