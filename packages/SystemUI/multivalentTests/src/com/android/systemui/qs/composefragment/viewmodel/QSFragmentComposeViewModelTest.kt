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

package com.android.systemui.qs.composefragment.viewmodel

import android.app.StatusBarManager
import android.content.testableContext
import android.graphics.Rect
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.domain.pipeline.legacyMediaDataManagerImpl
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.controller.mediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.qqsMediaHost
import com.android.systemui.media.controls.ui.view.qsMediaHost
import com.android.systemui.qs.composefragment.viewmodel.MediaState.ACTIVE_MEDIA
import com.android.systemui.qs.composefragment.viewmodel.MediaState.ANY_MEDIA
import com.android.systemui.qs.composefragment.viewmodel.MediaState.NO_MEDIA
import com.android.systemui.qs.fgsManagerController
import com.android.systemui.qs.panels.domain.interactor.tileSquishinessInteractor
import com.android.systemui.qs.panels.ui.viewmodel.setConfigurationForMediaInRow
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.largeScreenHeaderHelper
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.util.animation.DisappearParameters
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
class QSFragmentComposeViewModelTest : AbstractQSFragmentComposeViewModelTest() {

    @Test
    fun qsExpansionValueChanges_correctExpansionState() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest.setQsExpansionValue(0f)
                assertThat(underTest.expansionState.progress).isEqualTo(0f)

                underTest.setQsExpansionValue(0.3f)
                assertThat(underTest.expansionState.progress).isEqualTo(0.3f)

                underTest.setQsExpansionValue(1f)
                assertThat(underTest.expansionState.progress).isEqualTo(1f)
            }
        }

    @Test
    fun qsExpansionValueChanges_clamped() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest.setQsExpansionValue(-1f)
                assertThat(underTest.expansionState.progress).isEqualTo(0f)

                underTest.setQsExpansionValue(2f)
                assertThat(underTest.expansionState.progress).isEqualTo(1f)
            }
        }

    @Test
    fun qqsHeaderHeight_largeScreenHeader_0() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                testableContext.orCreateTestableResources.addOverride(
                    R.bool.config_use_large_screen_shade_header,
                    true,
                )
                fakeConfigurationRepository.onConfigurationChange()

                assertThat(underTest.qqsHeaderHeight).isEqualTo(0)
            }
        }

    @Test
    fun qqsHeaderHeight_noLargeScreenHeader_providedByHelper() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                testableContext.orCreateTestableResources.addOverride(
                    R.bool.config_use_large_screen_shade_header,
                    false,
                )
                fakeConfigurationRepository.onConfigurationChange()

                assertThat(underTest.qqsHeaderHeight)
                    .isEqualTo(largeScreenHeaderHelper.getLargeScreenHeaderHeight())
            }
        }

    @Test
    fun footerActionsControllerInit() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest
                runCurrent()
                assertThat(fgsManagerController.initialized).isTrue()
            }
        }

    @Test
    fun statusBarState_followsController() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                sysuiStatusBarStateController.setState(StatusBarState.SHADE)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.SHADE)

                sysuiStatusBarStateController.setState(StatusBarState.KEYGUARD)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.KEYGUARD)

                sysuiStatusBarStateController.setState(StatusBarState.SHADE_LOCKED)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.SHADE_LOCKED)
            }
        }

    @Test
    fun statusBarState_changesEarlyIfUpcomingStateIsKeyguard() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                sysuiStatusBarStateController.setState(StatusBarState.SHADE)
                sysuiStatusBarStateController.setUpcomingState(StatusBarState.SHADE_LOCKED)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.SHADE)

                sysuiStatusBarStateController.setUpcomingState(StatusBarState.KEYGUARD)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.KEYGUARD)

                sysuiStatusBarStateController.setUpcomingState(StatusBarState.SHADE)
                runCurrent()
                assertThat(underTest.statusBarState).isEqualTo(StatusBarState.KEYGUARD)
            }
        }

    @Test
    fun qsEnabled_followsRepository() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                fakeDisableFlagsRepository.disableFlags.value =
                    DisableFlagsModel(disable2 = QS_DISABLE_FLAG)
                runCurrent()

                assertThat(underTest.isQsEnabled).isFalse()

                fakeDisableFlagsRepository.disableFlags.value = DisableFlagsModel()
                runCurrent()

                assertThat(underTest.isQsEnabled).isTrue()
            }
        }

    @Test
    fun squishinessInExpansion_setInInteractor() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val squishiness by collectLastValue(tileSquishinessInteractor.squishiness)

                underTest.squishinessFraction = 0.3f
                assertThat(squishiness).isWithin(epsilon).of(0.3f.constrainSquishiness())

                underTest.squishinessFraction = 0f
                assertThat(squishiness).isWithin(epsilon).of(0f.constrainSquishiness())

                underTest.squishinessFraction = 1f
                assertThat(squishiness).isWithin(epsilon).of(1f.constrainSquishiness())
            }
        }

    @Test
    fun qqsMediaHost_initializedCorrectly() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                assertThat(underTest.qqsMediaHost.location)
                    .isEqualTo(MediaHierarchyManager.LOCATION_QQS)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)
                assertThat(underTest.qqsMediaHost.showsOnlyActiveMedia).isTrue()
                assertThat(underTest.qqsMediaHost.hostView).isNotNull()
            }
        }

    @Test
    fun qsMediaHost_initializedCorrectly() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                assertThat(underTest.qsMediaHost.location)
                    .isEqualTo(MediaHierarchyManager.LOCATION_QS)
                assertThat(underTest.qsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)
                assertThat(underTest.qsMediaHost.showsOnlyActiveMedia).isFalse()
                assertThat(underTest.qsMediaHost.hostView).isNotNull()
            }
        }

    @Test
    fun qqsMediaVisible_onlyWhenActiveMedia() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                whenever(mediaCarouselController.isLockedAndHidden()).thenReturn(false)

                assertThat(underTest.qqsMediaVisible).isEqualTo(underTest.qqsMediaHost.visible)

                setMediaState(NO_MEDIA)
                assertThat(underTest.qqsMediaVisible).isFalse()

                setMediaState(ANY_MEDIA)
                assertThat(underTest.qqsMediaVisible).isFalse()

                setMediaState(ACTIVE_MEDIA)
                assertThat(underTest.qqsMediaVisible).isTrue()
            }
        }

    @Test
    fun qsMediaVisible_onAnyMedia() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                whenever(mediaCarouselController.isLockedAndHidden()).thenReturn(false)

                assertThat(underTest.qsMediaVisible).isEqualTo(underTest.qsMediaHost.visible)

                setMediaState(NO_MEDIA)
                assertThat(underTest.qsMediaVisible).isFalse()

                setMediaState(ANY_MEDIA)
                assertThat(underTest.qsMediaVisible).isTrue()

                setMediaState(ACTIVE_MEDIA)
                assertThat(underTest.qsMediaVisible).isTrue()
            }
        }

    @Test
    fun notUsingMedia_mediaNotVisible() =
        with(kosmos) {
            testScope.testWithinLifecycle(usingMedia = false) {
                setMediaState(ACTIVE_MEDIA)

                assertThat(underTest.qqsMediaVisible).isFalse()
                assertThat(underTest.qsMediaVisible).isFalse()
            }
        }

    @Test
    fun mediaNotInRow() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setConfigurationForMediaInRow(mediaInRow = false)
                setMediaState(ACTIVE_MEDIA)

                assertThat(underTest.qqsMediaInRow).isFalse()
                assertThat(underTest.qsMediaInRow).isFalse()
            }
        }

    @Test
    fun mediaInRow_mediaActive_bothInRow() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setConfigurationForMediaInRow(mediaInRow = true)
                setMediaState(ACTIVE_MEDIA)

                assertThat(underTest.qqsMediaInRow).isTrue()
                assertThat(underTest.qsMediaInRow).isTrue()
            }
        }

    @Test
    fun mediaInRow_mediaRecommendation_onlyQSInRow() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setConfigurationForMediaInRow(mediaInRow = true)
                setMediaState(ANY_MEDIA)

                assertThat(underTest.qqsMediaInRow).isFalse()
                assertThat(underTest.qsMediaInRow).isTrue()
            }
        }

    @Test
    fun mediaInRow_correctConfig_noMediaVisible_noMediaInRow() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setConfigurationForMediaInRow(mediaInRow = true)
                setMediaState(NO_MEDIA)

                assertThat(underTest.qqsMediaInRow).isFalse()
                assertThat(underTest.qsMediaInRow).isFalse()
            }
        }

    @Test
    fun qqsMediaExpansion_collapsedMediaInLandscape() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setCollapsedMediaInLandscape(true)
                setMediaState(ACTIVE_MEDIA)

                setConfigurationForMediaInRow(mediaInRow = false)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)

                setConfigurationForMediaInRow(mediaInRow = true)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.COLLAPSED)
            }
        }

    @Test
    fun qqsMediaExpansion_notCollapsedMediaInLandscape_alwaysExpanded() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setCollapsedMediaInLandscape(false)
                setMediaState(ACTIVE_MEDIA)

                setConfigurationForMediaInRow(mediaInRow = false)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)

                setConfigurationForMediaInRow(mediaInRow = true)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)
            }
        }

    @Test
    fun qqsMediaExpansion_reactsToChangesInCollapsedMediaInLandscape() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setConfigurationForMediaInRow(mediaInRow = true)
                setMediaState(ACTIVE_MEDIA)

                setCollapsedMediaInLandscape(false)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.EXPANDED)

                setCollapsedMediaInLandscape(true)
                assertThat(underTest.qqsMediaHost.expansion).isEqualTo(MediaHostState.COLLAPSED)
            }
        }

    @Test
    fun applyQsScrollPositionForClipping() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val left = 1f
                val top = 3f
                val right = 5f
                val bottom = 7f

                underTest.applyNewQsScrollerBounds(left, top, right, bottom)

                assertThat(qsMediaHost.currentClipping)
                    .isEqualTo(Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()))
            }
        }

    @Test
    fun shouldUpdateMediaSquishiness_inSplitShadeFalse_mediaSquishinessSet() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest.isInSplitShade = false
                underTest.squishinessFraction = 0.3f

                underTest.shouldUpdateSquishinessOnMedia = true
                runCurrent()

                assertThat(underTest.qsMediaHost.squishFraction).isWithin(0.01f).of(0.3f)

                underTest.shouldUpdateSquishinessOnMedia = false
                runCurrent()
                assertThat(underTest.qsMediaHost.squishFraction).isWithin(0.01f).of(1f)
            }
        }

    @Test
    fun inSplitShade_differentStatusBarState_mediaSquishinessSet() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest.isInSplitShade = true
                underTest.squishinessFraction = 0.3f

                sysuiStatusBarStateController.setState(StatusBarState.SHADE)
                runCurrent()
                assertThat(underTest.qsMediaHost.squishFraction).isWithin(epsilon).of(0.3f)

                sysuiStatusBarStateController.setState(StatusBarState.KEYGUARD)
                runCurrent()
                assertThat(underTest.qsMediaHost.squishFraction).isWithin(epsilon).of(1f)

                sysuiStatusBarStateController.setState(StatusBarState.SHADE_LOCKED)
                runCurrent()
                assertThat(underTest.qsMediaHost.squishFraction).isWithin(epsilon).of(1f)
            }
        }

    @Test
    fun disappearParams() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                setMediaState(ACTIVE_MEDIA)

                setConfigurationForMediaInRow(false)

                assertThat(underTest.qqsMediaHost.disappearParameters)
                    .isEqualTo(disappearParamsColumn)
                assertThat(underTest.qsMediaHost.disappearParameters)
                    .isEqualTo(disappearParamsColumn)

                setConfigurationForMediaInRow(true)

                assertThat(underTest.qqsMediaHost.disappearParameters).isEqualTo(disappearParamsRow)
                assertThat(underTest.qsMediaHost.disappearParameters).isEqualTo(disappearParamsRow)
            }
        }

    @Test
    fun qsVisibleAndAnyShadeVisible() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest.isQsVisible = false
                fakeShadeRepository.setLegacyExpandedOrAwaitingInputTransfer(false)
                assertThat(underTest.isQsVisibleAndAnyShadeExpanded).isFalse()

                underTest.isQsVisible = true
                fakeShadeRepository.setLegacyExpandedOrAwaitingInputTransfer(false)
                assertThat(underTest.isQsVisibleAndAnyShadeExpanded).isFalse()

                underTest.isQsVisible = false
                fakeShadeRepository.setLegacyExpandedOrAwaitingInputTransfer(true)
                assertThat(underTest.isQsVisibleAndAnyShadeExpanded).isFalse()

                underTest.isQsVisible = true
                fakeShadeRepository.setLegacyExpandedOrAwaitingInputTransfer(true)
                assertThat(underTest.isQsVisibleAndAnyShadeExpanded).isTrue()
            }
        }

    private fun TestScope.setMediaState(state: MediaState) {
        with(kosmos) {
            val activeMedia = state == ACTIVE_MEDIA
            val anyMedia = state != NO_MEDIA
            whenever(legacyMediaDataManagerImpl.hasActiveMediaOrRecommendation())
                .thenReturn(activeMedia)
            whenever(legacyMediaDataManagerImpl.hasAnyMediaOrRecommendation()).thenReturn(anyMedia)
            qqsMediaHost.updateViewVisibility()
            qsMediaHost.updateViewVisibility()
        }
        runCurrent()
    }

    private fun TestScope.setCollapsedMediaInLandscape(collapsed: Boolean) {
        with(kosmos) {
            overrideResource(R.bool.config_quickSettingsMediaLandscapeCollapsed, collapsed)
            fakeConfigurationRepository.onAnyConfigurationChange()
        }
        runCurrent()
    }

    companion object {
        private const val QS_DISABLE_FLAG = StatusBarManager.DISABLE2_QUICK_SETTINGS

        private fun Float.constrainSquishiness(): Float {
            return (0.1f + this * 0.9f).coerceIn(0f, 1f)
        }

        private const val epsilon = 0.001f

        private val disappearParamsColumn =
            DisappearParameters().apply {
                fadeStartPosition = 0.95f
                disappearStart = 0f
                disappearEnd = 0.95f
                disappearSize.set(1f, 0f)
                gonePivot.set(0f, 0f)
                contentTranslationFraction.set(0f, 1f)
            }

        private val disappearParamsRow =
            DisappearParameters().apply {
                fadeStartPosition = 0.95f
                disappearStart = 0f
                disappearEnd = 0.6f
                disappearSize.set(0f, 0.4f)
                gonePivot.set(1f, 0f)
                contentTranslationFraction.set(0.25f, 1f)
            }
    }
}

private enum class MediaState {
    ACTIVE_MEDIA,
    ANY_MEDIA,
    NO_MEDIA,
}
