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

package com.android.systemui.qs.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.viewmodel.SceneContainerEdge
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class QuickSettingsShadeOverlayActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = kosmos.quickSettingsShadeOverlayActionsViewModel

    @Test
    fun up_hidesShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            underTest.activateIn(this)

            assertThat((actions?.get(Swipe.Up) as? UserActionResult.HideOverlay)?.overlay)
                .isEqualTo(Overlays.QuickSettingsShade)
            assertThat(actions?.get(Swipe.Down)).isNull()
        }

    @Test
    fun back_notEditing_hidesShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val isEditing by
                collectLastValue(kosmos.quickSettingsContainerViewModel.editModeViewModel.isEditing)
            underTest.activateIn(this)
            assertThat(isEditing).isFalse()

            assertThat((actions?.get(Back) as? UserActionResult.HideOverlay)?.overlay)
                .isEqualTo(Overlays.QuickSettingsShade)
        }

    @Test
    fun back_whileEditing_doesNotHideShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            underTest.activateIn(this)

            kosmos.quickSettingsContainerViewModel.editModeViewModel.startEditing()

            assertThat(actions?.get(Back)).isNull()
        }

    @Test
    fun downFromTopLeft_switchesToNotificationsShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            underTest.activateIn(this)

            assertThat(
                    (actions?.get(
                            Swipe(
                                direction = SwipeDirection.Down,
                                fromSource = SceneContainerEdge.TopLeft,
                            )
                        ) as? UserActionResult.ReplaceByOverlay)
                        ?.overlay
                )
                .isEqualTo(Overlays.NotificationsShade)
        }
}
