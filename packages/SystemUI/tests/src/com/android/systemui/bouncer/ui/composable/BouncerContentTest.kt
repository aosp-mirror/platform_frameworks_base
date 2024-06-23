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

package com.android.systemui.bouncer.ui.composable

import android.app.AlertDialog
import android.platform.test.annotations.MotionTest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.compose.theme.PlatformTheme
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout
import com.android.systemui.bouncer.ui.viewmodel.bouncerViewModel
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.motion.createSysUiComposeMotionTestRule
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.testKosmos
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.compose.ComposeFeatureCaptures.alpha
import platform.test.motion.compose.ComposeFeatureCaptures.positionInRoot
import platform.test.motion.compose.ComposeRecordingSpec
import platform.test.motion.compose.MotionControl
import platform.test.motion.compose.feature
import platform.test.motion.compose.motionTestValueOfNode
import platform.test.motion.compose.recordMotion
import platform.test.motion.compose.runTest
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.Displays.FoldableInner

@RunWith(AndroidJUnit4::class)
@LargeTest
@MotionTest
class BouncerContentTest : SysuiTestCase() {
    private val deviceSpec = DeviceEmulationSpec(FoldableInner)
    private val kosmos = testKosmos()

    @get:Rule val motionTestRule = createSysUiComposeMotionTestRule(kosmos, deviceSpec)

    private val bouncerDialogFactory =
        object : BouncerDialogFactory {
            override fun invoke(): AlertDialog {
                throw AssertionError()
            }
        }

    @Before
    fun setUp() {
        kosmos.sceneContainerStartable.start()
        kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
    }

    @Composable
    private fun BouncerContentUnderTest() {
        PlatformTheme {
            BouncerContent(
                viewModel = kosmos.bouncerViewModel,
                layout = BouncerSceneLayout.BESIDE_USER_SWITCHER,
                modifier = Modifier.fillMaxSize().testTag("BouncerContent"),
                dialogFactory = bouncerDialogFactory
            )
        }
    }

    @Test
    fun doubleClick_swapSide() =
        motionTestRule.runTest {
            val motion =
                recordMotion(
                    content = { BouncerContentUnderTest() },
                    ComposeRecordingSpec(
                        MotionControl {
                            onNode(hasTestTag("BouncerContent")).performTouchInput {
                                doubleClick(position = centerLeft)
                            }

                            awaitCondition {
                                motionTestValueOfNode(BouncerMotionTestKeys.swapAnimationEnd)
                            }
                        }
                    ) {
                        feature(hasTestTag("UserSwitcher"), positionInRoot, "userSwitcher_pos")
                        feature(hasTestTag("UserSwitcher"), alpha, "userSwitcher_alpha")
                        feature(hasTestTag("FoldAware"), positionInRoot, "foldAware_pos")
                        feature(hasTestTag("FoldAware"), alpha, "foldAware_alpha")
                    }
                )

            assertThat(motion).timeSeriesMatchesGolden()
        }
}
