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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.runCurrent
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

@SmallTest
class ShadeInteractorLegacyImplTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<ShadeInteractorLegacyImpl> {

        val configurationRepository: FakeConfigurationRepository
        val keyguardRepository: FakeKeyguardRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository
        val powerRepository: FakePowerRepository
        val sceneInteractor: SceneInteractor
        val shadeRepository: FakeShadeRepository
        val userRepository: FakeUserRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val dozeParameters: DozeParameters = mock()

    private val testComponent: TestComponent =
        DaggerShadeInteractorLegacyImplTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(Flags.FACE_AUTH_REFACTOR, false)
                        set(Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
                mocks =
                    TestMocksModule(
                        dozeParameters = dozeParameters,
                    ),
            )

    @Before
    fun setUp() {
        runBlocking {
            val userInfos =
                listOf(
                    UserInfo(
                        /* id= */ 0,
                        /* name= */ "zero",
                        /* iconPath= */ "",
                        /* flags= */ UserInfo.FLAG_PRIMARY or
                            UserInfo.FLAG_ADMIN or
                            UserInfo.FLAG_FULL,
                        UserManager.USER_TYPE_FULL_SYSTEM,
                    ),
                )
            testComponent.apply {
                userRepository.setUserInfos(userInfos)
                userRepository.setSelectedUserInfo(userInfos[0])
            }
        }
    }

    @Test
    fun fullShadeExpansionWhenShadeLocked() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            shadeRepository.setLockscreenShadeExpansion(0.5f)

            assertThat(actual).isEqualTo(1f)
        }

    @Test
    fun fullShadeExpansionWhenStatusBarStateIsNotShadeLocked() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            shadeRepository.setLockscreenShadeExpansion(0.5f)
            assertThat(actual).isEqualTo(0.5f)

            shadeRepository.setLockscreenShadeExpansion(0.8f)
            assertThat(actual).isEqualTo(0.8f)
        }

    @Test
    fun shadeExpansionWhenInSplitShadeAndQsExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN legacy shade expansion is passed through
            assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsCollapsed() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyShadeExpansion(.6f)

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(.6f)
        }

    @Test
    fun userInteractingWithShade_shadeDraggedUpAndDown() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged halfway and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(.6f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade completes expansion stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadePartiallyExpanded() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade partially expanded
            shadeRepository.setLegacyShadeExpansion(.4f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN tracking is stopped
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade goes back to collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeCollapsed() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade expanded and not tracking input
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged up halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithQs_qsDraggedUpAndDown() =
        testComponent.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithQs)
            // GIVEN qs collapsed and not tracking input
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN qs tracking starts
            shadeRepository.setLegacyQsTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged down halfway
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully expanded but tracking is not stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully collapsed but tracking is not stopped
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged halfway and tracking is stopped
            shadeRepository.setQsExpansion(.6f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs completes expansion stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }
}
