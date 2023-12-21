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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntRect
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.ui.composable.section.AmbientIndicationSection
import com.android.systemui.keyguard.ui.composable.section.BottomAreaSection
import com.android.systemui.keyguard.ui.composable.section.ClockSection
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.keyguard.ui.composable.section.NotificationSection
import com.android.systemui.keyguard.ui.composable.section.SmartSpaceSection
import com.android.systemui.keyguard.ui.composable.section.StatusBarSection
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject

/**
 * Renders the lockscreen scene when showing with the default layout (e.g. vertical phone form
 * factor).
 */
class ShortcutsBesideUdfpsBlueprint
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
    private val statusBarSection: StatusBarSection,
    private val clockSection: ClockSection,
    private val smartSpaceSection: SmartSpaceSection,
    private val notificationSection: NotificationSection,
    private val lockSection: LockSection,
    private val ambientIndicationSection: AmbientIndicationSection,
    private val bottomAreaSection: BottomAreaSection,
) : LockscreenSceneBlueprint {

    override val id: String = "shortcuts-besides-udfps"

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        val isUdfpsVisible = viewModel.isUdfpsVisible

        Layout(
            content = {
                // Constrained to above the lock icon.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    with(statusBarSection) { StatusBar(modifier = Modifier.fillMaxWidth()) }
                    with(clockSection) { SmallClock(modifier = Modifier.fillMaxWidth()) }
                    with(smartSpaceSection) { SmartSpace(modifier = Modifier.fillMaxWidth()) }
                    with(clockSection) { LargeClock(modifier = Modifier.fillMaxWidth()) }
                    with(notificationSection) {
                        Notifications(modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                    if (!isUdfpsVisible) {
                        with(ambientIndicationSection) {
                            AmbientIndication(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // Constrained to the left of the lock icon (in left-to-right layouts).
                with(bottomAreaSection) { Shortcut(isStart = true, applyPadding = false) }

                with(lockSection) { LockIcon() }

                // Constrained to the right of the lock icon (in left-to-right layouts).
                with(bottomAreaSection) { Shortcut(isStart = false, applyPadding = false) }

                // Aligned to bottom and constrained to below the lock icon.
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isUdfpsVisible) {
                        with(ambientIndicationSection) {
                            AmbientIndication(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    with(bottomAreaSection) { IndicationArea(modifier = Modifier.fillMaxWidth()) }
                }
            },
            modifier = modifier,
        ) { measurables, constraints ->
            check(measurables.size == 5)
            val (
                aboveLockIconMeasurable,
                startSideShortcutMeasurable,
                lockIconMeasurable,
                endSideShortcutMeasurable,
                belowLockIconMeasurable,
            ) = measurables

            val noMinConstraints =
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                )

            val lockIconPlaceable = lockIconMeasurable.measure(noMinConstraints)
            val lockIconBounds =
                IntRect(
                    left = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Left],
                    top = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Top],
                    right = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Right],
                    bottom = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Bottom],
                )

            val aboveLockIconPlaceable =
                aboveLockIconMeasurable.measure(
                    noMinConstraints.copy(maxHeight = lockIconBounds.top)
                )
            val startSideShortcutPlaceable = startSideShortcutMeasurable.measure(noMinConstraints)
            val endSideShortcutPlaceable = endSideShortcutMeasurable.measure(noMinConstraints)
            val belowLockIconPlaceable =
                belowLockIconMeasurable.measure(
                    noMinConstraints.copy(maxHeight = constraints.maxHeight - lockIconBounds.bottom)
                )

            layout(constraints.maxWidth, constraints.maxHeight) {
                aboveLockIconPlaceable.place(
                    x = 0,
                    y = 0,
                )
                startSideShortcutPlaceable.placeRelative(
                    x = lockIconBounds.left / 2 - startSideShortcutPlaceable.width / 2,
                    y = lockIconBounds.center.y - startSideShortcutPlaceable.height / 2,
                )
                lockIconPlaceable.place(
                    x = lockIconBounds.left,
                    y = lockIconBounds.top,
                )
                endSideShortcutPlaceable.placeRelative(
                    x =
                        lockIconBounds.right + (constraints.maxWidth - lockIconBounds.right) / 2 -
                            endSideShortcutPlaceable.width / 2,
                    y = lockIconBounds.center.y - endSideShortcutPlaceable.height / 2,
                )
                belowLockIconPlaceable.place(
                    x = 0,
                    y = constraints.maxHeight - belowLockIconPlaceable.height,
                )
            }
        }
    }
}

@Module
interface ShortcutsBesideUdfpsBlueprintModule {
    @Binds
    @IntoSet
    fun blueprint(blueprint: ShortcutsBesideUdfpsBlueprint): LockscreenSceneBlueprint
}
