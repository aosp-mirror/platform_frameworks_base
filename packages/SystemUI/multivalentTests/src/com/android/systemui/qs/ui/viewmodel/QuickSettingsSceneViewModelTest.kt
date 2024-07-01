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

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewmodel.brightnessMirrorViewModel
import com.android.systemui.shade.ui.viewmodel.shadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
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
class QuickSettingsSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val qsFlexiglassAdapter = FakeQSSceneAdapter({ mock() })
    private val footerActionsViewModel = mock<FooterActionsViewModel>()
    private val footerActionsViewModelFactory =
        mock<FooterActionsViewModel.Factory> {
            whenever(create(any())).thenReturn(footerActionsViewModel)
        }
    private val footerActionsController = mock<FooterActionsController>()

    private val sceneInteractor = kosmos.sceneInteractor
    private val sceneBackInteractor = kosmos.sceneBackInteractor
    private val sceneContainerStartable = kosmos.sceneContainerStartable

    private lateinit var underTest: QuickSettingsSceneViewModel

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(Flags.NEW_NETWORK_SLICE_UI, false)

        sceneContainerStartable.start()
        underTest =
            QuickSettingsSceneViewModel(
                applicationScope = testScope.backgroundScope,
                brightnessMirrorViewModel = kosmos.brightnessMirrorViewModel,
                shadeHeaderViewModel = kosmos.shadeHeaderViewModel,
                qsSceneAdapter = qsFlexiglassAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
                footerActionsViewModelFactory = footerActionsViewModelFactory,
                footerActionsController = footerActionsController,
                sceneBackInteractor = sceneBackInteractor,
                mediaCarouselInteractor = kosmos.mediaCarouselInteractor,
            )
    }

    @Test
    fun destinations_whenNotCustomizing_unlocked() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val destinations by collectLastValue(underTest.destinationScenes)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            qsFlexiglassAdapter.setCustomizing(false)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                        Swipe(
                            fromSource = Edge.Bottom,
                            direction = SwipeDirection.Up,
                        ) to UserActionResult(SceneFamilies.Home)
                    )
                )
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun destinations_whenNotCustomizing_withPreviousSceneLockscreen() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            qsFlexiglassAdapter.setCustomizing(false)
            val destinations by collectLastValue(underTest.destinationScenes)

            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backScene by collectLastValue(sceneBackInteractor.backScene)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(backScene).isEqualTo(Scenes.Lockscreen)

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Lockscreen),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Lockscreen),
                        Swipe(
                            fromSource = Edge.Bottom,
                            direction = SwipeDirection.Up,
                        ) to UserActionResult(SceneFamilies.Home)
                    )
                )
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun destinations_whenNotCustomizing_withPreviousSceneLockscreen_butLockscreenDisabled() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            qsFlexiglassAdapter.setCustomizing(false)
            val destinations by collectLastValue(underTest.destinationScenes)

            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backScene by collectLastValue(sceneBackInteractor.backScene)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            sceneInteractor.changeScene(Scenes.QuickSettings, "reason")

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(backScene).isNull()
            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                        Swipe(
                            fromSource = Edge.Bottom,
                            direction = SwipeDirection.Up,
                        ) to UserActionResult(SceneFamilies.Home)
                    )
                )
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun destinations_whenNotCustomizing_authMethodSwipe_lockscreenNotDismissed() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val destinations by collectLastValue(underTest.destinationScenes)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            qsFlexiglassAdapter.setCustomizing(false)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                        Swipe(
                            fromSource = Edge.Bottom,
                            direction = SwipeDirection.Up,
                        ) to UserActionResult(SceneFamilies.Home)
                    )
                )
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun destinations_whenCustomizing_noDestinations() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(true)

            assertThat(destinations).isEmpty()
        }

    @Test
    fun destinations_whenNotCustomizing_inSplitShade_unlocked() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            val destinations by collectLastValue(underTest.destinationScenes)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            qsFlexiglassAdapter.setCustomizing(false)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

            assertThat(destinations)
                .isEqualTo(
                    mapOf(
                        Back to UserActionResult(Scenes.Shade),
                        Swipe(SwipeDirection.Up) to UserActionResult(Scenes.Shade),
                        Swipe(
                            fromSource = Edge.Bottom,
                            direction = SwipeDirection.Up,
                        ) to UserActionResult(SceneFamilies.Home)
                    )
                )
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun destinations_whenCustomizing_inSplitShade_noDestinations() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            val destinations by collectLastValue(underTest.destinationScenes)
            qsFlexiglassAdapter.setCustomizing(true)

            assertThat(destinations).isEmpty()
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
}
