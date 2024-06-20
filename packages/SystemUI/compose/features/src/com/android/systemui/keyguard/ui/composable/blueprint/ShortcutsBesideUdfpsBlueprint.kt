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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.padding
import com.android.systemui.keyguard.ui.composable.LockscreenLongPress
import com.android.systemui.keyguard.ui.composable.section.AmbientIndicationSection
import com.android.systemui.keyguard.ui.composable.section.BottomAreaSection
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.keyguard.ui.composable.section.NotificationSection
import com.android.systemui.keyguard.ui.composable.section.SettingsMenuSection
import com.android.systemui.keyguard.ui.composable.section.StatusBarSection
import com.android.systemui.keyguard.ui.composable.section.TopAreaSection
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import java.util.Optional
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Renders the lockscreen scene when showing with the default layout (e.g. vertical phone form
 * factor).
 */
class ShortcutsBesideUdfpsBlueprint
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
    private val statusBarSection: StatusBarSection,
    private val lockSection: LockSection,
    private val ambientIndicationSectionOptional: Optional<AmbientIndicationSection>,
    private val bottomAreaSection: BottomAreaSection,
    private val settingsMenuSection: SettingsMenuSection,
    private val topAreaSection: TopAreaSection,
    private val notificationSection: NotificationSection,
) : ComposableLockscreenSceneBlueprint {

    override val id: String = "shortcuts-besides-udfps"

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        val isUdfpsVisible = viewModel.isUdfpsVisible
        val shouldUseSplitNotificationShade by
            viewModel.shouldUseSplitNotificationShade.collectAsStateWithLifecycle()
        val unfoldTranslations by viewModel.unfoldTranslations.collectAsStateWithLifecycle()

        LockscreenLongPress(
            viewModel = viewModel.longPress,
            modifier = modifier,
        ) { onSettingsMenuPlaced ->
            Layout(
                content = {
                    // Constrained to above the lock icon.
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        with(statusBarSection) {
                            StatusBar(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(
                                            horizontal = { unfoldTranslations.start.roundToInt() },
                                        )
                            )
                        }

                        Box {
                            with(topAreaSection) {
                                DefaultClockLayout(
                                    modifier =
                                        Modifier.graphicsLayer {
                                            translationX = unfoldTranslations.start
                                        },
                                )
                            }
                            if (shouldUseSplitNotificationShade) {
                                with(notificationSection) {
                                    Notifications(
                                        burnInParams = null,
                                        modifier =
                                            Modifier.fillMaxWidth(0.5f)
                                                .fillMaxHeight()
                                                .align(alignment = Alignment.TopEnd)
                                    )
                                }
                            }
                        }
                        if (!shouldUseSplitNotificationShade) {
                            with(notificationSection) {
                                Notifications(
                                    burnInParams = null,
                                    modifier = Modifier.weight(weight = 1f)
                                )
                            }
                        }
                        if (!isUdfpsVisible && ambientIndicationSectionOptional.isPresent) {
                            with(ambientIndicationSectionOptional.get()) {
                                AmbientIndication(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    // Constrained to the left of the lock icon (in left-to-right layouts).
                    with(bottomAreaSection) {
                        Shortcut(
                            isStart = true,
                            applyPadding = false,
                            modifier =
                                Modifier.graphicsLayer { translationX = unfoldTranslations.start },
                        )
                    }

                    with(lockSection) { LockIcon() }

                    // Constrained to the right of the lock icon (in left-to-right layouts).
                    with(bottomAreaSection) {
                        Shortcut(
                            isStart = false,
                            applyPadding = false,
                            modifier =
                                Modifier.graphicsLayer { translationX = unfoldTranslations.end },
                        )
                    }

                    // Aligned to bottom and constrained to below the lock icon.
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isUdfpsVisible && ambientIndicationSectionOptional.isPresent) {
                            with(ambientIndicationSectionOptional.get()) {
                                AmbientIndication(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        with(bottomAreaSection) {
                            IndicationArea(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    // Aligned to bottom and NOT constrained by the lock icon.
                    with(settingsMenuSection) { SettingsMenu(onSettingsMenuPlaced) }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                check(measurables.size == 6)
                val aboveLockIconMeasurable = measurables[0]
                val startSideShortcutMeasurable = measurables[1]
                val lockIconMeasurable = measurables[2]
                val endSideShortcutMeasurable = measurables[3]
                val belowLockIconMeasurable = measurables[4]
                val settingsMenuMeasurable = measurables[5]

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
                val startSideShortcutPlaceable =
                    startSideShortcutMeasurable.measure(noMinConstraints)
                val endSideShortcutPlaceable = endSideShortcutMeasurable.measure(noMinConstraints)
                val belowLockIconPlaceable =
                    belowLockIconMeasurable.measure(
                        noMinConstraints.copy(
                            maxHeight = constraints.maxHeight - lockIconBounds.bottom
                        )
                    )
                val settingsMenuPlaceable = settingsMenuMeasurable.measure(noMinConstraints)

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
                            lockIconBounds.right +
                                (constraints.maxWidth - lockIconBounds.right) / 2 -
                                endSideShortcutPlaceable.width / 2,
                        y = lockIconBounds.center.y - endSideShortcutPlaceable.height / 2,
                    )
                    belowLockIconPlaceable.place(
                        x = 0,
                        y = constraints.maxHeight - belowLockIconPlaceable.height,
                    )
                    settingsMenuPlaceable.place(
                        x = (constraints.maxWidth - settingsMenuPlaceable.width) / 2,
                        y = constraints.maxHeight - settingsMenuPlaceable.height,
                    )
                }
            }
        }
    }
}

@Module
interface ShortcutsBesideUdfpsBlueprintModule {
    @Binds
    @IntoSet
    fun blueprint(blueprint: ShortcutsBesideUdfpsBlueprint): ComposableLockscreenSceneBlueprint
}
