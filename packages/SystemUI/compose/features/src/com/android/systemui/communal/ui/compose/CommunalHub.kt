package com.android.systemui.communal.ui.compose

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.util.SizeF
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.ui.model.CommunalContentUiModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.media.controls.ui.MediaHostState

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: CommunalViewModel,
) {
    val showTutorial by viewModel.showTutorialContent.collectAsState(initial = false)
    val widgetContent by viewModel.widgetContent.collectAsState(initial = emptyList())
    val isMediaPlaying by viewModel.isMediaPlaying.collectAsState(initial = false)
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
                    )
                }
            }
        }

        if (isMediaPlaying) {
            Box(
                modifier =
                    Modifier.width(Dimensions.CardWidth)
                        .height(Dimensions.CardHeightThird)
                        .padding(Dimensions.Spacing)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
    modifier: Modifier = Modifier,
) {
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

private fun CommunalContentSize.dp(): Dp {
    return when (this) {
        CommunalContentSize.FULL -> Dimensions.CardHeightFull
        CommunalContentSize.HALF -> Dimensions.CardHeightHalf
        CommunalContentSize.THIRD -> Dimensions.CardHeightThird
    }
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
