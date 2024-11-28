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
 *
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.view.View
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR
import com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.pulseExpansionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableFlags(FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT, FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
class KeyguardRootViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val communalRepository by lazy { kosmos.communalSceneRepository }
    private val screenOffAnimationController by lazy { kosmos.screenOffAnimationController }
    private val deviceEntryRepository by lazy { kosmos.fakeDeviceEntryRepository }
    private val pulseExpansionInteractor by lazy { kosmos.pulseExpansionInteractor }
    private val notificationsKeyguardInteractor by lazy { kosmos.notificationsKeyguardInteractor }
    private val dozeParameters by lazy { kosmos.dozeParameters }
    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }
    private val underTest by lazy { kosmos.keyguardRootViewModel }

    private val viewState = ViewStateAccessor()

    private val transitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        kosmos.sceneContainerRepository.setTransitionState(transitionState)

        // Add sample notif so that the notif shelf has something to display
        kosmos.activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply {
                    addIndividualNotif(
                        activeNotificationModel(
                            key = "notif",
                            aodIcon = mock(),
                            groupKey = "testGroup",
                        )
                    )
                }
                .build()
    }

    @Test
    fun defaultBurnInScaleEqualsOne() =
        testScope.runTest {
            val burnInScale by collectLastValue(underTest.scale)
            assertThat(burnInScale!!.scale).isEqualTo(1f)
        }

    @Test
    fun burnInLayerVisibility() =
        testScope.runTest {
            val burnInLayerVisibility by collectLastValue(underTest.burnInLayerVisibility)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                ),
                validateStep = false,
            )
            assertThat(burnInLayerVisibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun iconContainer_isNotVisible_notOnKeyguard_dontShowAodIconsWhenShade() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OFF,
                to = KeyguardState.GONE,
                testScope,
            )
            whenever(screenOffAnimationController.shouldShowAodIconsWhenShade()).thenReturn(false)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isNotVisible_onKeyguard_dontShowWhenGoneToAodTransitionRunning() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.AOD,
                testScope,
            )
            whenever(screenOffAnimationController.shouldShowAodIconsWhenShade()).thenReturn(false)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isNotVisible_pulseExpanding_notBypassing() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            pulseExpansionInteractor.setPulseExpanding(true)
            deviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(isVisible?.value).isEqualTo(false)
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassEnabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(true)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_aodDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope,
            )
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled_displayNeedsBlanking() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope,
            )
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(true)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isFalse()
        }

    @Test
    fun iconContainer_isVisible_notifsFullyHidden_bypassDisabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DOZING,
                testScope,
            )
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun iconContainer_isNotVisible_notifsFullyHiddenThenVisible_bypassEnabled() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(true)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.value).isTrue()
            assertThat(isVisible?.isAnimating).isTrue()

            notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            runCurrent()

            assertThat(isVisible?.value).isFalse()
            assertThat(isVisible?.isAnimating).isTrue()
        }

    @Test
    fun isIconContainerVisible_stopAnimation() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isNotifIconContainerVisible)
            runCurrent()
            pulseExpansionInteractor.setPulseExpanding(false)
            deviceEntryRepository.setBypassEnabled(false)
            whenever(dozeParameters.alwaysOn).thenReturn(true)
            whenever(dozeParameters.displayNeedsBlanking).thenReturn(false)
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(true)
            isVisible?.stopAnimating()
            runCurrent()

            assertThat(isVisible?.isAnimating).isEqualTo(false)
        }

    @Test
    fun topClippingBounds() =
        testScope.runTest {
            val topClippingBounds by collectLastValue(underTest.topClippingBounds)
            assertThat(topClippingBounds).isNull()

            keyguardRepository.topClippingBounds.value = 50
            assertThat(topClippingBounds).isEqualTo(50)

            keyguardRepository.topClippingBounds.value = 1000
            assertThat(topClippingBounds).isEqualTo(1000)

            // Run at least 1 transition to make sure value remains at 0
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope,
            )

            kosmos.setSceneTransition(Idle(Scenes.Gone))
            // Make sure the value hasn't changed since we're GONE
            keyguardRepository.topClippingBounds.value = 5
            assertThat(topClippingBounds).isEqualTo(1000)
        }

    @Test
    @DisableSceneContainer
    fun alpha_idleOnHub_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Default value check
            assertThat(alpha).isEqualTo(1f)

            // Hub transition state is idle with hub open.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            runCurrent()

            // Run at least 1 transition to make sure value remains at 0
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            // Alpha property remains 0 regardless.
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun alpha_transitionBetweenHubAndDream_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Default value check
            assertThat(alpha).isEqualTo(1f)

            // Start transitioning between DREAM and HUB but don't finish.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DREAMING,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
                throughTransitionState = TransitionState.STARTED,
            )

            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    @EnableSceneContainer
    fun alpha_transitionToHub_isZero_scene_container() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Communal,
                    flowOf(Scenes.Communal),
                    flowOf(0.5f),
                    false,
                    emptyFlow(),
                )

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.UNDEFINED,
                testScope,
            )

            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun alpha_transitionToHub_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope,
            )

            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_transitionFromHubToLockscreen_isOne() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Transition to the glanceable hub and back.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope,
            )
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            assertThat(alpha).isEqualTo(1.0f)
        }

    @Test
    fun alpha_emitsOnShadeExpansion() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            shadeTestUtil.setQsExpansion(0f)
            assertThat(alpha).isEqualTo(1f)

            shadeTestUtil.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun alphaFromShadeExpansion_doesNotEmitWhenTransitionRunning() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            val alpha by collectLastValue(underTest.alpha(viewState))
            shadeTestUtil.setQsExpansion(0f)
            runCurrent()
            assertThat(alpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope,
            )
            assertThat(alpha).isEqualTo(0f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.PRIMARY_BOUNCER,
                        to = KeyguardState.LOCKSCREEN,
                        transitionState = TransitionState.STARTED,
                        value = 0f,
                    ),
                    TransitionStep(
                        from = KeyguardState.PRIMARY_BOUNCER,
                        to = KeyguardState.LOCKSCREEN,
                        transitionState = TransitionState.RUNNING,
                        value = 0.8f,
                    ),
                ),
                testScope,
            )
            // Alpha should be 1f from the above transition
            assertThat(alpha).isEqualTo(1f)

            shadeTestUtil.setQsExpansion(0.5f)
            // Alpha should remain unchanged instead of fading out
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @DisableSceneContainer
    fun alphaFromShadeExpansion_doesNotEmitWhenOccludedTransitionRunning() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            val alpha by collectLastValue(underTest.alpha(viewState))
            shadeTestUtil.setQsExpansion(0f)
            runCurrent()
            assertThat(alpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.OCCLUDED,
                        transitionState = TransitionState.STARTED,
                        value = 0f,
                    ),
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.OCCLUDED,
                        transitionState = TransitionState.RUNNING,
                        value = 0.8f,
                    ),
                ),
                testScope,
            )
            // Alpha should be 0f from the above transition
            assertThat(alpha).isEqualTo(0f)

            shadeTestUtil.setQsExpansion(0.5f)
            // Alpha should remain unchanged
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun alphaFromShadeExpansion_doesNotEmitWhenLockscreenToDreamTransitionRunning() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            val alpha by collectLastValue(underTest.alpha(viewState))
            shadeTestUtil.setQsExpansion(0f)

            assertThat(alpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.DREAMING,
                        transitionState = TransitionState.STARTED,
                        value = 0f,
                    ),
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.DREAMING,
                        transitionState = TransitionState.RUNNING,
                        value = 0.1f,
                    ),
                ),
                testScope,
            )

            val alphaBeforeExpansion = alpha
            shadeTestUtil.setQsExpansion(0.5f)
            // Alpha should remain unchanged instead of being affected by expansion.
            assertThat(alpha).isEqualTo(alphaBeforeExpansion)
        }

    @Test
    fun alpha_shadeClosedOverLockscreen_isOne() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Transition to the lockscreen.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            // Open the shade.
            shadeTestUtil.setQsExpansion(1f)
            assertThat(alpha).isEqualTo(0f)

            // Close the shade, alpha returns to 1.
            shadeTestUtil.setQsExpansion(0f)
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun alpha_shadeClosedOverDream_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))

            // Transition to dreaming.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope,
            )

            // Open the shade.
            shadeTestUtil.setQsExpansion(1f)
            assertThat(alpha).isEqualTo(0f)

            // Close the shade, alpha is still 0 since we're not on the lockscreen.
            shadeTestUtil.setQsExpansion(0f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_idleOnOccluded_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))
            assertThat(alpha).isEqualTo(1f)

            // Go to OCCLUDED state
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope = testScope,
            )
            assertThat(alpha).isEqualTo(0f)

            // Try pulling down shade and ensure the value doesn't change
            shadeTestUtil.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun alpha_idleOnGone_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))
            assertThat(alpha).isEqualTo(1f)

            // Go to GONE state
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            assertThat(alpha).isEqualTo(0f)

            if (!SceneContainerFlag.isEnabled) {
                // Try pulling down shade and ensure the value doesn't change
                shadeTestUtil.setQsExpansion(0.5f)
                assertThat(alpha).isEqualTo(0f)
            }
        }

    @Test
    fun alpha_idleOnDream_isZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha(viewState))
            assertThat(alpha).isEqualTo(1f)

            // Go to GONE state
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.DREAMING,
                testScope = testScope,
            )
            assertThat(alpha).isEqualTo(0f)

            // Try pulling down shade and ensure the value doesn't change
            shadeTestUtil.setQsExpansion(0.5f)
            assertThat(alpha).isEqualTo(0f)
        }
}
