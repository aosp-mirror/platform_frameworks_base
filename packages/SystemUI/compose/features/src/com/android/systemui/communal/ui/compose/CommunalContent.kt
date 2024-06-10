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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntRect
import com.android.compose.animation.scene.SceneScope
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.communal.ui.compose.section.AmbientStatusBarSection
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.keyguard.ui.composable.blueprint.BlueprintAlignmentLines
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject

/** Renders the content of the glanceable hub. */
class CommunalContent
@Inject
constructor(
    private val viewModel: CommunalViewModel,
    private val interactionHandler: WidgetInteractionHandler,
    private val dialogFactory: SystemUIDialogFactory,
    private val lockSection: LockSection,
    private val ambientStatusBarSection: AmbientStatusBarSection,
) {
    @Composable
    fun SceneScope.Content(modifier: Modifier = Modifier) {
        Layout(
            modifier = modifier.fillMaxSize(),
            content = {
                Box(modifier = Modifier.fillMaxSize()) {
                    with(ambientStatusBarSection) {
                        AmbientStatusBar(modifier = Modifier.fillMaxWidth())
                    }
                    CommunalHub(
                        viewModel = viewModel,
                        interactionHandler = interactionHandler,
                        dialogFactory = dialogFactory,
                        modifier = Modifier.element(Communal.Elements.Grid)
                    )
                }
                with(lockSection) {
                    LockIcon(
                        overrideColor = LocalAndroidColorScheme.current.onPrimaryContainer,
                        modifier = Modifier.element(Communal.Elements.LockIcon)
                    )
                }
            }
        ) { measurables, constraints ->
            val communalGridMeasurable = measurables[0]
            val lockIconMeasurable = measurables[1]

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

            val communalGridPlaceable =
                communalGridMeasurable.measure(
                    noMinConstraints.copy(maxHeight = lockIconBounds.top)
                )

            layout(constraints.maxWidth, constraints.maxHeight) {
                communalGridPlaceable.place(
                    x = 0,
                    y = 0,
                )
                lockIconPlaceable.place(
                    x = lockIconBounds.left,
                    y = lockIconBounds.top,
                )
            }
        }
    }
}
