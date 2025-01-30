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

package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.smartspace.SmartspaceInteractionHandler
import com.android.systemui.communal.ui.compose.section.AmbientStatusBarSection
import com.android.systemui.communal.ui.compose.section.CommunalLockSection
import com.android.systemui.communal.ui.compose.section.CommunalPopupSection
import com.android.systemui.communal.ui.compose.section.CommunalToDreamButtonSection
import com.android.systemui.communal.ui.compose.section.HubOnboardingSection
import com.android.systemui.communal.ui.view.layout.sections.CommunalAppWidgetSection
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.keyguard.ui.composable.blueprint.BlueprintAlignmentLines
import com.android.systemui.keyguard.ui.composable.section.BottomAreaSection
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders the content of the glanceable hub. */
class CommunalContent
@Inject
constructor(
    private val viewModel: CommunalViewModel,
    private val interactionHandler: SmartspaceInteractionHandler,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val dialogFactory: SystemUIDialogFactory,
    private val lockSection: LockSection,
    private val communalLockSection: CommunalLockSection,
    private val bottomAreaSection: BottomAreaSection,
    private val ambientStatusBarSection: AmbientStatusBarSection,
    private val communalPopupSection: CommunalPopupSection,
    private val widgetSection: CommunalAppWidgetSection,
    private val communalToDreamButtonSection: CommunalToDreamButtonSection,
    private val hubOnboardingSection: HubOnboardingSection,
) {

    @Composable
    fun ContentScope.Content(modifier: Modifier = Modifier) {
        CommunalTouchableSurface(viewModel = viewModel, modifier = modifier) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        with(communalPopupSection) { Popup() }
                        with(ambientStatusBarSection) {
                            AmbientStatusBar(modifier = Modifier.fillMaxWidth().zIndex(1f))
                        }
                        CommunalHub(
                            viewModel = viewModel,
                            interactionHandler = interactionHandler,
                            dialogFactory = dialogFactory,
                            widgetSection = widgetSection,
                            modifier = Modifier.element(Communal.Elements.Grid),
                            contentScope = this@Content,
                        )
                        with(hubOnboardingSection) { BottomSheet() }
                    }
                    if (communalSettingsInteractor.isV2FlagEnabled()) {
                        with(communalLockSection) {
                            LockIcon(modifier = Modifier.element(Communal.Elements.LockIcon))
                        }
                    } else {
                        with(lockSection) {
                            LockIcon(
                                overrideColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.element(Communal.Elements.LockIcon),
                            )
                        }
                    }
                    with(bottomAreaSection) {
                        IndicationArea(
                            Modifier.element(Communal.Elements.IndicationArea).fillMaxWidth()
                        )
                    }
                    with(communalToDreamButtonSection) { Button() }
                },
            ) { measurables, constraints ->
                val communalGridMeasurable = measurables[0]
                val lockIconMeasurable = measurables[1]
                val bottomAreaMeasurable = measurables[2]
                val screensaverButtonMeasurable: Measurable? = measurables.getOrNull(3)

                val noMinConstraints = constraints.copy(minWidth = 0, minHeight = 0)

                val lockIconPlaceable =
                    if (communalSettingsInteractor.isV2FlagEnabled()) {
                        val lockIconSizeInt = lockIconSize.roundToPx()
                        lockIconMeasurable.measure(
                            Constraints.fixed(width = lockIconSizeInt, height = lockIconSizeInt)
                        )
                    } else {
                        lockIconMeasurable.measure(noMinConstraints)
                    }
                val lockIconBounds =
                    if (communalSettingsInteractor.isV2FlagEnabled()) {
                        val lockIconDistanceFromBottom =
                            min(
                                (constraints.maxHeight * lockIconPercentDistanceFromBottom)
                                    .roundToInt(),
                                lockIconMinDistanceFromBottom.roundToPx(),
                            )
                        val x = constraints.maxWidth / 2 - lockIconPlaceable.width / 2
                        val y =
                            constraints.maxHeight -
                                lockIconDistanceFromBottom -
                                lockIconPlaceable.height
                        IntRect(
                            left = x,
                            top = y,
                            right = x + lockIconPlaceable.width,
                            bottom = y + lockIconPlaceable.height,
                        )
                    } else {
                        IntRect(
                            left = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Left],
                            top = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Top],
                            right = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Right],
                            bottom = lockIconPlaceable[BlueprintAlignmentLines.LockIcon.Bottom],
                        )
                    }

                val bottomAreaPlaceable = bottomAreaMeasurable.measure(noMinConstraints)

                val screensaverButtonPlaceable =
                    screensaverButtonMeasurable?.measure(noMinConstraints)

                val communalGridPlaceable =
                    communalGridMeasurable.measure(
                        noMinConstraints.copy(maxHeight = lockIconBounds.top)
                    )

                layout(constraints.maxWidth, constraints.maxHeight) {
                    communalGridPlaceable.place(x = 0, y = 0)
                    lockIconPlaceable.place(x = lockIconBounds.left, y = lockIconBounds.top)

                    val bottomAreaTop = constraints.maxHeight - bottomAreaPlaceable.height
                    bottomAreaPlaceable.place(x = 0, y = bottomAreaTop)

                    val screensaverButtonPaddingInt = screensaverButtonPadding.roundToPx()
                    screensaverButtonPlaceable?.place(
                        x =
                            constraints.maxWidth -
                                screensaverButtonPaddingInt -
                                screensaverButtonPlaceable.width,
                        y =
                            constraints.maxHeight -
                                screensaverButtonPaddingInt -
                                screensaverButtonPlaceable.height,
                    )
                }
            }
        }
    }

    companion object {
        private val screensaverButtonPadding: Dp = 24.dp

        // TODO(b/382739998): Remove these hardcoded values once lock icon size and bottom area
        // position are sorted.
        private val lockIconSize: Dp = 54.dp
        private val lockIconPercentDistanceFromBottom = 0.1f
        private val lockIconMinDistanceFromBottom = 70.dp
    }
}
