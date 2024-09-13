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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.keyguard.ui.composable.LockscreenLongPress
import com.android.systemui.keyguard.ui.composable.section.AmbientIndicationSection
import com.android.systemui.keyguard.ui.composable.section.BottomAreaSection
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.keyguard.ui.composable.section.NotificationSection
import com.android.systemui.keyguard.ui.composable.section.SettingsMenuSection
import com.android.systemui.keyguard.ui.composable.section.StatusBarSection
import com.android.systemui.keyguard.ui.composable.section.TopAreaSection
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.res.R
import java.util.Optional
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Renders the lockscreen scene when showing with the default layout (e.g. vertical phone form
 * factor).
 */
class DefaultBlueprint
@Inject
constructor(
    private val statusBarSection: StatusBarSection,
    private val lockSection: LockSection,
    private val ambientIndicationSectionOptional: Optional<AmbientIndicationSection>,
    private val bottomAreaSection: BottomAreaSection,
    private val settingsMenuSection: SettingsMenuSection,
    private val topAreaSection: TopAreaSection,
    private val notificationSection: NotificationSection,
) : ComposableLockscreenSceneBlueprint {

    override val id: String = "default"

    @Composable
    override fun SceneScope.Content(
        viewModel: LockscreenContentViewModel,
        modifier: Modifier,
    ) {
        val isUdfpsVisible = viewModel.isUdfpsVisible
        val isShadeLayoutWide by viewModel.isShadeLayoutWide.collectAsStateWithLifecycle()
        val unfoldTranslations by viewModel.unfoldTranslations.collectAsStateWithLifecycle()
        val areNotificationsVisible by
            viewModel.areNotificationsVisible().collectAsStateWithLifecycle(initialValue = false)
        val isBypassEnabled by viewModel.isBypassEnabled.collectAsStateWithLifecycle()

        if (isBypassEnabled) {
            with(notificationSection) { HeadsUpNotifications() }
        }

        LockscreenLongPress(
            viewModel = viewModel.touchHandling,
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
                                    smartSpacePaddingTop = viewModel::getSmartSpacePaddingTop,
                                    modifier =
                                        Modifier.thenIf(isShadeLayoutWide) {
                                                Modifier.fillMaxWidth(0.5f)
                                            }
                                            .graphicsLayer {
                                                translationX = unfoldTranslations.start
                                            }
                                )
                            }
                            if (isShadeLayoutWide && !isBypassEnabled) {
                                with(notificationSection) {
                                    Notifications(
                                        areNotificationsVisible = areNotificationsVisible,
                                        isShadeLayoutWide = true,
                                        burnInParams = null,
                                        modifier =
                                            Modifier.fillMaxWidth(0.5f)
                                                .fillMaxHeight()
                                                .align(alignment = Alignment.TopEnd)
                                    )
                                }
                            }
                        }

                        val aodIconPadding: Dp =
                            dimensionResource(R.dimen.below_clock_padding_start_icons)

                        with(notificationSection) {
                            if (!isShadeLayoutWide && !isBypassEnabled) {
                                Box(modifier = Modifier.weight(weight = 1f)) {
                                    AodNotificationIcons(
                                        modifier =
                                            Modifier.align(alignment = Alignment.TopStart)
                                                .padding(start = aodIconPadding),
                                    )
                                    Notifications(
                                        areNotificationsVisible = areNotificationsVisible,
                                        isShadeLayoutWide = false,
                                        burnInParams = null,
                                    )
                                }
                            } else {
                                AodNotificationIcons(
                                    modifier = Modifier.padding(start = aodIconPadding),
                                )
                            }
                        }
                        if (!isUdfpsVisible && ambientIndicationSectionOptional.isPresent) {
                            with(ambientIndicationSectionOptional.get()) {
                                AmbientIndication(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    with(lockSection) { LockIcon() }

                    // Aligned to bottom and constrained to below the lock icon.
                    Column(modifier = Modifier.fillMaxWidth().sysuiResTag("keyguard_bottom_area")) {
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
                    with(bottomAreaSection) {
                        Shortcut(
                            isStart = true,
                            applyPadding = true,
                            modifier =
                                Modifier.graphicsLayer { translationX = unfoldTranslations.start },
                        )
                        Shortcut(
                            isStart = false,
                            applyPadding = true,
                            modifier =
                                Modifier.graphicsLayer { translationX = unfoldTranslations.end },
                        )
                    }
                    with(settingsMenuSection) { SettingsMenu(onSettingsMenuPlaced) }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                check(measurables.size == 6) { "Expected 6 measurables, got: ${measurables.size}" }
                val aboveLockIconMeasurable = measurables[0]
                val lockIconMeasurable = measurables[1]
                val belowLockIconMeasurable = measurables[2]
                val startShortcutMeasurable = measurables[3]
                val endShortcutMeasurable = measurables[4]
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
                val belowLockIconPlaceable =
                    belowLockIconMeasurable.measure(
                        noMinConstraints.copy(
                            maxHeight =
                                (constraints.maxHeight - lockIconBounds.bottom).coerceAtLeast(0)
                        )
                    )
                val startShortcutPleaceable = startShortcutMeasurable.measure(noMinConstraints)
                val endShortcutPleaceable = endShortcutMeasurable.measure(noMinConstraints)
                val settingsMenuPlaceable = settingsMenuMeasurable.measure(noMinConstraints)

                layout(constraints.maxWidth, constraints.maxHeight) {
                    aboveLockIconPlaceable.place(
                        x = 0,
                        y = 0,
                    )
                    lockIconPlaceable.place(
                        x = lockIconBounds.left,
                        y = lockIconBounds.top,
                    )
                    belowLockIconPlaceable.place(
                        x = 0,
                        y = constraints.maxHeight - belowLockIconPlaceable.height,
                    )
                    startShortcutPleaceable.place(
                        x = 0,
                        y = constraints.maxHeight - startShortcutPleaceable.height,
                    )
                    endShortcutPleaceable.place(
                        x = constraints.maxWidth - endShortcutPleaceable.width,
                        y = constraints.maxHeight - endShortcutPleaceable.height,
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
