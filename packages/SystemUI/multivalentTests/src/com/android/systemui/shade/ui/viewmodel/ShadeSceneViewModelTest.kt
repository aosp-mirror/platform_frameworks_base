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

package com.android.systemui.shade.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }

    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private val flags = FakeFeatureFlagsClassic().also { it.set(Flags.NEW_NETWORK_SLICE_UI, false) }

    private var mobileIconsViewModel: MobileIconsViewModel =
        MobileIconsViewModel(
            logger = mock(),
            verboseLogger = mock(),
            interactor = mobileIconsInteractor,
            airplaneModeInteractor =
                AirplaneModeInteractor(
                    FakeAirplaneModeRepository(),
                    FakeConnectivityRepository(),
                    FakeMobileConnectionsRepository(),
                ),
            constants = mock(),
            flags,
            scope = testScope.backgroundScope,
        )

    private val qsFlexiglassAdapter = FakeQSSceneAdapter({ mock() })

    private lateinit var shadeHeaderViewModel: ShadeHeaderViewModel

    private lateinit var underTest: ShadeSceneViewModel

    @Mock private lateinit var mediaDataManager: MediaDataManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        shadeHeaderViewModel =
            ShadeHeaderViewModel(
                applicationScope = testScope.backgroundScope,
                context = context,
                sceneInteractor = sceneInteractor,
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )

        underTest =
            ShadeSceneViewModel(
                applicationScope = testScope.backgroundScope,
                deviceEntryInteractor = deviceEntryInteractor,
                shadeHeaderViewModel = shadeHeaderViewModel,
                qsSceneAdapter = qsFlexiglassAdapter,
                notifications = kosmos.notificationsPlaceholderViewModel,
                mediaDataManager = mediaDataManager,
            )
    }

    @Test
    fun upTransitionSceneKey_deviceLocked_lockScreen() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val upTransitionSceneKey by collectLastValue(underTest.upDestinationSceneKey)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone), "reason")

            assertThat(upTransitionSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun onContentClicked_deviceUnlocked_switchesToGone() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onContentClicked_deviceLockedSecurely_switchesToBouncer() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)
            runCurrent()

            underTest.onContentClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun hasActiveMedia_mediaVisible() =
        testScope.runTest {
            whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true)

            assertThat(underTest.isMediaVisible()).isTrue()
        }

    @Test
    fun doesNotHaveActiveMedia_mediaNotVisible() =
        testScope.runTest {
            whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(false)

            assertThat(underTest.isMediaVisible()).isFalse()
        }
}
