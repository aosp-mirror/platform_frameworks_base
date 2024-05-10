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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.Utils
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.domain.interactor.UdfpsKeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.wm.shell.animation.Interpolators
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

/** Tests UDFPS lockscreen view model transitions. */
@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsLockscreenViewModelTest : SysuiTestCase() {
    private val lockscreenColorResId = android.R.attr.textColorPrimary
    private val alternateBouncerResId = com.android.internal.R.attr.materialColorOnPrimaryFixed
    private val lockscreenColor = Utils.getColorAttrDefaultColor(context, lockscreenColorResId)
    private val alternateBouncerColor =
        Utils.getColorAttrDefaultColor(context, alternateBouncerResId)

    @Mock private lateinit var dialogManager: SystemUIDialogManager

    private lateinit var underTest: UdfpsLockscreenViewModel
    private lateinit var testScope: TestScope
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository
    private lateinit var configRepository: FakeConfigurationRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var shadeRepository: FakeShadeRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        transitionRepository = FakeKeyguardTransitionRepository()
        shadeRepository = FakeShadeRepository()
        KeyguardInteractorFactory.create().also {
            keyguardInteractor = it.keyguardInteractor
            keyguardRepository = it.repository
            configRepository = it.configurationRepository
            bouncerRepository = it.bouncerRepository
        }

        val transitionInteractor =
            KeyguardTransitionInteractorFactory.create(
                    scope = testScope.backgroundScope,
                    repository = transitionRepository,
                    keyguardInteractor = keyguardInteractor,
                )
                .keyguardTransitionInteractor

        underTest =
            UdfpsLockscreenViewModel(
                context,
                lockscreenColorResId,
                alternateBouncerResId,
                transitionInteractor,
                UdfpsKeyguardInteractor(
                    configRepository,
                    BurnInInteractor(
                        context,
                        burnInHelperWrapper = mock(),
                        testScope.backgroundScope,
                        configRepository,
                        keyguardInteractor,
                    ),
                    keyguardInteractor,
                    shadeRepository,
                    dialogManager,
                ),
                keyguardInteractor,
            )
    }

    @Test
    fun goneToAodTransition() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: gone -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "goneToAodTransition",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()

            // TransitionState.RUNNING: gone -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "goneToAodTransition",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()

            // TransitionState.FINISHED: gone -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "goneToAodTransition",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()
        }

    @Test
    fun lockscreenToAod() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            // TransitionState.STARTED: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isIn(Range.closed(.39f, .41f))
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isFalse()
        }

    @Test
    fun lockscreenShadeLockedToAod() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)

            // TransitionState.STARTED: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()

            // TransitionState.RUNNING: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()

            // TransitionState.FINISHED: lockscreen -> AOD
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "lockscreenToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isFalse()
        }

    @Test
    fun aodToLockscreen() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: AOD -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "aodToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isFalse()

            // TransitionState.RUNNING: AOD -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "aodToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isIn(Range.closed(.59f, .61f))
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: AOD -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "aodToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()
        }

    @Test
    fun lockscreenToAlternateBouncer() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            // TransitionState.STARTED: lockscreen -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "lockscreenToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: lockscreen -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "lockscreenToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: lockscreen -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "lockscreenToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()
        }

    fun alternateBouncerToPrimaryBouncer() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: alternate bouncer -> primary bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "alternateBouncerToPrimaryBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: alternate bouncer -> primary bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "alternateBouncerToPrimaryBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isIn(Range.closed(.59f, .61f))
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: alternate bouncer -> primary bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "alternateBouncerToPrimaryBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isFalse()
        }

    fun alternateBouncerToAod() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: alternate bouncer -> aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.AOD,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "alternateBouncerToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: alternate bouncer -> aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.AOD,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "alternateBouncerToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isIn(Range.closed(.39f, .41f))
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: alternate bouncer -> aod
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.AOD,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "alternateBouncerToAod",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isFalse()
        }

    @Test
    fun lockscreenToOccluded() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            // TransitionState.STARTED: lockscreen -> occluded
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "lockscreenToOccluded",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: lockscreen -> occluded
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "lockscreenToOccluded",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isIn(Range.closed(.39f, .41f))
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: lockscreen -> occluded
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "lockscreenToOccluded",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isFalse()
        }

    @Test
    fun occludedToLockscreen() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: occluded -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "occludedToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: occluded -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.LOCKSCREEN,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "occludedToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: occluded -> lockscreen
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.LOCKSCREEN,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "occludedToLockscreen",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(lockscreenColor)
            assertThat(visible).isTrue()
        }

    @Test
    fun qsProgressChange() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            givenTransitionToLockscreenFinished()

            // qsExpansion = 0f
            shadeRepository.setQsExpansion(0f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(visible).isEqualTo(true)

            // qsExpansion = .25
            shadeRepository.setQsExpansion(.2f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(.6f)
            assertThat(visible).isEqualTo(true)

            // qsExpansion = .5
            shadeRepository.setQsExpansion(.5f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isEqualTo(false)

            // qsExpansion = 1
            shadeRepository.setQsExpansion(1f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isEqualTo(false)
        }

    @Test
    fun shadeExpansionChanged() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)
            givenTransitionToLockscreenFinished()

            // shadeExpansion = 0f
            shadeRepository.setUdfpsTransitionToFullShadeProgress(0f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(visible).isEqualTo(true)

            // shadeExpansion = .2
            shadeRepository.setUdfpsTransitionToFullShadeProgress(.2f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(.8f)
            assertThat(visible).isEqualTo(true)

            // shadeExpansion = .5
            shadeRepository.setUdfpsTransitionToFullShadeProgress(.5f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(.5f)
            assertThat(visible).isEqualTo(true)

            // shadeExpansion = 1
            shadeRepository.setUdfpsTransitionToFullShadeProgress(1f)
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(0f)
            assertThat(visible).isEqualTo(false)
        }

    @Test
    fun dialogHideAffordancesRequestChanged() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            givenTransitionToLockscreenFinished()
            runCurrent()
            val captor = argumentCaptor<SystemUIDialogManager.Listener>()
            Mockito.verify(dialogManager).registerListener(captor.capture())

            captor.value.shouldHideAffordances(true)
            assertThat(transition?.alpha).isEqualTo(0f)

            captor.value.shouldHideAffordances(false)
            assertThat(transition?.alpha).isEqualTo(1f)
        }

    @Test
    fun occludedToAlternateBouncer() =
        testScope.runTest {
            val transition by collectLastValue(underTest.transition)
            val visible by collectLastValue(underTest.visible)

            // TransitionState.STARTED: occluded -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = 0f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "occludedToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(0f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.RUNNING: occluded -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = .6f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "occludedToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale)
                .isEqualTo(Interpolators.FAST_OUT_SLOW_IN.getInterpolation(.6f))
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()

            // TransitionState.FINISHED: occluded -> alternate bouncer
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OCCLUDED,
                    to = KeyguardState.ALTERNATE_BOUNCER,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "occludedToAlternateBouncer",
                )
            )
            runCurrent()
            assertThat(transition?.alpha).isEqualTo(1f)
            assertThat(transition?.scale).isEqualTo(1f)
            assertThat(transition?.color).isEqualTo(alternateBouncerColor)
            assertThat(visible).isTrue()
        }

    private suspend fun givenTransitionToLockscreenFinished() {
        transitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope
        )
    }
}
