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

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.ui.model.CommunalContentUiModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.res.R

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: CommunalViewModel,
) {
    val showTutorial by viewModel.showTutorialContent.collectAsState(initial = false)
    val widgetContent by viewModel.widgetContent.collectAsState(initial = emptyList())
    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        LazyHorizontalGrid(
            modifier = modifier.height(Dimensions.GridHeight).align(Alignment.CenterStart),
            rows = GridCells.Fixed(CommunalContentSize.FULL.span),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
        ) {
            if (showTutorial) {
                items(
                    count = tutorialContentSizes.size,
                    // TODO(b/308148193): a more scalable solution for unique ids.
                    key = { index -> "tutorial_$index" },
                    span = { index -> GridItemSpan(tutorialContentSizes[index].span) },
                ) { index ->
                    TutorialCard(
                        modifier =
                            Modifier.size(Dimensions.CardWidth, tutorialContentSizes[index].dp()),
                    )
                }
            } else {
                items(
                    count = widgetContent.size,
                    key = { index -> widgetContent[index].id },
                    span = { index -> GridItemSpan(widgetContent[index].size.span) },
                ) { index ->
                    val widget = widgetContent[index]
                    ContentCard(
                        modifier = Modifier.size(Dimensions.CardWidth, widget.size.dp()),
                        model = widget,
                        deleteOnClick = viewModel::onDeleteWidget
                    )
                }
            }
        }
        IconButton(onClick = viewModel::onOpenWidgetPicker) {
            Icon(
                Icons.Default.Add,
                LocalContext.current.getString(R.string.button_to_open_widget_picker)
            )
        }
    }
}

// A placeholder for tutorial content.
@Composable
private fun TutorialCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier, content = {})
}

@Composable
private fun ContentCard(
    model: CommunalContentUiModel,
    deleteOnClick: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // TODO(b/309009246): update background color
    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        // TODO(b/308148193): this will be cleaned up soon once the change to convert to
        // CommunalContentUiModel interface is merged
        val widgetId = getWidgetId(model.id)
        widgetId?.let {
            IconButton(onClick = { deleteOnClick(it) }) {
                Icon(
                    Icons.Default.Close,
                    LocalContext.current.getString(R.string.button_to_remove_widget)
                )
            }
        }
        AndroidView(
            modifier = modifier,
            factory = {
                model.view.apply {
                    if (this is AppWidgetHostView) {
                        val size = SizeF(Dimensions.CardWidth.value, model.size.dp().value)
                        updateAppWidgetSize(Bundle.EMPTY, listOf(size))
                    }
                }
            },
        )
    }
}

private fun CommunalContentSize.dp(): Dp {
    return when (this) {
        CommunalContentSize.FULL -> Dimensions.CardHeightFull
        CommunalContentSize.HALF -> Dimensions.CardHeightHalf
        CommunalContentSize.THIRD -> Dimensions.CardHeightThird
    }
}

private fun getWidgetId(id: String): Int? {
    return if (id.startsWith("widget_")) id.substring("widget_".length).toInt() else null
}

// Sizes for the tutorial placeholders.
private val tutorialContentSizes =
    listOf(
        CommunalContentSize.FULL,
        CommunalContentSize.THIRD,
        CommunalContentSize.THIRD,
        CommunalContentSize.THIRD,
        CommunalContentSize.HALF,
        CommunalContentSize.HALF,
        CommunalContentSize.HALF,
        CommunalContentSize.HALF,
    )

private object Dimensions {
    val CardWidth = 464.dp
    val CardHeightFull = 630.dp
    val CardHeightHalf = 307.dp
    val CardHeightThird = 199.dp
    val GridHeight = CardHeightFull
    val Spacing = 16.dp
}
