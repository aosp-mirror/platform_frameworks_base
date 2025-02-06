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

package com.android.systemui.communal.ui.compose.section

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformIconButton
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.ui.compose.extensions.observeTaps
import com.android.systemui.communal.ui.viewmodel.CommunalToDreamButtonViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

class CommunalToDreamButtonSection
@Inject
constructor(
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val viewModelFactory: CommunalToDreamButtonViewModel.Factory,
) {
    @Composable
    fun Button() {
        if (!communalSettingsInteractor.isV2FlagEnabled()) {
            return
        }

        val viewModel =
            rememberViewModel("CommunalToDreamButtonSection") { viewModelFactory.create() }

        if (!viewModel.shouldShowDreamButtonOnHub) {
            return
        }

        val buttonSize = dimensionResource(R.dimen.communal_to_dream_button_size)

        if (viewModel.shouldShowTooltip) {
            val tooltipVisibleState = remember { MutableTransitionState(false) }

            Column(
                modifier =
                    Modifier.widthIn(max = tooltipMaxWidth).pointerInput(Unit) {
                        observeTaps {
                            if (tooltipVisibleState.isCurrentlyVisible()) {
                                tooltipVisibleState.targetState = false
                            }
                        }
                    }
            ) {
                var waitingToShowTooltip by remember { mutableStateOf(true) }

                LaunchedEffect(tooltipVisibleState.targetState) {
                    delay(3.seconds)
                    tooltipVisibleState.targetState = true
                    waitingToShowTooltip = false
                }

                // This LaunchedEffect is used to wait for the tooltip dismiss animation to
                // complete before setting the tooltip dismissed. Otherwise, the composable would
                // be removed before the animation can start.
                LaunchedEffect(
                    tooltipVisibleState.currentState,
                    tooltipVisibleState.isIdle,
                    waitingToShowTooltip,
                ) {
                    if (
                        !waitingToShowTooltip &&
                            !tooltipVisibleState.currentState &&
                            tooltipVisibleState.isIdle
                    ) {
                        viewModel.setDreamButtonTooltipDismissed()
                    }
                }

                AnimatedVisibility(
                    visibleState = tooltipVisibleState,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    Tooltip(
                        pointerOffsetDp = buttonSize.div(2),
                        text = stringResource(R.string.glanceable_hub_to_dream_button_tooltip),
                    )
                }

                GoToDreamButton(
                    modifier = Modifier.width(buttonSize).height(buttonSize).align(Alignment.End)
                ) {
                    viewModel.onShowDreamButtonTap()
                }
            }
        } else {
            GoToDreamButton(modifier = Modifier.width(buttonSize).height(buttonSize)) {
                viewModel.onShowDreamButtonTap()
            }
        }
    }

    private fun MutableTransitionState<Boolean>.isCurrentlyVisible() = currentState && isIdle

    companion object {
        private val tooltipMaxWidth = 350.dp
    }
}

@Composable
private fun GoToDreamButton(modifier: Modifier, onClick: () -> Unit) {
    PlatformIconButton(
        modifier = modifier,
        onClick = onClick,
        iconResource = R.drawable.ic_screensaver_auto,
        contentDescription = stringResource(R.string.accessibility_glanceable_hub_to_dream_button),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    )
}

@Composable
private fun Tooltip(pointerOffsetDp: Dp, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = TooltipShape(pointerSizeDp = 12.dp, pointerOffsetDp = pointerOffsetDp),
    ) {
        Text(
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, end = 32.dp, bottom = 32.dp),
            color = MaterialTheme.colorScheme.onSurface,
            text = text,
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
}

private class TooltipShape(private val pointerSizeDp: Dp, private val pointerOffsetDp: Dp) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {

        val pointerSizePx = with(density) { pointerSizeDp.toPx() }
        val pointerOffsetPx = with(density) { pointerOffsetDp.toPx() }
        val cornerRadius = CornerRadius(CornerSize(16.dp).toPx(size, density))
        val bubbleSize = size.copy(height = size.height - pointerSizePx)

        val path =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = bubbleSize.toRect(),
                        topLeft = cornerRadius,
                        topRight = cornerRadius,
                        bottomRight = cornerRadius,
                        bottomLeft = cornerRadius,
                    )
                )
                addPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(pointerSizePx / 2f, pointerSizePx)
                        lineTo(pointerSizePx, 0f)
                        close()
                    },
                    offset =
                        Offset(
                            x = bubbleSize.width - pointerOffsetPx - pointerSizePx / 2f,
                            y = bubbleSize.height,
                        ),
                )
            }

        return Outline.Generic(path)
    }
}
