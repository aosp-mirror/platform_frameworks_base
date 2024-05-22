/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.people.ui.compose

import android.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.people.ui.viewmodel.PeopleTileViewModel
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.res.R

/**
 * Compose the screen associated to a [PeopleViewModel].
 *
 * @param viewModel the [PeopleViewModel] that should be composed.
 * @param onResult the callback called with the result of this screen. Callers should usually finish
 *   the Activity/Fragment/View hosting this Composable once a result is available.
 */
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onResult: (PeopleViewModel.Result) -> Unit,
) {
    val priorityTiles by viewModel.priorityTiles.collectAsStateWithLifecycle()
    val recentTiles by viewModel.recentTiles.collectAsStateWithLifecycle()

    // Call [onResult] this activity when the ViewModel tells us so.
    LaunchedEffect(viewModel.result) {
        viewModel.result.collect { result ->
            if (result != null) {
                viewModel.clearResult()
                onResult(result)
            }
        }
    }

    // Make sure to use the Android colors and not the default Material3 colors to have the exact
    // same colors as the View implementation.
    val androidColors = LocalAndroidColorScheme.current.deprecated
    Surface(
        color = androidColors.colorBackground,
        contentColor = androidColors.textColorPrimary,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (priorityTiles.isNotEmpty() || recentTiles.isNotEmpty()) {
            PeopleScreenWithConversations(priorityTiles, recentTiles, viewModel.onTileClicked)
        } else {
            PeopleScreenEmpty(viewModel.onUserJourneyCancelled)
        }
    }
}

@Composable
private fun PeopleScreenWithConversations(
    priorityTiles: List<PeopleTileViewModel>,
    recentTiles: List<PeopleTileViewModel>,
    onTileClicked: (PeopleTileViewModel) -> Unit,
) {
    Column(
        Modifier.sysuiResTag("top_level_with_conversations"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(PeopleSpacePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.select_conversation_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.select_conversation_text),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            Modifier.fillMaxWidth()
                .sysuiResTag("scroll_view")
                .verticalScroll(rememberScrollState())
                .padding(
                    top = 16.dp,
                    bottom = PeopleSpacePadding,
                    start = 8.dp,
                    end = 8.dp,
                ),
        ) {
            val hasPriorityConversations = priorityTiles.isNotEmpty()
            if (hasPriorityConversations) {
                ConversationList(R.string.priority_conversations, priorityTiles, onTileClicked)
            }

            if (recentTiles.isNotEmpty()) {
                if (hasPriorityConversations) {
                    Spacer(Modifier.height(35.dp))
                }

                ConversationList(R.string.recent_conversations, recentTiles, onTileClicked)
            }
        }
    }
}

@Composable
private fun ConversationList(
    @StringRes headerTextResource: Int,
    tiles: List<PeopleTileViewModel>,
    onTileClicked: (PeopleTileViewModel) -> Unit
) {
    Text(
        stringResource(headerTextResource),
        Modifier.padding(start = 16.dp),
        style = MaterialTheme.typography.labelLarge,
        color = LocalAndroidColorScheme.current.deprecated.colorAccentPrimaryVariant,
    )

    Spacer(Modifier.height(10.dp))

    tiles.forEachIndexed { index, tile ->
        if (index > 0) {
            Divider(
                color = LocalAndroidColorScheme.current.deprecated.colorBackground,
                thickness = 2.dp,
            )
        }

        key(tile.key.toString()) {
            Tile(
                tile,
                onTileClicked,
                withTopCornerRadius = index == 0,
                withBottomCornerRadius = index == tiles.lastIndex,
            )
        }
    }
}

@Composable
private fun Tile(
    tile: PeopleTileViewModel,
    onTileClicked: (PeopleTileViewModel) -> Unit,
    withTopCornerRadius: Boolean,
    withBottomCornerRadius: Boolean,
) {
    val androidColors = LocalAndroidColorScheme.current.deprecated
    val cornerRadius = dimensionResource(R.dimen.people_space_widget_radius)
    val topCornerRadius = if (withTopCornerRadius) cornerRadius else 0.dp
    val bottomCornerRadius = if (withBottomCornerRadius) cornerRadius else 0.dp

    Surface(
        color = androidColors.colorSurface,
        contentColor = androidColors.textColorPrimary,
        shape =
            RoundedCornerShape(
                topStart = topCornerRadius,
                topEnd = topCornerRadius,
                bottomStart = bottomCornerRadius,
                bottomEnd = bottomCornerRadius,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onTileClicked(tile) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                tile.icon.asImageBitmap(),
                // TODO(b/238993727): Add a content description.
                contentDescription = null,
                Modifier.size(dimensionResource(R.dimen.avatar_size_for_medium)),
            )

            Text(
                tile.username ?: "",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/** The padding applied to the PeopleSpace screen. */
internal val PeopleSpacePadding = 24.dp
