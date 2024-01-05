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
import android.widget.FrameLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.media.controls.ui.MediaHostState
import com.android.systemui.res.R

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: BaseCommunalViewModel,
    onOpenWidgetPicker: (() -> Unit)? = null,
    onEditDone: (() -> Unit)? = null,
) {
    val communalContent by viewModel.communalContent.collectAsState(initial = emptyList())
    var removeButtonCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    var toolbarSize: IntSize? by remember { mutableStateOf(null) }
    var gridCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    var isDraggingToRemove by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        CommunalHubLazyGrid(
            communalContent = communalContent,
            viewModel = viewModel,
            contentPadding = gridContentPadding(viewModel.isEditMode, toolbarSize),
            setGridCoordinates = { gridCoordinates = it },
            updateDragPositionForRemove = {
                isDraggingToRemove =
                    checkForDraggingToRemove(it, removeButtonCoordinates, gridCoordinates)
                isDraggingToRemove
            }
        )

        if (viewModel.isEditMode && onOpenWidgetPicker != null && onEditDone != null) {
            Toolbar(
                isDraggingToRemove = isDraggingToRemove,
                setToolbarSize = { toolbarSize = it },
                setRemoveButtonCoordinates = { removeButtonCoordinates = it },
                onEditDone = onEditDone,
                onOpenWidgetPicker = onOpenWidgetPicker,
            )
        } else {
            IconButton(onClick = viewModel::onOpenWidgetEditor) {
                Icon(Icons.Default.Edit, stringResource(R.string.button_to_open_widget_editor))
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.CommunalHubLazyGrid(
    communalContent: List<CommunalContentModel>,
    viewModel: BaseCommunalViewModel,
    contentPadding: PaddingValues,
    setGridCoordinates: (coordinates: LayoutCoordinates) -> Unit,
    updateDragPositionForRemove: (offset: Offset) -> Boolean,
) {
    var gridModifier = Modifier.align(Alignment.CenterStart)
    val gridState = rememberLazyGridState()
    var list = communalContent
    var dragDropState: GridDragDropState? = null
    if (viewModel.isEditMode && viewModel is CommunalEditModeViewModel) {
        val contentListState = rememberContentListState(communalContent, viewModel)
        list = contentListState.list
        // for drag & drop operations within the communal hub grid
        dragDropState =
            rememberGridDragDropState(
                gridState = gridState,
                contentListState = contentListState,
                updateDragPositionForRemove = updateDragPositionForRemove
            )
        gridModifier =
            gridModifier
                .fillMaxSize()
                .dragContainer(dragDropState, beforeContentPadding(contentPadding))
                .onGloballyPositioned { setGridCoordinates(it) }
        // for widgets dropped from other activities
        val dragAndDropTargetState =
            rememberDragAndDropTargetState(
                gridState = gridState,
                contentListState = contentListState,
                updateDragPositionForRemove = updateDragPositionForRemove
            )

        // A full size box in background that listens to widget drops from the picker.
        // Since the grid has its own listener for in-grid drag events, we use a separate element
        // for android drag events.
        Box(Modifier.fillMaxSize().dragAndDropTarget(dragAndDropTargetState)) {}
    } else {
        gridModifier = gridModifier.height(Dimensions.GridHeight)
    }

    LazyHorizontalGrid(
        modifier = gridModifier,
        state = gridState,
        rows = GridCells.Fixed(CommunalContentSize.FULL.span),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing),
    ) {
        items(
            count = list.size,
            key = { index -> list[index].key },
            contentType = { index -> list[index].key },
            span = { index -> GridItemSpan(list[index].size.span) },
        ) { index ->
            val cardModifier = Modifier.width(Dimensions.CardWidth)
            val size =
                SizeF(
                    Dimensions.CardWidth.value,
                    list[index].size.dp().value,
                )
            if (viewModel.isEditMode && dragDropState != null) {
                DraggableItem(
                    dragDropState = dragDropState,
                    enabled = true,
                    index = index,
                    size = size
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 1.dp)
                    CommunalContent(
                        modifier = cardModifier,
                        elevation = elevation,
                        model = list[index],
                        viewModel = viewModel,
                        size = size,
                    )
                }
            } else {
                CommunalContent(
                    modifier = cardModifier,
                    model = list[index],
                    viewModel = viewModel,
                    size = size,
                )
            }
        }
    }
}

/**
 * Toolbar that contains action buttons to
 * 1) open the widget picker
 * 2) remove a widget from the grid and
 * 3) exit the edit mode.
 */
@Composable
private fun Toolbar(
    isDraggingToRemove: Boolean,
    setToolbarSize: (toolbarSize: IntSize) -> Unit,
    setRemoveButtonCoordinates: (coordinates: LayoutCoordinates) -> Unit,
    onOpenWidgetPicker: () -> Unit,
    onEditDone: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    top = Dimensions.ToolbarPaddingTop,
                    start = Dimensions.ToolbarPaddingHorizontal,
                    end = Dimensions.ToolbarPaddingHorizontal,
                )
                .onSizeChanged { setToolbarSize(it) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val buttonContentPadding =
            PaddingValues(
                vertical = Dimensions.ToolbarButtonPaddingVertical,
                horizontal = Dimensions.ToolbarButtonPaddingHorizontal,
            )
        val spacerModifier = Modifier.width(Dimensions.ToolbarButtonSpaceBetween)
        Button(
            onClick = onOpenWidgetPicker,
            colors = filledSecondaryButtonColors(),
            contentPadding = buttonContentPadding
        ) {
            Icon(Icons.Default.Add, stringResource(R.string.button_to_open_widget_editor))
            Spacer(spacerModifier)
            Text(
                text = stringResource(R.string.hub_mode_add_widget_button_text),
            )
        }

        val buttonColors =
            if (isDraggingToRemove) filledButtonColors() else ButtonDefaults.outlinedButtonColors()
        OutlinedButton(
            onClick = {},
            colors = buttonColors,
            contentPadding = buttonContentPadding,
            modifier = Modifier.onGloballyPositioned { setRemoveButtonCoordinates(it) },
        ) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.button_to_open_widget_editor))
            Spacer(spacerModifier)
            Text(
                text = stringResource(R.string.button_to_remove_widget),
            )
        }

        Button(
            onClick = onEditDone,
            colors = filledButtonColors(),
            contentPadding = buttonContentPadding
        ) {
            Text(
                text = stringResource(R.string.hub_mode_editing_exit_button_text),
            )
        }
    }
}

@Composable
private fun filledButtonColors(): ButtonColors {
    val colors = LocalAndroidColorScheme.current
    return ButtonDefaults.buttonColors(
        containerColor = colors.primary,
        contentColor = colors.onPrimary,
    )
}

@Composable
private fun filledSecondaryButtonColors(): ButtonColors {
    val colors = LocalAndroidColorScheme.current
    return ButtonDefaults.buttonColors(
        containerColor = colors.secondary,
        contentColor = colors.onSecondary,
    )
}

@Composable
private fun CommunalContent(
    model: CommunalContentModel,
    viewModel: BaseCommunalViewModel,
    size: SizeF,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
) {
    when (model) {
        is CommunalContentModel.Widget -> WidgetContent(viewModel, model, size, elevation, modifier)
        is CommunalContentModel.WidgetPlaceholder -> WidgetPlaceholderContent(size)
        is CommunalContentModel.Smartspace -> SmartspaceContent(model, modifier)
        is CommunalContentModel.Tutorial -> TutorialContent(modifier)
        is CommunalContentModel.Umo -> Umo(viewModel, modifier)
    }
}

/** Presents a placeholder card for the new widget being dragged and dropping into the grid. */
@Composable
fun WidgetPlaceholderContent(size: SizeF) {
    Card(
        modifier = Modifier.size(Dp(size.width), Dp(size.height)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(3.dp, LocalAndroidColorScheme.current.tertiaryFixed),
        shape = RoundedCornerShape(16.dp)
    ) {}
}

@Composable
private fun WidgetContent(
    viewModel: BaseCommunalViewModel,
    model: CommunalContentModel.Widget,
    size: SizeF,
    elevation: Dp,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(size.height.dp),
        elevation = CardDefaults.cardElevation(draggedElevation = elevation),
    ) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                // The AppWidgetHostView will inherit the interaction handler from the
                // AppWidgetHost. So set the interaction handler here before creating the view, and
                // then clear it after the view is created. This is a workaround due to the fact
                // that the interaction handler cannot be specified when creating the view,
                // and there are race conditions if it is set after the view is created.
                model.appWidgetHost.setInteractionHandler(viewModel.getInteractionHandler())
                val view =
                    model.appWidgetHost
                        .createView(context, model.appWidgetId, model.providerInfo)
                        .apply { updateAppWidgetSize(Bundle.EMPTY, listOf(size)) }
                model.appWidgetHost.setInteractionHandler(null)
                view
            },
            // For reusing composition in lazy lists.
            onReset = {},
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
            AppWidgetHostView(context).apply { updateAppWidget(model.remoteViews) }
        },
        // For reusing composition in lazy lists.
        onReset = {},
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

/**
 * Returns the `contentPadding` of the grid. Use the vertical padding to push the grid content area
 * below the toolbar and let the grid take the max size. This ensures the item can be dragged
 * outside the grid over the toolbar, without part of it getting clipped by the container.
 */
@Composable
private fun gridContentPadding(isEditMode: Boolean, toolbarSize: IntSize?): PaddingValues {
    if (!isEditMode || toolbarSize == null) {
        return PaddingValues(horizontal = Dimensions.Spacing)
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp.dp
    val toolbarHeight = with(density) { Dimensions.ToolbarPaddingTop + toolbarSize.height.toDp() }
    val verticalPadding =
        ((screenHeight - toolbarHeight - Dimensions.GridHeight) / 2).coerceAtLeast(
            Dimensions.Spacing
        )
    return PaddingValues(
        start = Dimensions.ToolbarPaddingHorizontal,
        end = Dimensions.ToolbarPaddingHorizontal,
        top = verticalPadding + toolbarHeight,
        bottom = verticalPadding
    )
}

@Composable
private fun beforeContentPadding(paddingValues: PaddingValues): ContentPaddingInPx {
    return with(LocalDensity.current) {
        ContentPaddingInPx(
            startPadding = paddingValues.calculateLeftPadding(LayoutDirection.Ltr).toPx(),
            topPadding = paddingValues.calculateTopPadding().toPx()
        )
    }
}

/**
 * Check whether the pointer position that the item is being dragged at is within the coordinates of
 * the remove button in the toolbar. Returns true if the item is removable.
 */
private fun checkForDraggingToRemove(
    offset: Offset,
    removeButtonCoordinates: LayoutCoordinates?,
    gridCoordinates: LayoutCoordinates?,
): Boolean {
    if (removeButtonCoordinates == null || gridCoordinates == null) {
        return false
    }
    val pointer = gridCoordinates.positionInWindow() + offset
    val removeButton = removeButtonCoordinates.positionInWindow()
    return pointer.x in removeButton.x..removeButton.x + removeButtonCoordinates.size.width &&
        pointer.y in removeButton.y..removeButton.y + removeButtonCoordinates.size.height
}

private fun CommunalContentSize.dp(): Dp {
    return when (this) {
        CommunalContentSize.FULL -> Dimensions.CardHeightFull
        CommunalContentSize.HALF -> Dimensions.CardHeightHalf
        CommunalContentSize.THIRD -> Dimensions.CardHeightThird
    }
}

data class ContentPaddingInPx(val startPadding: Float, val topPadding: Float)

object Dimensions {
    val CardWidth = 464.dp
    val CardHeightFull = 630.dp
    val CardHeightHalf = 307.dp
    val CardHeightThird = 199.dp
    val GridHeight = CardHeightFull
    val Spacing = 16.dp

    // The sizing/padding of the toolbar in glanceable hub edit mode
    val ToolbarPaddingTop = 27.dp
    val ToolbarPaddingHorizontal = 16.dp
    val ToolbarButtonPaddingHorizontal = 24.dp
    val ToolbarButtonPaddingVertical = 16.dp
    val ToolbarButtonSpaceBetween = 8.dp
}
