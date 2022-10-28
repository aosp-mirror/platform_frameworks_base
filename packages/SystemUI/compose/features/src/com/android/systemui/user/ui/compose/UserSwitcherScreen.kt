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
 *
 */

package com.android.systemui.user.ui.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.SysUiOutlinedButton
import com.android.systemui.compose.SysUiTextButton
import com.android.systemui.compose.features.R
import com.android.systemui.compose.theme.LocalAndroidColorScheme
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.user.ui.viewmodel.UserViewModel
import java.lang.Integer.min
import kotlin.math.ceil

@Composable
fun UserSwitcherScreen(
    viewModel: UserSwitcherViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFinishRequested: Boolean by viewModel.isFinishRequested.collectAsState(false)
    val users: List<UserViewModel> by viewModel.users.collectAsState(emptyList())
    val maxUserColumns: Int by viewModel.maximumUserColumns.collectAsState(1)
    val menuActions: List<UserActionViewModel> by viewModel.menu.collectAsState(emptyList())
    val isOpenMenuButtonVisible: Boolean by viewModel.isOpenMenuButtonVisible.collectAsState(false)
    val isMenuVisible: Boolean by viewModel.isMenuVisible.collectAsState(false)

    UserSwitcherScreenStateless(
        isFinishRequested = isFinishRequested,
        users = users,
        maxUserColumns = maxUserColumns,
        menuActions = menuActions,
        isOpenMenuButtonVisible = isOpenMenuButtonVisible,
        isMenuVisible = isMenuVisible,
        onMenuClosed = viewModel::onMenuClosed,
        onOpenMenuButtonClicked = viewModel::onOpenMenuButtonClicked,
        onCancelButtonClicked = viewModel::onCancelButtonClicked,
        onFinished = {
            onFinished()
            viewModel.onFinished()
        },
        modifier = modifier,
    )
}

@Composable
private fun UserSwitcherScreenStateless(
    isFinishRequested: Boolean,
    users: List<UserViewModel>,
    maxUserColumns: Int,
    menuActions: List<UserActionViewModel>,
    isOpenMenuButtonVisible: Boolean,
    isMenuVisible: Boolean,
    onMenuClosed: () -> Unit,
    onOpenMenuButtonClicked: () -> Unit,
    onCancelButtonClicked: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(isFinishRequested) {
        if (isFinishRequested) {
            onFinished()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 60.dp,
                    vertical = 40.dp,
                ),
    ) {
        UserGrid(
            users = users,
            maxUserColumns = maxUserColumns,
            modifier = Modifier.align(Alignment.Center),
        )

        Buttons(
            menuActions = menuActions,
            isOpenMenuButtonVisible = isOpenMenuButtonVisible,
            isMenuVisible = isMenuVisible,
            onMenuClosed = onMenuClosed,
            onOpenMenuButtonClicked = onOpenMenuButtonClicked,
            onCancelButtonClicked = onCancelButtonClicked,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun UserGrid(
    users: List<UserViewModel>,
    maxUserColumns: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(44.dp),
        modifier = modifier,
    ) {
        val rowCount = ceil(users.size / maxUserColumns.toFloat()).toInt()
        (0 until rowCount).forEach { rowIndex ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                modifier = modifier,
            ) {
                val fromIndex = rowIndex * maxUserColumns
                val toIndex = min(users.size, (rowIndex + 1) * maxUserColumns)
                users.subList(fromIndex, toIndex).forEach { user ->
                    UserItem(
                        viewModel = user,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserItem(
    viewModel: UserViewModel,
) {
    val onClicked = viewModel.onClicked
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            if (onClicked != null) {
                    Modifier.clickable { onClicked() }
                } else {
                    Modifier
                }
                .alpha(viewModel.alpha),
    ) {
        Box {
            UserItemBackground(modifier = Modifier.align(Alignment.Center).size(222.dp))

            UserItemIcon(
                image = viewModel.image,
                isSelectionMarkerVisible = viewModel.isSelectionMarkerVisible,
                modifier = Modifier.align(Alignment.Center).size(222.dp)
            )
        }

        // User name
        val text = viewModel.name.load()
        if (text != null) {
            // We use the box to center-align the text vertically as that is not possible with Text
            // alone.
            Box(
                modifier = Modifier.size(width = 222.dp, height = 48.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(com.android.internal.R.color.system_neutral1_50),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun UserItemBackground(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = ColorPainter(LocalAndroidColorScheme.current.colorBackground),
        contentDescription = null,
        modifier = modifier.clip(CircleShape),
    )
}

@Composable
private fun UserItemIcon(
    image: Drawable,
    isSelectionMarkerVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = image.toBitmap().asImageBitmap(),
        contentDescription = null,
        modifier =
            if (isSelectionMarkerVisible) {
                    // Draws a ring
                    modifier.border(
                        width = 8.dp,
                        color = LocalAndroidColorScheme.current.colorAccentPrimary,
                        shape = CircleShape,
                    )
                } else {
                    modifier
                }
                .padding(16.dp)
                .clip(CircleShape)
    )
}

@Composable
private fun Buttons(
    menuActions: List<UserActionViewModel>,
    isOpenMenuButtonVisible: Boolean,
    isMenuVisible: Boolean,
    onMenuClosed: () -> Unit,
    onOpenMenuButtonClicked: () -> Unit,
    onCancelButtonClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
    ) {
        // Cancel button.
        SysUiTextButton(
            onClick = onCancelButtonClicked,
        ) {
            Text(stringResource(R.string.cancel))
        }

        // "Open menu" button.
        if (isOpenMenuButtonVisible) {
            Spacer(modifier = Modifier.width(8.dp))
            // To properly use a DropdownMenu in Compose, we need to wrap the button that opens it
            // and the menu itself in a Box.
            Box {
                SysUiOutlinedButton(
                    onClick = onOpenMenuButtonClicked,
                ) {
                    Text(stringResource(R.string.add))
                }
                Menu(
                    viewModel = menuActions,
                    isMenuVisible = isMenuVisible,
                    onMenuClosed = onMenuClosed,
                )
            }
        }
    }
}

@Composable
private fun Menu(
    viewModel: List<UserActionViewModel>,
    isMenuVisible: Boolean,
    onMenuClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxItemWidth = LocalConfiguration.current.screenWidthDp.dp / 4
    DropdownMenu(
        expanded = isMenuVisible,
        onDismissRequest = onMenuClosed,
        modifier =
            modifier.background(
                color = MaterialTheme.colorScheme.inverseOnSurface,
            ),
    ) {
        viewModel.forEachIndexed { index, action ->
            MenuItem(
                viewModel = action,
                onClicked = { action.onClicked() },
                topPadding =
                    if (index == 0) {
                        16.dp
                    } else {
                        0.dp
                    },
                bottomPadding =
                    if (index == viewModel.size - 1) {
                        16.dp
                    } else {
                        0.dp
                    },
                modifier = Modifier.sizeIn(maxWidth = maxItemWidth),
            )
        }
    }
}

@Composable
private fun MenuItem(
    viewModel: UserActionViewModel,
    onClicked: () -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val icon =
        remember(viewModel.iconResourceId) {
            val drawable =
                checkNotNull(AppCompatResources.getDrawable(context, viewModel.iconResourceId))
            drawable
                .toBitmap(
                    size = with(density) { 20.dp.toPx() }.toInt(),
                    tintColor = Color.White,
                )
                .asImageBitmap()
        }

    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(viewModel.textResourceId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onClicked,
        leadingIcon = {
            Spacer(modifier = Modifier.width(10.dp))
            Image(
                bitmap = icon,
                contentDescription = null,
            )
        },
        modifier =
            modifier
                .heightIn(
                    min = 56.dp,
                )
                .padding(
                    start = 18.dp,
                    end = 65.dp,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
    )
}

/**
 * Converts the [Drawable] to a [Bitmap].
 *
 * Note that this is a relatively memory-heavy operation as it allocates a whole bitmap and draws
 * the `Drawable` onto it. Use sparingly and with care.
 */
private fun Drawable.toBitmap(
    size: Int? = null,
    tintColor: Color? = null,
): Bitmap {
    val bitmap =
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(
                size ?: intrinsicWidth,
                size ?: intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
        }
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    if (tintColor != null) {
        setTint(tintColor.toArgb())
    }
    draw(canvas)
    return bitmap
}
