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

package com.android.systemui.communal.ui.compose

import android.os.Bundle
import android.util.SizeF
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.media.controls.ui.MediaHostState
import com.android.systemui.res.R

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: BaseCommunalViewModel,
) {
    val communalContent by viewModel.communalContent.collectAsState(initial = emptyList())
    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        LazyHorizontalGrid(
            modifier = modifier.height(Dimensions.GridHeight).align(Alignment.CenterStart),
            rows = GridCells.Fixed(CommunalContentSize.FULL.span),
            contentPadding = PaddingValues(horizontal = Dimensions.Spacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
        ) {
            items(
                count = communalContent.size,
                key = { index -> communalContent[index].key },
                span = { index -> GridItemSpan(communalContent[index].size.span) },
            ) { index ->
                CommunalContent(
                    modifier = Modifier.fillMaxHeight().width(Dimensions.CardWidth),
                    model = communalContent[index],
                    viewModel = viewModel,
                    deleteOnClick = viewModel::onDeleteWidget,
                    size =
                        SizeF(
                            Dimensions.CardWidth.value,
                            communalContent[index].size.dp().value,
                        ),
                )
            }
        }
        IconButton(onClick = viewModel::onOpenWidgetEditor) {
            Icon(Icons.Default.Add, stringResource(R.string.button_to_open_widget_editor))
        }

        // This spacer covers the edge of the LazyHorizontalGrid and prevents it from receiving
        // touches, so that the SceneTransitionLayout can intercept the touches and allow an edge
        // swipe back to the blank scene.
        Spacer(
            Modifier.height(Dimensions.GridHeight)
                .align(Alignment.CenterStart)
                .width(Dimensions.Spacing)
                .pointerInput(Unit) {}
        )
    }
}

@Composable
private fun CommunalContent(
    model: CommunalContentModel,
    viewModel: BaseCommunalViewModel,
    size: SizeF,
    deleteOnClick: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (model) {
        is CommunalContentModel.Widget -> WidgetContent(model, size, deleteOnClick, modifier)
        is CommunalContentModel.Smartspace -> SmartspaceContent(model, modifier)
        is CommunalContentModel.Tutorial -> TutorialContent(modifier)
        is CommunalContentModel.Umo -> Umo(viewModel, modifier)
    }
}

@Composable
private fun WidgetContent(
    model: CommunalContentModel.Widget,
    size: SizeF,
    deleteOnClick: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // TODO(b/309009246): update background color
    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        IconButton(onClick = { deleteOnClick(model.appWidgetId) }) {
            Icon(
                Icons.Default.Close,
                LocalContext.current.getString(R.string.button_to_remove_widget)
            )
        }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                model.appWidgetHost
                    .createView(context, model.appWidgetId, model.providerInfo)
                    .apply { updateAppWidgetSize(Bundle.EMPTY, listOf(size)) }
            },
            // For reusing composition in lazy lists.
            onReset = {}
        )
    }
}

@Composable
private fun SmartspaceContent(
    model: CommunalContentModel.Smartspace,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply { addView(model.remoteViews.apply(context, this)) }
        },
        // For reusing composition in lazy lists.
        onReset = {}
    )
}

@Composable
private fun TutorialContent(modifier: Modifier = Modifier) {
    Card(modifier = modifier, content = {})
}

@Composable
private fun Umo(viewModel: BaseCommunalViewModel, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = {
            viewModel.mediaHost.expansion = MediaHostState.EXPANDED
            viewModel.mediaHost.showsOnlyActiveMedia = false
            viewModel.mediaHost.falsingProtectionNeeded = false
            viewModel.mediaHost.init(MediaHierarchyManager.LOCATION_COMMUNAL_HUB)
            viewModel.mediaHost.hostView.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            viewModel.mediaHost.hostView
        },
        // For reusing composition in lazy lists.
        onReset = {},
    )
}

private fun CommunalContentSize.dp(): Dp {
    return when (this) {
        CommunalContentSize.FULL -> Dimensions.CardHeightFull
        CommunalContentSize.HALF -> Dimensions.CardHeightHalf
        CommunalContentSize.THIRD -> Dimensions.CardHeightThird
    }
}

private object Dimensions {
    val CardWidth = 464.dp
    val CardHeightFull = 630.dp
    val CardHeightHalf = 307.dp
    val CardHeightThird = 199.dp
    val GridHeight = CardHeightFull
    val Spacing = 16.dp
}
