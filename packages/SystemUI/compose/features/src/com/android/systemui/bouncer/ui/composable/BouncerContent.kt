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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.bouncer.ui.composable

import android.app.AlertDialog
import android.content.DialogInterface
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformButton
import com.android.compose.animation.Easings
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.transitions
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout
import com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerMessageViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerSceneContentViewModel
import com.android.systemui.bouncer.ui.viewmodel.MessageViewModel
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.fold.ui.composable.foldPosture
import com.android.systemui.fold.ui.helper.FoldPosture
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.MotionTestValues
import platform.test.motion.compose.values.motionTestValues

@Composable
fun BouncerContent(
    viewModel: BouncerSceneContentViewModel,
    dialogFactory: BouncerDialogFactory,
    modifier: Modifier = Modifier,
) {
    val isSideBySideSupported by viewModel.isSideBySideSupported.collectAsStateWithLifecycle()
    val layout = calculateLayout(isSideBySideSupported = isSideBySideSupported)

    BouncerContent(layout, viewModel, dialogFactory, modifier)
}

@Composable
@VisibleForTesting
fun BouncerContent(
    layout: BouncerSceneLayout,
    viewModel: BouncerSceneContentViewModel,
    dialogFactory: BouncerDialogFactory,
    modifier: Modifier,
) {
    Box(
        // Allows the content within each of the layouts to react to the appearance and
        // disappearance of the IME, which is also known as the software keyboard.
        //
        // Despite the keyboard only being part of the password bouncer, adding it at this level is
        // both necessary to properly handle the keyboard in all layouts and harmless in cases when
        // the keyboard isn't used (like the PIN or pattern auth methods).
        modifier = modifier.imePadding().onKeyEvent(viewModel::onKeyEvent)
    ) {
        when (layout) {
            BouncerSceneLayout.STANDARD_BOUNCER -> StandardLayout(viewModel = viewModel)
            BouncerSceneLayout.BESIDE_USER_SWITCHER ->
                BesideUserSwitcherLayout(viewModel = viewModel)
            BouncerSceneLayout.BELOW_USER_SWITCHER -> BelowUserSwitcherLayout(viewModel = viewModel)
            BouncerSceneLayout.SPLIT_BOUNCER -> SplitLayout(viewModel = viewModel)
        }

        Dialog(bouncerViewModel = viewModel, dialogFactory = dialogFactory)
    }
}

/**
 * Renders the contents of the actual bouncer UI, the area that takes user input to do an
 * authentication attempt, including all messaging UI (directives, reasoning, errors, etc.).
 */
@Composable
private fun StandardLayout(viewModel: BouncerSceneContentViewModel, modifier: Modifier = Modifier) {
    val isHeightExpanded =
        LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Expanded

    FoldAware(
        modifier = modifier.padding(start = 32.dp, top = 92.dp, end = 32.dp, bottom = 48.dp),
        viewModel = viewModel,
        aboveFold = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusMessage(viewModel = viewModel.message, modifier = Modifier)

                OutputArea(
                    viewModel = viewModel,
                    modifier = Modifier.padding(top = if (isHeightExpanded) 96.dp else 64.dp),
                )
            }
        },
        belowFold = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InputArea(
                        viewModel = viewModel,
                        pinButtonRowVerticalSpacing = 12.dp,
                        centerPatternDotsVertically = false,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                ActionArea(viewModel = viewModel, modifier = Modifier.padding(top = 48.dp))
            }
        },
    )
}

/**
 * Renders the bouncer UI in split mode, with half on one side and half on the other side, swappable
 * by double-tapping on the side.
 */
@Composable
private fun SplitLayout(viewModel: BouncerSceneContentViewModel, modifier: Modifier = Modifier) {
    val authMethod by viewModel.authMethodViewModel.collectAsStateWithLifecycle()

    Row(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(
                    horizontal = 24.dp,
                    vertical = if (authMethod is PasswordBouncerViewModel) 24.dp else 48.dp,
                )
    ) {
        // Left side (in left-to-right locales).
        Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
            when (authMethod) {
                is PinBouncerViewModel -> {
                    StatusMessage(
                        viewModel = viewModel.message,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    OutputArea(
                        viewModel = viewModel,
                        modifier =
                            Modifier.align(Alignment.Center).sysuiResTag("bouncer_text_entry"),
                    )

                    ActionArea(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(top = 48.dp),
                    )
                }
                is PatternBouncerViewModel -> {
                    StatusMessage(
                        viewModel = viewModel.message,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    ActionArea(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(vertical = 48.dp),
                    )
                }
                is PasswordBouncerViewModel -> {
                    ActionArea(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                else -> Unit
            }
        }

        // Right side (in right-to-left locales).
        Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
            when (authMethod) {
                is PinBouncerViewModel,
                is PatternBouncerViewModel -> {
                    InputArea(
                        viewModel = viewModel,
                        pinButtonRowVerticalSpacing = 8.dp,
                        centerPatternDotsVertically = true,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is PasswordBouncerViewModel -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    ) {
                        StatusMessage(viewModel = viewModel.message)
                        OutputArea(
                            viewModel = viewModel,
                            modifier =
                                Modifier.padding(top = 24.dp).sysuiResTag("bouncer_text_entry"),
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * Arranges the bouncer contents and user switcher contents side-by-side, supporting a double tap
 * anywhere on the background to flip their positions.
 */
@Composable
private fun BesideUserSwitcherLayout(
    viewModel: BouncerSceneContentViewModel,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val isLeftToRight = layoutDirection == LayoutDirection.Ltr
    val (isSwapped, setSwapped) = rememberSaveable(isLeftToRight) { mutableStateOf(!isLeftToRight) }
    val isHeightExpanded =
        LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Expanded
    val authMethod by viewModel.authMethodViewModel.collectAsStateWithLifecycle()

    var swapAnimationEnd by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            // Depending on where the user double tapped, switch the elements such
                            // that the non-swapped element is closer to the side that was double
                            // tapped.
                            setSwapped(offset.x < size.width / 2)
                        }
                    )
                }
                .testTag("BesideUserSwitcherLayout")
                .motionTestValues {
                    swapAnimationEnd exportAs BouncerMotionTestKeys.swapAnimationEnd
                }
                .padding(
                    top = if (isHeightExpanded) 128.dp else 96.dp,
                    bottom = if (isHeightExpanded) 128.dp else 48.dp,
                )
    ) {
        LaunchedEffect(isSwapped) { swapAnimationEnd = false }
        val animatedOffset by
            animateFloatAsState(
                targetValue =
                    if (!isSwapped) {
                        // A non-swapped element has its natural placement so it's not offset.
                        0f
                    } else if (isLeftToRight) {
                        // A swapped element has its elements offset horizontally. In the case of
                        // LTR locales, this means pushing the element to the right, hence the
                        // positive number.
                        1f
                    } else {
                        // A swapped element has its elements offset horizontally. In the case of
                        // RTL locales, this means pushing the element to the left, hence the
                        // negative number.
                        -1f
                    },
                label = "offset",
            ) {
                swapAnimationEnd = true
            }

        fun Modifier.swappable(inversed: Boolean = false): Modifier {
            return graphicsLayer {
                    translationX =
                        size.width *
                            animatedOffset *
                            if (inversed) {
                                // A negative sign is used to make sure this is offset in the
                                // direction that's opposite to the direction that the user
                                // switcher is pushed in.
                                -1
                            } else {
                                1
                            }
                    alpha = animatedAlpha(animatedOffset)
                }
                .motionTestValues { animatedAlpha(animatedOffset) exportAs MotionTestValues.alpha }
        }

        UserSwitcher(viewModel = viewModel, modifier = Modifier.weight(1f).swappable())

        FoldAware(
            modifier = Modifier.weight(1f).swappable(inversed = true).testTag("FoldAware"),
            viewModel = viewModel,
            aboveFold = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusMessage(viewModel = viewModel.message)
                    OutputArea(
                        viewModel = viewModel,
                        modifier = Modifier.padding(top = 24.dp).sysuiResTag("bouncer_text_entry"),
                    )
                }
            },
            belowFold = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val isOutputAreaVisible = authMethod !is PatternBouncerViewModel
                    // If there is an output area and the window is not tall enough, spacing needs
                    // to be added between the input and the output areas (otherwise the two get
                    // very squished together).
                    val addSpacingBetweenOutputAndInput = isOutputAreaVisible && !isHeightExpanded

                    Box(
                        modifier =
                            Modifier.weight(1f)
                                .padding(top = (if (addSpacingBetweenOutputAndInput) 24 else 0).dp)
                    ) {
                        InputArea(
                            viewModel = viewModel,
                            pinButtonRowVerticalSpacing = 12.dp,
                            centerPatternDotsVertically = true,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }

                    ActionArea(
                        viewModel = viewModel,
                        modifier = Modifier.padding(top = 48.dp).testTag("ActionArea"),
                    )
                }
            },
        )
    }
}

/** Arranges the bouncer contents and user switcher contents one on top of the other, vertically. */
@Composable
private fun BelowUserSwitcherLayout(
    viewModel: BouncerSceneContentViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 128.dp)) {
        UserSwitcher(viewModel = viewModel, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusMessage(viewModel = viewModel.message)
                OutputArea(viewModel = viewModel, modifier = Modifier.padding(top = 24.dp))

                InputArea(
                    viewModel = viewModel,
                    pinButtonRowVerticalSpacing = 12.dp,
                    centerPatternDotsVertically = true,
                    modifier = Modifier.padding(top = 128.dp),
                )

                ActionArea(viewModel = viewModel, modifier = Modifier.padding(top = 48.dp))
            }
        }
    }
}

@Composable
private fun FoldAware(
    viewModel: BouncerSceneContentViewModel,
    aboveFold: @Composable BoxScope.() -> Unit,
    belowFold: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val foldPosture: FoldPosture by foldPosture()
    val isSplitAroundTheFoldRequired by viewModel.isFoldSplitRequired.collectAsStateWithLifecycle()
    val isSplitAroundTheFold = foldPosture == FoldPosture.Tabletop && isSplitAroundTheFoldRequired
    val currentSceneKey =
        if (isSplitAroundTheFold) SceneKeys.SplitSceneKey else SceneKeys.ContiguousSceneKey

    val state = remember { MutableSceneTransitionLayoutState(currentSceneKey, SceneTransitions) }

    // Update state whenever currentSceneKey has changed.
    LaunchedEffect(state, currentSceneKey) {
        if (currentSceneKey != state.transitionState.currentScene) {
            state.setTargetScene(currentSceneKey, animationScope = this)
        }
    }

    SceneTransitionLayout(state, modifier = modifier) {
        scene(SceneKeys.ContiguousSceneKey) {
            FoldableScene(aboveFold = aboveFold, belowFold = belowFold, isSplit = false)
        }

        scene(SceneKeys.SplitSceneKey) {
            FoldableScene(aboveFold = aboveFold, belowFold = belowFold, isSplit = true)
        }
    }
}

@Composable
private fun SceneScope.FoldableScene(
    aboveFold: @Composable BoxScope.() -> Unit,
    belowFold: @Composable BoxScope.() -> Unit,
    isSplit: Boolean,
    modifier: Modifier = Modifier,
) {
    val splitRatio =
        LocalContext.current.resources.getFloat(
            R.dimen.motion_layout_half_fold_bouncer_height_ratio
        )

    Column(modifier = modifier.fillMaxHeight()) {
        // Content above the fold, when split on a foldable device in a "table top" posture:
        Box(
            modifier =
                Modifier.element(SceneElements.AboveFold)
                    .then(
                        if (isSplit) {
                            Modifier.weight(splitRatio)
                        } else {
                            Modifier
                        }
                    )
        ) {
            aboveFold()
        }

        // Content below the fold, when split on a foldable device in a "table top" posture:
        Box(
            modifier =
                Modifier.element(SceneElements.BelowFold)
                    .weight(
                        if (isSplit) {
                            1 - splitRatio
                        } else {
                            1f
                        }
                    )
        ) {
            belowFold()
        }
    }
}

@Composable
private fun StatusMessage(viewModel: BouncerMessageViewModel, modifier: Modifier = Modifier) {
    val message: MessageViewModel? by viewModel.message.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.onShown()
        onDispose {}
    }

    Crossfade(
        targetState = message,
        label = "Bouncer message",
        animationSpec = if (message?.isUpdateAnimated == true) tween() else snap(),
        modifier = modifier.fillMaxWidth(),
    ) { msg ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            msg?.let {
                Text(
                    text = it.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = it.secondaryText ?: "",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * Renders the user output area, where the user sees what they entered.
 *
 * For example, this can be the PIN shapes or password text field.
 */
@Composable
private fun OutputArea(viewModel: BouncerSceneContentViewModel, modifier: Modifier = Modifier) {
    val authMethodViewModel: AuthMethodBouncerViewModel? by
        viewModel.authMethodViewModel.collectAsStateWithLifecycle()
    when (val nonNullViewModel = authMethodViewModel) {
        is PinBouncerViewModel ->
            PinInputDisplay(
                viewModel = nonNullViewModel,
                modifier = modifier.sysuiResTag("bouncer_text_entry"),
            )
        is PasswordBouncerViewModel ->
            PasswordBouncer(
                viewModel = nonNullViewModel,
                modifier = modifier.sysuiResTag("bouncer_text_entry"),
            )
        else -> Unit
    }
}

/**
 * Renders the user input area, where the user enters their credentials.
 *
 * For example, this can be the pattern input area or the PIN pad.
 */
@Composable
private fun InputArea(
    viewModel: BouncerSceneContentViewModel,
    pinButtonRowVerticalSpacing: Dp,
    centerPatternDotsVertically: Boolean,
    modifier: Modifier = Modifier,
) {
    val authMethodViewModel: AuthMethodBouncerViewModel? by
        viewModel.authMethodViewModel.collectAsStateWithLifecycle()

    when (val nonNullViewModel = authMethodViewModel) {
        is PinBouncerViewModel -> {
            PinPad(
                viewModel = nonNullViewModel,
                verticalSpacing = pinButtonRowVerticalSpacing,
                modifier = modifier,
            )
        }
        is PatternBouncerViewModel -> {
            PatternBouncer(
                viewModel = nonNullViewModel,
                centerDotsVertically = centerPatternDotsVertically,
                modifier = modifier,
            )
        }
        else -> Unit
    }
}

@Composable
private fun ActionArea(viewModel: BouncerSceneContentViewModel, modifier: Modifier = Modifier) {
    val actionButton: BouncerActionButtonModel? by
        viewModel.actionButton.collectAsStateWithLifecycle()
    val appearFadeInAnimatable = remember { Animatable(0f) }
    val appearMoveAnimatable = remember { Animatable(0f) }
    val appearAnimationInitialOffset = with(LocalDensity.current) { 80.dp.toPx() }

    actionButton?.let { actionButtonViewModel ->
        LaunchedEffect(Unit) {
            appearFadeInAnimatable.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        durationMillis = 450,
                        delayMillis = 133,
                        easing = Easings.LegacyDecelerate,
                    ),
            )
        }
        LaunchedEffect(Unit) {
            appearMoveAnimatable.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        durationMillis = 450,
                        delayMillis = 133,
                        easing = Easings.StandardDecelerate,
                    ),
            )
        }

        Box(
            modifier =
                modifier
                    .graphicsLayer {
                        // Translate the button up from an initially pushed-down position:
                        translationY =
                            (1 - appearMoveAnimatable.value) * appearAnimationInitialOffset
                        // Fade the button in:
                        alpha = appearFadeInAnimatable.value
                    }
                    .height(56.dp)
                    .clip(ButtonDefaults.shape)
                    .background(color = MaterialTheme.colorScheme.tertiaryContainer)
                    .semantics { role = Role.Button }
                    .combinedClickable(
                        onClick = { actionButtonViewModel.onClick() },
                        onLongClick = actionButtonViewModel.onLongClick?.let { { it.invoke() } },
                    )
        ) {
            Text(
                text = actionButtonViewModel.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.align(Alignment.Center).padding(ButtonDefaults.ContentPadding),
            )
        }
    }
}

@Composable
private fun Dialog(
    bouncerViewModel: BouncerSceneContentViewModel,
    dialogFactory: BouncerDialogFactory,
) {
    val dialogViewModel by bouncerViewModel.dialogViewModel.collectAsStateWithLifecycle()
    var dialog: AlertDialog? by remember { mutableStateOf(null) }

    dialogViewModel?.let { viewModel ->
        if (dialog == null) {
            dialog = dialogFactory()
        }
        dialog?.apply {
            setMessage(viewModel.text)
            setButton(DialogInterface.BUTTON_NEUTRAL, context.getString(R.string.ok)) { _, _ ->
                viewModel.onDismiss()
            }
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
    }
        ?: {
            dialog?.dismiss()
            dialog = null
        }
}

/** Renders the UI of the user switcher that's displayed on large screens next to the bouncer UI. */
@Composable
private fun UserSwitcher(viewModel: BouncerSceneContentViewModel, modifier: Modifier = Modifier) {
    if (!viewModel.isUserSwitcherVisible) {
        // Take up the same space as the user switcher normally would, but with nothing inside it.
        Box(modifier = modifier)
        return
    }

    val selectedUserImage by viewModel.selectedUserImage.collectAsStateWithLifecycle(null)
    val dropdownItems by viewModel.userSwitcherDropdown.collectAsStateWithLifecycle(emptyList())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.sysuiResTag("UserSwitcher"),
    ) {
        selectedUserImage?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(SelectedUserImageSize).sysuiResTag("user_icon"),
            )
        }

        val (isDropdownExpanded, setDropdownExpanded) = remember { mutableStateOf(false) }

        dropdownItems.firstOrNull()?.let { firstDropdownItem ->
            Spacer(modifier = Modifier.height(40.dp))

            Box {
                PlatformButton(
                    modifier =
                        Modifier
                            // Remove the built-in padding applied inside PlatformButton:
                            .padding(vertical = 0.dp)
                            .width(UserSwitcherDropdownWidth)
                            .height(UserSwitcherDropdownHeight),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    onClick = { setDropdownExpanded(!isDropdownExpanded) },
                ) {
                    val context = LocalContext.current
                    Text(
                        text = checkNotNull(firstDropdownItem.text.loadText(context)),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).sysuiResTag("user_switcher_anchor"),
                    )
                }

                UserSwitcherDropdownMenu(
                    isExpanded = isDropdownExpanded,
                    items = dropdownItems,
                    onDismissed = { setDropdownExpanded(false) },
                )
            }
        }
    }
}

/**
 * Renders the dropdown menu that displays the actual users and/or user actions that can be
 * selected.
 */
@Composable
private fun UserSwitcherDropdownMenu(
    isExpanded: Boolean,
    items: List<BouncerSceneContentViewModel.UserSwitcherDropdownItemViewModel>,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current

    // TODO(b/303071855): once the FR is fixed, remove this composition local override.
    MaterialTheme(
        colorScheme =
            MaterialTheme.colorScheme.copy(
                surface = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(28.dp)),
    ) {
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = onDismissed,
            offset = DpOffset(x = 0.dp, y = -UserSwitcherDropdownHeight),
            modifier = Modifier.width(UserSwitcherDropdownWidth).sysuiResTag("user_list_dropdown"),
        ) {
            items.forEach { userSwitcherDropdownItem ->
                DropdownMenuItem(
                    modifier = Modifier.sysuiResTag("user_switcher_item"),
                    leadingIcon = {
                        Icon(
                            icon = userSwitcherDropdownItem.icon,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    text = {
                        Text(
                            text = checkNotNull(userSwitcherDropdownItem.text.loadText(context)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onDismissed()
                        userSwitcherDropdownItem.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Calculates an alpha for the user switcher and bouncer such that it's at `1` when the offset of
 * the two reaches a stopping point but `0` in the middle of the transition.
 */
private fun animatedAlpha(offset: Float): Float {
    // Describes a curve that is made of two parabolic U-shaped curves mirrored horizontally around
    // the y-axis. The U on the left runs between x = -1 and x = 0 while the U on the right runs
    // between x = 0 and x = 1.
    //
    // The minimum values of the curves are at -0.5 and +0.5.
    //
    // Both U curves are vertically scaled such that they reach the points (-1, 1) and (1, 1).
    //
    // Breaking it down, it's y = a×(|x|-m)²+b, where:
    // x: the offset
    // y: the alpha
    // m: x-axis center of the parabolic curves, where the minima are.
    // b: y-axis offset to apply to the entire curve so the animation spends more time with alpha =
    // 0.
    // a: amplitude to scale the parabolic curves to reach y = 1 at x = -1, x = 0, and x = +1.
    val m = 0.5f
    val b = -0.25
    val a = (1 - b) / m.pow(2)

    return max(0f, (a * (abs(offset) - m).pow(2) + b).toFloat())
}

private val SelectedUserImageSize = 190.dp
private val UserSwitcherDropdownWidth = SelectedUserImageSize + 2 * 29.dp
private val UserSwitcherDropdownHeight = 60.dp

private object SceneKeys {
    val ContiguousSceneKey = SceneKey("default")
    val SplitSceneKey = SceneKey("split")
}

private object SceneElements {
    val AboveFold = ElementKey("above_fold")
    val BelowFold = ElementKey("below_fold")
}

private val SceneTransitions = transitions {
    from(SceneKeys.ContiguousSceneKey, to = SceneKeys.SplitSceneKey) { spec = tween() }
}

@VisibleForTesting
object BouncerMotionTestKeys {
    val swapAnimationEnd = MotionTestValueKey<Boolean>("swapAnimationEnd")
}
