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

package com.android.systemui.qs.footer.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.modifiers.background
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.theme.colorAttr
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsForegroundServicesButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsSecurityButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/** The Quick Settings footer actions row. */
@Composable
fun FooterActions(
    viewModel: FooterActionsViewModel,
    qsVisibilityLifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Collect alphas as soon as we are composed, even when not visible.
    val alpha by viewModel.alpha.collectAsState()
    val backgroundAlpha = viewModel.backgroundAlpha.collectAsState()

    var security by remember { mutableStateOf<FooterActionsSecurityButtonViewModel?>(null) }
    var foregroundServices by remember {
        mutableStateOf<FooterActionsForegroundServicesButtonViewModel?>(null)
    }
    var userSwitcher by remember { mutableStateOf<FooterActionsButtonViewModel?>(null) }

    LaunchedEffect(
        context,
        qsVisibilityLifecycleOwner,
        viewModel,
        viewModel.security,
        viewModel.foregroundServices,
        viewModel.userSwitcher,
    ) {
        launch {
            // Listen for dialog requests as soon as we are composed, even when not visible.
            viewModel.observeDeviceMonitoringDialogRequests(context)
        }

        // Listen for model changes only when QS are visible.
        qsVisibilityLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { viewModel.security.collect { security = it } }
            launch { viewModel.foregroundServices.collect { foregroundServices = it } }
            launch { viewModel.userSwitcher.collect { userSwitcher = it } }
        }
    }

    val backgroundColor = colorAttr(R.attr.underSurface)
    val contentColor = LocalAndroidColorScheme.current.onSurface
    val backgroundTopRadius = dimensionResource(R.dimen.qs_corner_radius)
    val backgroundModifier =
        remember(
            backgroundColor,
            backgroundAlpha,
            backgroundTopRadius,
        ) {
            Modifier.background(
                backgroundColor,
                backgroundAlpha::value,
                RoundedCornerShape(topStart = backgroundTopRadius, topEnd = backgroundTopRadius),
            )
        }

    val horizontalPadding = dimensionResource(R.dimen.qs_content_horizontal_padding)
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .then(backgroundModifier)
            .padding(
                top = dimensionResource(R.dimen.qs_footer_actions_top_padding),
                bottom = dimensionResource(R.dimen.qs_footer_actions_bottom_padding),
                start = horizontalPadding,
                end = horizontalPadding,
            )
            .layout { measurable, constraints ->
                // All buttons have a 4dp padding to increase their touch size. To be consistent
                // with the View implementation, we want to left-most and right-most buttons to be
                // visually aligned with the left and right sides of this row. So we let this
                // component be 2*4dp wider and then offset it by -4dp to the start.
                val inset = 4.dp.roundToPx()
                val additionalWidth = inset * 2
                val newConstraints =
                    if (constraints.hasBoundedWidth) {
                        constraints.copy(maxWidth = constraints.maxWidth + additionalWidth)
                    } else {
                        constraints
                    }
                val placeable = measurable.measure(newConstraints)

                val width = constraints.constrainWidth(placeable.width - additionalWidth)
                val height = constraints.constrainHeight(placeable.height)
                layout(width, height) { placeable.place(-inset, 0) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
        ) {
            if (security == null && foregroundServices == null) {
                Spacer(Modifier.weight(1f))
            }

            security?.let { SecurityButton(it, Modifier.weight(1f)) }
            foregroundServices?.let { ForegroundServicesButton(it) }
            userSwitcher?.let { IconButton(it, Modifier.sysuiResTag("multi_user_switch")) }
            IconButton(viewModel.settings, Modifier.sysuiResTag("settings_button_container"))
            viewModel.power?.let { IconButton(it, Modifier.sysuiResTag("pm_lite")) }
        }
    }
}

/** The security button. */
@Composable
private fun SecurityButton(
    model: FooterActionsSecurityButtonViewModel,
    modifier: Modifier = Modifier,
) {
    val onClick: ((Expandable) -> Unit)? =
        model.onClick?.let { onClick ->
            val context = LocalContext.current
            { expandable -> onClick(context, expandable) }
        }

    TextButton(
        model.icon,
        model.text,
        showNewDot = false,
        onClick = onClick,
        modifier,
    )
}

/** The foreground services button. */
@Composable
private fun RowScope.ForegroundServicesButton(
    model: FooterActionsForegroundServicesButtonViewModel,
) {
    if (model.displayText) {
        TextButton(
            Icon.Resource(R.drawable.ic_info_outline, contentDescription = null),
            model.text,
            showNewDot = model.hasNewChanges,
            onClick = model.onClick,
            Modifier.weight(1f),
        )
    } else {
        NumberButton(
            model.foregroundServicesCount,
            showNewDot = model.hasNewChanges,
            onClick = model.onClick,
        )
    }
}

/** A button with an icon. */
@Composable
private fun IconButton(
    model: FooterActionsButtonViewModel,
    modifier: Modifier = Modifier,
) {
    Expandable(
        color = colorAttr(model.backgroundColor),
        shape = CircleShape,
        onClick = model.onClick,
        modifier = modifier,
    ) {
        val tint = model.iconTint?.let { Color(it) } ?: Color.Unspecified
        Icon(
            model.icon,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A button with a number an an optional dot (to indicate new changes). */
@Composable
private fun NumberButton(
    number: Int,
    showNewDot: Boolean,
    onClick: (Expandable) -> Unit,
    modifier: Modifier = Modifier,
) {
    // By default Expandable will show a ripple above its content when clicked, and clip the content
    // with the shape of the expandable. In this case we also want to show a "new changes dot"
    // outside of the shape, so we can't clip. To work around that we can pass our own interaction
    // source and draw the ripple indication ourselves above the text but below the "new changes
    // dot".
    val interactionSource = remember { MutableInteractionSource() }

    Expandable(
        color = colorAttr(R.attr.shadeInactive),
        shape = CircleShape,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Box(Modifier.size(40.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .clip(CircleShape)
                    .indication(
                        interactionSource,
                        LocalIndication.current,
                    )
            ) {
                Text(
                    number.toString(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorAttr(R.attr.onShadeInactiveVariant),
                    // TODO(b/242040009): This should only use a standard text style instead and
                    // should not override the text size.
                    fontSize = 18.sp,
                )
            }

            if (showNewDot) {
                NewChangesDot(Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** A dot that indicates new changes. */
@Composable
private fun NewChangesDot(modifier: Modifier = Modifier) {
    val contentDescription = stringResource(R.string.fgs_dot_content_description)
    val color = LocalAndroidColorScheme.current.tertiary

    Canvas(modifier.size(12.dp).semantics { this.contentDescription = contentDescription }) {
        drawCircle(color)
    }
}

/** A larger button with an icon, some text and an optional dot (to indicate new changes). */
@Composable
private fun TextButton(
    icon: Icon,
    text: String,
    showNewDot: Boolean,
    onClick: ((Expandable) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Expandable(
        shape = CircleShape,
        color = colorAttr(R.attr.underSurface),
        contentColor = LocalAndroidColorScheme.current.onSurfaceVariant,
        borderStroke = BorderStroke(1.dp, colorAttr(R.attr.shadeInactive)),
        modifier = modifier.padding(horizontal = 4.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = dimensionResource(R.dimen.qs_footer_padding)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, Modifier.padding(end = 12.dp).size(20.dp))

            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                // TODO(b/242040009): Remove this letter spacing. We should only use the M3 text
                // styles without modifying them.
                letterSpacing = 0.01.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (showNewDot) {
                NewChangesDot(Modifier.padding(start = 8.dp))
            }

            if (onClick != null) {
                Icon(
                    painterResource(com.android.internal.R.drawable.ic_chevron_end),
                    contentDescription = null,
                    Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
        }
    }
}
