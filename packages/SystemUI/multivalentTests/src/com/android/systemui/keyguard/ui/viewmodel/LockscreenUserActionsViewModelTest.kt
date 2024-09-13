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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.UserActionResult.ShowOverlay
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.Parameter
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
@EnableSceneContainer
class LockscreenUserActionsViewModelTest : SysuiTestCase() {

    companion object {
        private const val parameterCount = 6

        @Parameters(
            name =
                "canSwipeToEnter={0}, downWithTwoPointers={1}, downFromEdge={2}," +
                    " isSingleShade={3}, isCommunalAvailable={4}, isShadeTouchable={5}"
        )
        @JvmStatic
        fun combinations() = buildList {
            repeat(2f.pow(parameterCount).toInt()) { combination ->
                add(
                    arrayOf(
                            /* canSwipeToEnter= */ combination and 1 != 0,
                            /* downWithTwoPointers= */ combination and 2 != 0,
                            /* downFromEdge= */ combination and 4 != 0,
                            /* isSingleShade= */ combination and 8 != 0,
                            /* isCommunalAvailable= */ combination and 16 != 0,
                            /* isShadeTouchable= */ combination and 32 != 0,
                        )
                        .also { check(it.size == parameterCount) }
                )
            }
        }

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val combinationStrings =
                combinations().map { array ->
                    check(array.size == parameterCount)
                    buildString {
                        ((parameterCount - 1) downTo 0).forEach { index ->
                            append("${array[index]}")
                            if (index > 0) {
                                append(",")
                            }
                        }
                    }
                }
            val uniqueCombinations = combinationStrings.toSet()
            assertThat(combinationStrings).hasSize(uniqueCombinations.size)
        }

        private fun expectedDownDestination(
            downFromEdge: Boolean,
            isNarrowScreen: Boolean,
            isShadeTouchable: Boolean,
        ): SceneKey? {
            return when {
                !isShadeTouchable -> null
                downFromEdge && isNarrowScreen -> Scenes.QuickSettings
                else -> Scenes.Shade
            }
        }

        private fun expectedDownTransitionKey(
            isSingleShade: Boolean,
            isShadeTouchable: Boolean,
        ): TransitionKey? {
            return when {
                !isShadeTouchable -> null
                !isSingleShade -> TransitionKeys.ToSplitShade
                else -> null
            }
        }

        private fun expectedUpDestination(
            canSwipeToEnter: Boolean,
            isShadeTouchable: Boolean,
        ): SceneKey? {
            return when {
                !isShadeTouchable -> null
                canSwipeToEnter -> Scenes.Gone
                else -> Scenes.Bouncer
            }
        }

        private fun expectedLeftDestination(
            isCommunalAvailable: Boolean,
            isShadeTouchable: Boolean,
        ): SceneKey? {
            return when {
                !isShadeTouchable -> null
                isCommunalAvailable -> Scenes.Communal
                else -> null
            }
        }
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }

    @JvmField @Parameter(0) var canSwipeToEnter: Boolean = false
    @JvmField @Parameter(1) var downWithTwoPointers: Boolean = false
    @JvmField @Parameter(2) var downFromEdge: Boolean = false
    @JvmField @Parameter(3) var isNarrowScreen: Boolean = true
    @JvmField @Parameter(4) var isCommunalAvailable: Boolean = false
    @JvmField @Parameter(5) var isShadeTouchable: Boolean = false

    private val underTest by lazy { createLockscreenSceneViewModel() }

    @Test
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB)
    @DisableFlags(Flags.FLAG_DUAL_SHADE)
    fun userActions_fullscreenShade() =
        testScope.runTest {
            underTest.activateIn(this)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                if (canSwipeToEnter) {
                    AuthenticationMethodModel.None
                } else {
                    AuthenticationMethodModel.Pin
                }
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            kosmos.shadeRepository.setShadeLayoutWide(!isNarrowScreen)
            kosmos.setCommunalAvailable(isCommunalAvailable)
            kosmos.fakePowerRepository.updateWakefulness(
                rawState =
                    if (isShadeTouchable) {
                        WakefulnessState.AWAKE
                    } else {
                        WakefulnessState.ASLEEP
                    }
            )

            val userActions by collectLastValue(underTest.actions)
            val downDestination =
                userActions?.get(
                    Swipe(
                        SwipeDirection.Down,
                        fromSource = Edge.Top.takeIf { downFromEdge },
                        pointerCount = if (downWithTwoPointers) 2 else 1,
                    )
                ) as? UserActionResult.ChangeScene
            val downScene by
                collectLastValue(
                    downDestination?.let {
                        kosmos.sceneInteractor.resolveSceneFamily(downDestination.toScene)
                    } ?: flowOf(null)
                )
            assertThat(downScene)
                .isEqualTo(
                    expectedDownDestination(
                        downFromEdge = downFromEdge,
                        isNarrowScreen = isNarrowScreen,
                        isShadeTouchable = isShadeTouchable,
                    )
                )

            assertThat(downDestination?.transitionKey)
                .isEqualTo(
                    expectedDownTransitionKey(
                        isSingleShade = isNarrowScreen,
                        isShadeTouchable = isShadeTouchable,
                    )
                )

            val upScene by
                collectLastValue(
                    (userActions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene?.let {
                        scene ->
                        kosmos.sceneInteractor.resolveSceneFamily(scene)
                    } ?: flowOf(null)
                )

            assertThat(upScene)
                .isEqualTo(
                    expectedUpDestination(
                        canSwipeToEnter = canSwipeToEnter,
                        isShadeTouchable = isShadeTouchable,
                    )
                )

            val leftScene by
                collectLastValue(
                    (userActions?.get(Swipe.Left) as? UserActionResult.ChangeScene)?.toScene?.let {
                        scene ->
                        kosmos.sceneInteractor.resolveSceneFamily(scene)
                    } ?: flowOf(null)
                )

            assertThat(leftScene)
                .isEqualTo(
                    expectedLeftDestination(
                        isCommunalAvailable = isCommunalAvailable,
                        isShadeTouchable = isShadeTouchable,
                    )
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB, Flags.FLAG_DUAL_SHADE)
    fun userActions_dualShade() =
        testScope.runTest {
            underTest.activateIn(this)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                if (canSwipeToEnter) {
                    AuthenticationMethodModel.None
                } else {
                    AuthenticationMethodModel.Pin
                }
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            kosmos.shadeRepository.setShadeLayoutWide(!isNarrowScreen)
            kosmos.setCommunalAvailable(isCommunalAvailable)
            kosmos.fakePowerRepository.updateWakefulness(
                rawState =
                    if (isShadeTouchable) {
                        WakefulnessState.AWAKE
                    } else {
                        WakefulnessState.ASLEEP
                    }
            )

            val userActions by collectLastValue(underTest.actions)

            val downDestination =
                userActions?.get(
                    Swipe(
                        SwipeDirection.Down,
                        fromSource = Edge.Top.takeIf { downFromEdge },
                        pointerCount = if (downWithTwoPointers) 2 else 1,
                    )
                )

            if (downFromEdge || downWithTwoPointers || !isShadeTouchable) {
                // Top edge is not applicable in dual shade, as well as two-finger swipe.
                assertThat(downDestination).isNull()
            } else {
                assertThat(downDestination).isEqualTo(ShowOverlay(Overlays.NotificationsShade))
                assertThat(downDestination?.transitionKey).isNull()
            }

            val downFromTopRightDestination =
                userActions?.get(
                    Swipe(
                        SwipeDirection.Down,
                        fromSource = SceneContainerEdge.TopRight,
                        pointerCount = if (downWithTwoPointers) 2 else 1,
                    )
                )
            when {
                !isShadeTouchable -> assertThat(downFromTopRightDestination).isNull()
                downWithTwoPointers -> assertThat(downFromTopRightDestination).isNull()
                else -> {
                    assertThat(downFromTopRightDestination)
                        .isEqualTo(ShowOverlay(Overlays.QuickSettingsShade))
                    assertThat(downFromTopRightDestination?.transitionKey).isNull()
                }
            }

            val upScene by
                collectLastValue(
                    (userActions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene?.let {
                        scene ->
                        kosmos.sceneInteractor.resolveSceneFamily(scene)
                    } ?: flowOf(null)
                )

            assertThat(upScene)
                .isEqualTo(
                    expectedUpDestination(
                        canSwipeToEnter = canSwipeToEnter,
                        isShadeTouchable = isShadeTouchable,
                    )
                )

            val leftScene by
                collectLastValue(
                    (userActions?.get(Swipe.Left) as? UserActionResult.ChangeScene)?.toScene?.let {
                        scene ->
                        kosmos.sceneInteractor.resolveSceneFamily(scene)
                    } ?: flowOf(null)
                )

            assertThat(leftScene)
                .isEqualTo(
                    expectedLeftDestination(
                        isCommunalAvailable = isCommunalAvailable,
                        isShadeTouchable = isShadeTouchable,
                    )
                )
        }

    private fun createLockscreenSceneViewModel(): LockscreenUserActionsViewModel {
        return LockscreenUserActionsViewModel(
            deviceEntryInteractor = kosmos.deviceEntryInteractor,
            communalInteractor = kosmos.communalInteractor,
            shadeInteractor = kosmos.shadeInteractor,
        )
    }
}
