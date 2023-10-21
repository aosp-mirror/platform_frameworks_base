package com.android.systemui.communal.ui.compose

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.communal.layout.ui.compose.CommunalGridLayout
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutCard
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutConfig
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
        CommunalGridLayout(
            modifier = Modifier.align(Alignment.CenterStart),
            layoutConfig =
                CommunalGridLayoutConfig(
                    gridColumnSize = dimensionResource(R.dimen.communal_grid_column_size),
                    gridGutter = dimensionResource(R.dimen.communal_grid_gutter_size),
                    gridHeight = dimensionResource(R.dimen.communal_grid_height),
                    gridColumnsPerCard = integerResource(R.integer.communal_grid_columns_per_card),
                ),
            communalCards = if (showTutorial) tutorialContent else widgetContent.map(::contentCard),
        )
    }
}

private val tutorialContent =
    listOf(
        tutorialCard(CommunalGridLayoutCard.Size.FULL),
        tutorialCard(CommunalGridLayoutCard.Size.THIRD),
        tutorialCard(CommunalGridLayoutCard.Size.THIRD),
        tutorialCard(CommunalGridLayoutCard.Size.THIRD),
        tutorialCard(CommunalGridLayoutCard.Size.HALF),
        tutorialCard(CommunalGridLayoutCard.Size.HALF),
        tutorialCard(CommunalGridLayoutCard.Size.HALF),
        tutorialCard(CommunalGridLayoutCard.Size.HALF),
    )

private fun tutorialCard(size: CommunalGridLayoutCard.Size): CommunalGridLayoutCard {
    return object : CommunalGridLayoutCard() {
        override val supportedSizes = listOf(size)

        @Composable
        override fun Content(modifier: Modifier, size: SizeF) {
            Card(modifier = modifier, content = {})
        }
    }
}

private fun contentCard(model: CommunalContentUiModel): CommunalGridLayoutCard {
    return object : CommunalGridLayoutCard() {
        override val supportedSizes = listOf(convertToCardSize(model.size))
        override val priority = model.priority

        @Composable
        override fun Content(modifier: Modifier, size: SizeF) {
            AndroidView(
                modifier = modifier,
                factory = {
                    model.view.apply {
                        if (this is AppWidgetHostView) {
                            updateAppWidgetSize(Bundle(), listOf(size))
                        }
                    }
                },
            )
        }
    }
}

private fun convertToCardSize(size: CommunalContentSize): CommunalGridLayoutCard.Size {
    return when (size) {
        CommunalContentSize.FULL -> CommunalGridLayoutCard.Size.FULL
        CommunalContentSize.HALF -> CommunalGridLayoutCard.Size.HALF
        CommunalContentSize.THIRD -> CommunalGridLayoutCard.Size.THIRD
    }
}
