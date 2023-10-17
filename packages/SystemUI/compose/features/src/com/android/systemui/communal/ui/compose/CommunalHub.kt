package com.android.systemui.communal.ui.compose

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
import com.android.systemui.communal.layout.ui.compose.CommunalGridLayout
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutCard
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutConfig
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.res.R

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: CommunalViewModel,
) {
    val showTutorial by viewModel.showTutorialContent.collectAsState(initial = false)
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
            communalCards = if (showTutorial) tutorialContent else emptyList(),
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
        override fun Content(modifier: Modifier) {
            Card(modifier = modifier, content = {})
        }
    }
}
