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

package com.android.systemui.volume.panel.component.mediaoutput.ui.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.toColor
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.mediaoutput.ui.viewmodel.ConnectedDeviceViewModel
import com.android.systemui.volume.panel.component.mediaoutput.ui.viewmodel.DeviceIconViewModel
import com.android.systemui.volume.panel.component.mediaoutput.ui.viewmodel.MediaOutputViewModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import javax.inject.Inject

@VolumePanelScope
class MediaOutputComponent
@Inject
constructor(
    private val viewModel: MediaOutputViewModel,
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val connectedDeviceViewModel: ConnectedDeviceViewModel? by
            viewModel.connectedDeviceViewModel.collectAsStateWithLifecycle()
        val deviceIconViewModel: DeviceIconViewModel? by
            viewModel.deviceIconViewModel.collectAsStateWithLifecycle()
        val clickLabel = stringResource(R.string.volume_panel_enter_media_output_settings)

        Expandable(
            modifier =
                Modifier.fillMaxWidth().height(80.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    this.onClick(label = clickLabel) {
                        viewModel.onBarClick(null)
                        true
                    }
                },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            onClick = { viewModel.onBarClick(it) },
        ) { _ ->
            Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                connectedDeviceViewModel?.let { ConnectedDeviceText(it) }

                deviceIconViewModel?.let { ConnectedDeviceIcon(it) }
            }
        }
    }

    @Composable
    private fun RowScope.ConnectedDeviceText(connectedDeviceViewModel: ConnectedDeviceViewModel) {
        Column(
            modifier = Modifier.weight(1f).padding(start = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.basicMarquee(),
                text = connectedDeviceViewModel.label.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            connectedDeviceViewModel.deviceName?.let {
                Text(
                    modifier = Modifier.basicMarquee(),
                    text = it.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun ConnectedDeviceIcon(deviceIconViewModel: DeviceIconViewModel) {
        val transition = updateTransition(deviceIconViewModel, label = "MediaOutputIconTransition")
        Box(
            modifier = Modifier.padding(16.dp).fillMaxHeight().aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            transition.AnimatedContent(
                contentKey = { it.backgroundColor },
                transitionSpec = {
                    if (targetState is DeviceIconViewModel.IsPlaying) {
                        scaleIn(
                            initialScale = 0.9f,
                            animationSpec = isPlayingInIconBackgroundSpec(),
                        ) + fadeIn(animationSpec = isPlayingInIconBackgroundSpec()) togetherWith
                            fadeOut(animationSpec = snap())
                    } else {
                        fadeIn(animationSpec = snap(delayMillis = 900)) togetherWith
                            scaleOut(
                                targetScale = 0.9f,
                                animationSpec = isPlayingOutSpec(),
                            ) + fadeOut(animationSpec = isPlayingOutSpec())
                    }
                }
            ) { targetViewModel ->
                Spacer(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(
                                color = targetViewModel.backgroundColor.toColor(),
                                shape = RoundedCornerShape(12.dp),
                            ),
                )
            }
            transition.AnimatedContent(
                contentKey = { it.icon },
                transitionSpec = {
                    if (targetState is DeviceIconViewModel.IsPlaying) {
                        fadeIn(animationSpec = snap(delayMillis = 700)) togetherWith
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = isPlayingInIconSpec(),
                            ) + fadeOut(animationSpec = isNotPlayingOutIconSpec())
                    } else {
                        slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = isNotPlayingInIconSpec(),
                        ) + fadeIn(animationSpec = isNotPlayingInIconSpec()) togetherWith
                            fadeOut(animationSpec = isPlayingOutSpec())
                    }
                }
            ) {
                Icon(
                    icon = it.icon,
                    tint = it.iconColor.toColor(),
                    modifier = Modifier.padding(12.dp).fillMaxSize(),
                )
            }
        }
    }
}

private fun <T> isPlayingOutSpec() = tween<T>(durationMillis = 400, delayMillis = 500)

private fun <T> isPlayingInIconSpec() = tween<T>(durationMillis = 400, delayMillis = 300)

private fun <T> isPlayingInIconBackgroundSpec() = tween<T>(durationMillis = 400, delayMillis = 700)

private fun <T> isNotPlayingOutIconSpec() = tween<T>(durationMillis = 400, delayMillis = 300)

private fun <T> isNotPlayingInIconSpec() = tween<T>(durationMillis = 400, delayMillis = 900)
