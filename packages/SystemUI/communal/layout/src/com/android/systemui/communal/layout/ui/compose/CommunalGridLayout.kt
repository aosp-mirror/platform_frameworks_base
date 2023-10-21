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

package com.android.systemui.communal.layout.ui.compose

import android.util.SizeF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.systemui.communal.layout.CommunalLayoutEngine
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutCard
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutConfig

/**
 * An arrangement of cards with a horizontal scroll, where each card is displayed in the right size
 * and follows a specific order based on its priority, ensuring a seamless layout without any gaps.
 */
@Composable
fun CommunalGridLayout(
    modifier: Modifier,
    layoutConfig: CommunalGridLayoutConfig,
    communalCards: List<CommunalGridLayoutCard>,
) {
    val columns = CommunalLayoutEngine.distributeCardsIntoColumns(communalCards)
    LazyRow(
        modifier = modifier.height(layoutConfig.gridHeight),
        horizontalArrangement = Arrangement.spacedBy(layoutConfig.gridGutter),
    ) {
        for (column in columns) {
            item {
                Column(
                    modifier = Modifier.width(layoutConfig.cardWidth),
                    verticalArrangement = Arrangement.spacedBy(layoutConfig.gridGutter),
                ) {
                    for (cardInfo in column) {
                        Row(
                            modifier = Modifier.height(layoutConfig.cardHeight(cardInfo.size)),
                        ) {
                            cardInfo.card.Content(
                                modifier = Modifier.fillMaxSize(),
                                size =
                                    SizeF(
                                        layoutConfig.cardWidth.value,
                                        layoutConfig.cardHeight(cardInfo.size).value,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
