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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.core.view.setPadding
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.ui.compose.Dimensions.CardOutlineWidth
import com.android.systemui.communal.ui.compose.extensions.allowGestures
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.communal.ui.compose.extensions.firstItemAtOffset
import com.android.systemui.communal.ui.compose.extensions.observeTapsWithoutConsuming
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.res.R
import kotlinx.coroutines.launch

@Composable
fun CommunalHub(
    modifier: Modifier = Modifier,
    viewModel: BaseCommunalViewModel,
    widgetConfigurator: WidgetConfigurator? = null,
    onOpenWidgetPicker: (() -> Unit)? = null,
    onEditDone: (() -> Unit)? = null,
) {
    val communalContent by viewModel.communalContent.collectAsState(initial = emptyList())
    val isPopupOnDismissCtaShowing by
        viewModel.isPopupOnDismissCtaShowing.collectAsState(initial = false)
    var removeButtonCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    var toolbarSize: IntSize? by remember { mutableStateOf(null) }
    var gridCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    var isDraggingToRemove by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val contentListState = rememberContentListState(widgetConfigurator, communalContent, viewModel)
    val reorderingWidgets by viewModel.reorderingWidgets.collectAsState()
    val selectedIndex = viewModel.selectedIndex.collectAsState()
    val removeButtonEnabled by remember {
        derivedStateOf { selectedIndex.value != null || reorderingWidgets }
    }
    val (isButtonToEditWidgetsShowing, setIsButtonToEditWidgetsShowing) =
        remember { mutableStateOf(false) }

    val contentPadding = gridContentPadding(viewModel.isEditMode, toolbarSize)
    val contentOffset = beforeContentPadding(contentPadding).toOffset()

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAndroidColorScheme.current.outlineVariant)
                .pointerInput(gridState, contentOffset, contentListState) {
                    // If not in edit mode, don't allow selecting items.
                    if (!viewModel.isEditMode) return@pointerInput
                    observeTapsWithoutConsuming { offset ->
                        val adjustedOffset = offset - contentOffset
                        val index =
                            gridState.layoutInfo.visibleItemsInfo
                                .firstItemAtOffset(adjustedOffset)
                                ?.index
                        val newIndex =
                            if (index?.let(contentListState::isItemEditable) == true) {
                                index
                            } else {
                                null
                            }
                        viewModel.setSelectedIndex(newIndex)
                    }
                }
                .thenIf(!viewModel.isEditMode) {
                    Modifier.pointerInput(Unit) {
                        detectLongPressGesture { offset -> setIsButtonToEditWidgetsShowing(true) }
                    }
                },
    ) {
        CommunalHubLazyGrid(
            communalContent = communalContent,
            viewModel = viewModel,
            contentPadding = contentPadding,
            contentOffset = contentOffset,
            setGridCoordinates = { gridCoordinates = it },
            updateDragPositionForRemove = { offset ->
                isDraggingToRemove =
                    isPointerWithinCoordinates(
                        offset = gridCoordinates?.let { it.positionInWindow() + offset },
                        containerToCheck = removeButtonCoordinates
                    )
                isDraggingToRemove
            },
            onOpenWidgetPicker = onOpenWidgetPicker,
            gridState = gridState,
            contentListState = contentListState,
            selectedIndex = selectedIndex,
            widgetConfigurator = widgetConfigurator,
        )

        if (viewModel.isEditMode && onOpenWidgetPicker != null && onEditDone != null) {
            Toolbar(
                isDraggingToRemove = isDraggingToRemove,
                setToolbarSize = { toolbarSize = it },
                setRemoveButtonCoordinates = { removeButtonCoordinates = it },
                onEditDone = onEditDone,
                onOpenWidgetPicker = onOpenWidgetPicker,
                onRemoveClicked = {
                    selectedIndex.value?.let { index ->
                        contentListState.onRemove(index)
                        contentListState.onSaveList()
                        viewModel.setSelectedIndex(null)
                    }
                },
                removeEnabled = removeButtonEnabled
            )
        }

        if (isPopupOnDismissCtaShowing) {
            PopupOnDismissCtaTile(viewModel::onHidePopupAfterDismissCta)
        }

        if (isButtonToEditWidgetsShowing) {
            ButtonToEditWidgets(
                onClick = {
                    setIsButtonToEditWidgetsShowing(false)
                    viewModel.onOpenWidgetEditor()
                },
                onHide = { setIsButtonToEditWidgetsShowing(false) },
            )
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
    selectedIndex: State<Int?>,
    contentOffset: Offset,
    gridState: LazyGridState,
    contentListState: ContentListState,
    setGridCoordinates: (coordinates: LayoutCoordinates) -> Unit,
    updateDragPositionForRemove: (offset: Offset) -> Boolean,
    onOpenWidgetPicker: (() -> Unit)? = null,
    widgetConfigurator: WidgetConfigurator?,
) {
    var gridModifier = Modifier.align(Alignment.CenterStart)
    var list = communalContent
    var dragDropState: GridDragDropState? = null
    if (viewModel.isEditMode && viewModel is CommunalEditModeViewModel) {
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
                .dragContainer(dragDropState, contentOffset, viewModel)
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
                val selected by remember(index) { derivedStateOf { index == selectedIndex.value } }
                DraggableItem(
                    dragDropState = dragDropState,
                    selected = selected,
                    enabled = list[index] is CommunalContentModel.Widget,
                    index = index,
                    size = size
                ) { isDragging ->
                    CommunalContent(
                        modifier = cardModifier,
                        model = list[index],
                        viewModel = viewModel,
                        size = size,
                        onOpenWidgetPicker = onOpenWidgetPicker,
                        selected = selected && !isDragging,
                        widgetConfigurator = widgetConfigurator,
                    )
                }
            } else {
                CommunalContent(
                    model = list[index],
                    viewModel = viewModel,
                    size = size,
                    selected = false,
                    modifier = cardModifier,
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
    removeEnabled: Boolean,
    onRemoveClicked: () -> Unit,
    setToolbarSize: (toolbarSize: IntSize) -> Unit,
    setRemoveButtonCoordinates: (coordinates: LayoutCoordinates) -> Unit,
    onOpenWidgetPicker: () -> Unit,
    onEditDone: () -> Unit
) {
    val removeButtonAlpha: Float by
        animateFloatAsState(
            targetValue = if (removeEnabled) 1f else 0.5f,
            label = "RemoveButtonAlphaAnimation"
        )

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
        val spacerModifier = Modifier.width(Dimensions.ToolbarButtonSpaceBetween)
        Button(
            onClick = onOpenWidgetPicker,
            colors = filledButtonColors(),
            contentPadding = Dimensions.ButtonPadding
        ) {
            Icon(Icons.Default.Add, stringResource(R.string.hub_mode_add_widget_button_text))
            Spacer(spacerModifier)
            Text(
                text = stringResource(R.string.hub_mode_add_widget_button_text),
            )
        }

        val colors = LocalAndroidColorScheme.current
        if (isDraggingToRemove) {
            Button(
                // Button is disabled to make it non-clickable
                enabled = false,
                onClick = {},
                colors =
                    ButtonDefaults.buttonColors(
                        disabledContainerColor = colors.primary,
                        disabledContentColor = colors.onPrimary,
                    ),
                contentPadding = Dimensions.ButtonPadding,
                modifier = Modifier.onGloballyPositioned { setRemoveButtonCoordinates(it) }
            ) {
                RemoveButtonContent(spacerModifier)
            }
        } else {
            OutlinedButton(
                enabled = removeEnabled,
                onClick = onRemoveClicked,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.primary,
                        disabledContentColor = colors.primary
                    ),
                border = BorderStroke(width = 1.0.dp, color = colors.primary),
                contentPadding = Dimensions.ButtonPadding,
                modifier =
                    Modifier.graphicsLayer { alpha = removeButtonAlpha }
                        .onGloballyPositioned { setRemoveButtonCoordinates(it) }
            ) {
                RemoveButtonContent(spacerModifier)
            }
        }

        Button(
            onClick = onEditDone,
            colors = filledButtonColors(),
            contentPadding = Dimensions.ButtonPadding
        ) {
            Text(
                text = stringResource(R.string.hub_mode_editing_exit_button_text),
            )
        }
    }
}

@Composable
private fun ButtonToEditWidgets(
    onClick: () -> Unit,
    onHide: () -> Unit,
) {
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 40), onDismissRequest = onHide) {
        val colors = LocalAndroidColorScheme.current
        Button(
            modifier =
                Modifier.height(56.dp).background(colors.secondary, RoundedCornerShape(50.dp)),
            onClick = onClick,
        ) {
            Icon(
                imageVector = Icons.Outlined.Widgets,
                contentDescription = stringResource(R.string.button_to_configure_widgets_text),
                tint = colors.onSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.button_to_configure_widgets_text),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondary,
            )
        }
    }
}

@Composable
private fun PopupOnDismissCtaTile(onHidePopupAfterDismissCta: () -> Unit) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 40),
        onDismissRequest = onHidePopupAfterDismissCta
    ) {
        val colors = LocalAndroidColorScheme.current
        Row(
            modifier =
                Modifier.height(56.dp)
                    .background(colors.secondary, RoundedCornerShape(50.dp))
                    .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.TouchApp,
                contentDescription = stringResource(R.string.popup_on_dismiss_cta_tile_text),
                tint = colors.onSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.popup_on_dismiss_cta_tile_text),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondary,
            )
        }
    }
}

@Composable
private fun RemoveButtonContent(spacerModifier: Modifier) {
    Icon(Icons.Outlined.Delete, stringResource(R.string.button_to_remove_widget))
    Spacer(spacerModifier)
    Text(
        text = stringResource(R.string.button_to_remove_widget),
    )
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
private fun CommunalContent(
    model: CommunalContentModel,
    viewModel: BaseCommunalViewModel,
    size: SizeF,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onOpenWidgetPicker: (() -> Unit)? = null,
    widgetConfigurator: WidgetConfigurator? = null,
) {
    when (model) {
        is CommunalContentModel.Widget ->
            WidgetContent(viewModel, model, size, selected, widgetConfigurator, modifier)
        is CommunalContentModel.WidgetPlaceholder -> HighlightedItem(size)
        is CommunalContentModel.CtaTileInViewMode ->
            CtaTileInViewModeContent(viewModel, size, modifier)
        is CommunalContentModel.CtaTileInEditMode ->
            CtaTileInEditModeContent(size, modifier, onOpenWidgetPicker)
        is CommunalContentModel.Smartspace -> SmartspaceContent(model, modifier)
        is CommunalContentModel.Tutorial -> TutorialContent(modifier)
        is CommunalContentModel.Umo -> Umo(viewModel, modifier)
    }
}

/** Creates an empty card used to highlight a particular spot on the grid. */
@Composable
fun HighlightedItem(size: SizeF, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.size(Dp(size.width), Dp(size.height)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(CardOutlineWidth, LocalAndroidColorScheme.current.tertiaryFixed),
        shape = RoundedCornerShape(16.dp)
    ) {}
}

/** Presents a CTA tile at the end of the grid, to customize the hub. */
@Composable
private fun CtaTileInViewModeContent(
    viewModel: BaseCommunalViewModel,
    size: SizeF,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAndroidColorScheme.current
    Card(
        modifier = modifier.height(size.height.dp).padding(CardOutlineWidth),
        colors =
            CardDefaults.cardColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
        shape = RoundedCornerShape(80.dp, 40.dp, 80.dp, 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 82.dp),
            verticalArrangement =
                Arrangement.spacedBy(Dimensions.Spacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Widgets,
                contentDescription = stringResource(R.string.cta_label_to_open_widget_picker),
                modifier = Modifier.size(Dimensions.IconSize),
            )
            Text(
                text = stringResource(R.string.cta_label_to_edit_widget),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                OutlinedButton(
                    colors =
                        ButtonDefaults.buttonColors(
                            contentColor = colors.onPrimary,
                        ),
                    border = BorderStroke(width = 1.0.dp, color = colors.primaryContainer),
                    contentPadding = Dimensions.ButtonPadding,
                    onClick = viewModel::onDismissCtaTile,
                ) {
                    Text(
                        text = stringResource(R.string.cta_tile_button_to_dismiss),
                    )
                }
                Spacer(modifier = Modifier.size(Dimensions.Spacing))
                Button(
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                        ),
                    contentPadding = Dimensions.ButtonPadding,
                    onClick = viewModel::onOpenWidgetEditor
                ) {
                    Text(
                        text = stringResource(R.string.cta_tile_button_to_open_widget_editor),
                    )
                }
            }
        }
    }
}

/** Presents a CTA tile at the end of the hub in edit mode, to add more widgets. */
@Composable
private fun CtaTileInEditModeContent(
    size: SizeF,
    modifier: Modifier = Modifier,
    onOpenWidgetPicker: (() -> Unit)? = null,
) {
    if (onOpenWidgetPicker == null) {
        throw IllegalArgumentException("onOpenWidgetPicker should not be null.")
    }
    val colors = LocalAndroidColorScheme.current
    Card(
        modifier = modifier.height(size.height.dp).padding(CardOutlineWidth),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, colors.primary),
        shape = RoundedCornerShape(200.dp),
        onClick = onOpenWidgetPicker,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement =
                Arrangement.spacedBy(Dimensions.Spacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Widgets,
                contentDescription = stringResource(R.string.cta_label_to_open_widget_picker),
                tint = colors.primary,
                modifier = Modifier.size(Dimensions.IconSize),
            )
            Text(
                text = stringResource(R.string.cta_label_to_open_widget_picker),
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WidgetContent(
    viewModel: BaseCommunalViewModel,
    model: CommunalContentModel.Widget,
    size: SizeF,
    selected: Boolean,
    widgetConfigurator: WidgetConfigurator?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(size.height.dp),
    ) {
        val paddingInPx = with(LocalDensity.current) { CardOutlineWidth.toPx().toInt() }
        AndroidView(
            modifier =
                modifier.align(Alignment.Center).allowGestures(allowed = !viewModel.isEditMode),
            factory = { context ->
                val view =
                    model.appWidgetHost
                        .createViewForCommunal(context, model.appWidgetId, model.providerInfo)
                        .apply { updateAppWidgetSize(Bundle.EMPTY, listOf(size)) }
                // Remove the extra padding applied to AppWidgetHostView to allow widgets to
                // occupy the entire box. The added padding is now adjusted to leave only sufficient
                // space for displaying the outline around the box when the widget is selected.
                view.setPadding(paddingInPx)
                view
            },
            // For reusing composition in lazy lists.
            onReset = {},
        )
        if (
            viewModel is CommunalEditModeViewModel &&
                model.reconfigurable &&
                widgetConfigurator != null
        ) {
            WidgetConfigureButton(
                visible = selected,
                model = model,
                widgetConfigurator = widgetConfigurator,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun WidgetConfigureButton(
    visible: Boolean,
    model: CommunalContentModel.Widget,
    modifier: Modifier = Modifier,
    widgetConfigurator: WidgetConfigurator,
) {
    val colors = LocalAndroidColorScheme.current
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(16.dp),
    ) {
        FilledIconButton(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(48.dp),
            colors =
                IconButtonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.Transparent
                ),
            onClick = { scope.launch { widgetConfigurator.configureWidget(model.appWidgetId) } },
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(id = R.string.edit_widget),
                modifier = Modifier.padding(12.dp)
            )
        }
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
            start = paddingValues.calculateLeftPadding(LayoutDirection.Ltr).toPx(),
            top = paddingValues.calculateTopPadding().toPx()
        )
    }
}

/**
 * Check whether the pointer position that the item is being dragged at is within the coordinates of
 * the remove button in the toolbar. Returns true if the item is removable.
 */
private fun isPointerWithinCoordinates(
    offset: Offset?,
    containerToCheck: LayoutCoordinates?
): Boolean {
    if (offset == null || containerToCheck == null) {
        return false
    }
    val container = containerToCheck.boundsInWindow()
    return container.contains(offset)
}

private fun CommunalContentSize.dp(): Dp {
    return when (this) {
        CommunalContentSize.FULL -> Dimensions.CardHeightFull
        CommunalContentSize.HALF -> Dimensions.CardHeightHalf
        CommunalContentSize.THIRD -> Dimensions.CardHeightThird
    }
}

data class ContentPaddingInPx(val start: Float, val top: Float) {
    fun toOffset(): Offset = Offset(start, top)
}

object Dimensions {
    val CardWidth = 464.dp
    val CardHeightFull = 630.dp
    val CardHeightHalf = 307.dp
    val CardHeightThird = 199.dp
    val CardOutlineWidth = 3.dp
    val GridHeight = CardHeightFull
    val Spacing = 16.dp

    // The sizing/padding of the toolbar in glanceable hub edit mode
    val ToolbarPaddingTop = 27.dp
    val ToolbarPaddingHorizontal = 16.dp
    val ToolbarButtonPaddingHorizontal = 24.dp
    val ToolbarButtonPaddingVertical = 16.dp
    val ToolbarButtonSpaceBetween = 8.dp
    val ButtonPadding =
        PaddingValues(
            vertical = ToolbarButtonPaddingVertical,
            horizontal = ToolbarButtonPaddingHorizontal,
        )
    val IconSize = 48.dp
}
