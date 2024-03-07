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

@file:OptIn(ExperimentalAnimationGraphicsApi::class)

package com.android.systemui.bouncer.ui.composable

import android.app.AlertDialog
import android.app.Dialog
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.android.compose.PlatformOutlinedButton
import com.android.compose.animation.Easings
import com.android.keyguard.PinShapeAdapter
import com.android.systemui.bouncer.ui.viewmodel.EntryToken.Digit
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinInputViewModel
import com.android.systemui.res.R
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@Composable
fun PinInputDisplay(
    viewModel: PinBouncerViewModel,
    modifier: Modifier = Modifier,
) {
    val hintedPinLength: Int? by viewModel.hintedPinLength.collectAsState()
    val shapeAnimations = rememberShapeAnimations(viewModel.pinShapes)

    // The display comes in two different flavors:
    // 1) hinting: shows a circle (◦) per expected pin input, and dot (●) per entered digit.
    //    This has a fixed width, and uses two distinct types of AVDs to animate the addition and
    //    removal of digits.
    // 2) regular, shows a dot (●) per entered digit.
    //    This grows/shrinks as digits are added deleted. Uses the same type of AVDs to animate the
    //    addition of digits, but simply center-shrinks the dot (●) shape to zero to animate the
    //    removal.
    // Because of all these differences, there are two separate implementations, rather than
    // unifying into a single, more complex implementation.

    when (val length = hintedPinLength) {
        null -> RegularPinInputDisplay(viewModel, shapeAnimations, modifier)
        else -> HintingPinInputDisplay(viewModel, shapeAnimations, length, modifier)
    }
}

/**
 * A pin input display that shows a placeholder circle (◦) for every digit in the pin not yet
 * entered.
 *
 * Used for auto-confirmed pins of a specific length, see design: http://shortn/_jS8kPzQ7QV
 */
@Composable
private fun HintingPinInputDisplay(
    viewModel: PinBouncerViewModel,
    shapeAnimations: ShapeAnimations,
    hintedPinLength: Int,
    modifier: Modifier = Modifier,
) {
    val pinInput: PinInputViewModel by viewModel.pinInput.collectAsState()
    // [ClearAll] marker pointing at the beginning of the current pin input.
    // When a new [ClearAll] token is added to the [pinInput], the clear-all animation is played
    // and the marker is advanced manually to the most recent marker. See LaunchedEffect below.
    var currentClearAll by remember { mutableStateOf(pinInput.mostRecentClearAll()) }
    // The length of the pin currently entered by the user.
    val currentPinLength = pinInput.getDigits(currentClearAll).size

    // The animated vector drawables for each of the [hintedPinLength] slots.
    // The first [currentPinLength] drawables end in a dot (●) shape, the remaining drawables up to
    // [hintedPinLength] end in the circle (◦) shape.
    // This list is re-generated upon each pin entry, it is modelled as a  [MutableStateList] to
    // allow the clear-all animation to replace the shapes asynchronously, see LaunchedEffect below.
    // Note that when a [ClearAll] token is added to the input (and the clear-all animation plays)
    // the [currentPinLength] does not change; the [pinEntryDrawable] is remembered until the
    // clear-all animation finishes and the [currentClearAll] state is manually advanced.
    val pinEntryDrawable =
        remember(currentPinLength) {
            buildList {
                    repeat(currentPinLength) { add(shapeAnimations.getShapeToDot(it)) }
                    repeat(hintedPinLength - currentPinLength) { add(shapeAnimations.dotToCircle) }
                }
                .toMutableStateList()
        }

    val mostRecentClearAll = pinInput.mostRecentClearAll()
    // Whenever a new [ClearAll] marker is added to the input, the clear-all animation needs to
    // be played.
    LaunchedEffect(mostRecentClearAll) {
        if (currentClearAll == mostRecentClearAll) {
            // Except during the initial composition.
            return@LaunchedEffect
        }

        // Staggered replace of all dot (●) shapes with an animation from dot (●) to circle (◦).
        for (index in 0 until hintedPinLength) {
            if (!shapeAnimations.isDotShape(pinEntryDrawable[index])) break

            pinEntryDrawable[index] = shapeAnimations.dotToCircle
            delay(shapeAnimations.dismissStaggerDelay)
        }

        // Once the animation is done, start processing the next pin input again.
        currentClearAll = mostRecentClearAll
    }

    // During the initial composition, do not play the [pinEntryDrawable] animations. This prevents
    // the dot (●) to circle (◦) animation when the empty display becomes first visible, and a
    // superfluous shape to dot (●)  animation after for example device rotation.
    var playAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { playAnimation = true }

    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier.heightIn(min = shapeAnimations.shapeSize)) {
        pinEntryDrawable.forEachIndexed { index, drawable ->
            // Key the loop by [index] and [drawable], so that updating a shape drawable at the same
            // index will play the new animation (by remembering a new [atEnd]).
            key(index, drawable) {
                // [rememberAnimatedVectorPainter] requires a `atEnd` boolean to switch from `false`
                // to `true` for the animation to play. This animation is suppressed when
                // playAnimation is false, always rendering the end-state of the animation.
                var atEnd by remember { mutableStateOf(!playAnimation) }
                LaunchedEffect(Unit) { atEnd = true }

                Image(
                    painter = rememberAnimatedVectorPainter(drawable, atEnd),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(dotColor),
                )
            }
        }
    }
}

/**
 * A pin input that shows a dot (●) for each entered pin, horizontally centered and growing /
 * shrinking as more digits are entered and deleted.
 *
 * Used for pin input when the pin length is not hinted, see design http://shortn/_wNP7SrBD78
 */
@Composable
private fun RegularPinInputDisplay(
    viewModel: PinBouncerViewModel,
    shapeAnimations: ShapeAnimations,
    modifier: Modifier = Modifier,
) {
    if (viewModel.isSimAreaVisible) {
        SimArea(viewModel = viewModel)
    }

    // Holds all currently [VisiblePinEntry] composables. This cannot be simply derived from
    // `viewModel.pinInput` at composition, since deleting a pin entry needs to play a remove
    // animation, thus the composable to be removed has to remain in the composition until fully
    // disappeared (see `prune` launched effect below)
    val pinInputRow = remember(shapeAnimations) { PinInputRow(shapeAnimations) }

    // Processed `viewModel.pinInput` updates and applies them to [pinDigitShapes]
    LaunchedEffect(viewModel.pinInput, pinInputRow) {
        // Initial setup: capture the most recent [ClearAll] marker and create the visuals for the
        // existing digits (if any) without animation..
        var currentClearAll =
            with(viewModel.pinInput.value) {
                val initialClearAll = mostRecentClearAll()
                pinInputRow.setDigits(getDigits(initialClearAll))
                initialClearAll
            }

        viewModel.pinInput.collect { input ->
            // Process additions and removals of pins within the current input block.
            pinInputRow.updateDigits(input.getDigits(currentClearAll), scope = this@LaunchedEffect)

            val mostRecentClearAll = input.mostRecentClearAll()
            if (currentClearAll != mostRecentClearAll) {
                // A new [ClearAll] token is added to the [input], play the clear-all animation
                pinInputRow.playClearAllAnimation()

                // Animation finished, advance manually to the next marker.
                currentClearAll = mostRecentClearAll
            }
        }
    }

    LaunchedEffect(pinInputRow) {
        // Prunes unused VisiblePinEntries once they are no longer visible.
        snapshotFlow { pinInputRow.hasUnusedEntries() }
            .collect { hasUnusedEntries ->
                if (hasUnusedEntries) {
                    pinInputRow.prune()
                }
            }
    }

    pinInputRow.Content(modifier)
}

@Composable
private fun SimArea(viewModel: PinBouncerViewModel) {
    val isLockedEsim by viewModel.isLockedEsim.collectAsState()
    val isSimUnlockingDialogVisible by viewModel.isSimUnlockingDialogVisible.collectAsState()
    val errorDialogMessage by viewModel.errorDialogMessage.collectAsState()
    var unlockDialog: Dialog? by remember { mutableStateOf(null) }
    var errorDialog: Dialog? by remember { mutableStateOf(null) }
    val context = LocalView.current.context

    DisposableEffect(isSimUnlockingDialogVisible) {
        if (isSimUnlockingDialogVisible) {
            val builder =
                AlertDialog.Builder(context).apply {
                    setMessage(context.getString(R.string.kg_sim_unlock_progress_dialog_message))
                    setCancelable(false)
                }
            unlockDialog =
                builder.create().apply {
                    window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
                    show()
                    findViewById<TextView>(android.R.id.message)?.gravity = Gravity.CENTER
                }
        } else {
            unlockDialog?.hide()
            unlockDialog = null
        }

        onDispose {
            unlockDialog?.hide()
            unlockDialog = null
        }
    }

    DisposableEffect(errorDialogMessage) {
        if (errorDialogMessage != null) {
            val builder = AlertDialog.Builder(context)
            builder.setMessage(errorDialogMessage)
            builder.setCancelable(false)
            builder.setNeutralButton(R.string.ok, null)
            errorDialog =
                builder.create().apply {
                    window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
                    setOnDismissListener { viewModel.onErrorDialogDismissed() }
                    show()
                }
        } else {
            errorDialog?.hide()
            errorDialog = null
        }

        onDispose {
            errorDialog?.hide()
            errorDialog = null
        }
    }

    Box(modifier = Modifier.padding(bottom = 20.dp)) {
        // If isLockedEsim is null, then we do not show anything.
        if (isLockedEsim == true) {
            PlatformOutlinedButton(
                onClick = { viewModel.onDisableEsimButtonClicked() },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_no_sim),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                    )
                    Text(
                        text = stringResource(R.string.disable_carrier_button_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else if (isLockedEsim == false) {
            Image(
                painter = painterResource(id = R.drawable.ic_lockscreen_sim),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorResource(id = R.color.background_protected))
            )
        }
    }
}

private class PinInputRow(
    val shapeAnimations: ShapeAnimations,
) {
    private val entries = mutableStateListOf<PinInputEntry>()

    @Composable
    fun Content(modifier: Modifier) {
        Row(
            modifier =
                modifier
                    .heightIn(min = shapeAnimations.shapeSize)
                    // Pins overflowing horizontally should still be shown as scrolling.
                    .wrapContentSize(unbounded = true),
        ) {
            entries.forEach { entry -> key(entry.digit) { entry.Content() } }
        }
    }

    /**
     * Replaces all current [PinInputEntry] composables with new instances for each digit.
     *
     * Does not play the entry expansion animation.
     */
    fun setDigits(digits: List<Digit>) {
        entries.clear()
        entries.addAll(digits.map { PinInputEntry(it, shapeAnimations) })
    }

    /**
     * Adds [PinInputEntry] composables for new digits and plays an entry animation, and starts the
     * exit animation for digits not in [updated] anymore.
     *
     * The function return immediately, playing the animations in the background.
     *
     * Removed entries have to be [prune]d once the exit animation completes, [hasUnusedEntries] can
     * be used in a [SnapshotFlow] to discover when its time to do so.
     */
    fun updateDigits(updated: List<Digit>, scope: CoroutineScope) {
        val incoming = updated.minus(entries.map { it.digit }.toSet()).toList()
        val outgoing = entries.filterNot { entry -> updated.any { entry.digit == it } }.toList()

        entries.addAll(
            incoming.map {
                PinInputEntry(it, shapeAnimations).apply { scope.launch { animateAppearance() } }
            }
        )

        outgoing.forEach { entry -> scope.launch { entry.animateRemoval() } }

        entries.sortWith(compareBy { it.digit })
    }

    /**
     * Plays a staggered remove animation, and upon completion removes the [PinInputEntry]
     * composables.
     *
     * This function returns once the animation finished playing and the entries are removed.
     */
    suspend fun playClearAllAnimation() = coroutineScope {
        val entriesToRemove = entries.toList()
        entriesToRemove
            .mapIndexed { index, entry ->
                launch {
                    delay(shapeAnimations.dismissStaggerDelay * index)
                    entry.animateClearAllCollapse()
                }
            }
            .joinAll()

        // Remove all [PinInputEntry] composables for which the staggered remove animation was
        // played. Note that up to now, each PinInputEntry still occupied the full width.
        entries.removeAll(entriesToRemove)
    }

    /**
     * Whether there are [PinInputEntry] that can be removed from the composition since they were
     * fully animated out.
     */
    fun hasUnusedEntries(): Boolean {
        return entries.any { it.isUnused }
    }

    /** Remove all no longer visible [PinInputEntry]s from the composition. */
    fun prune() {
        entries.removeAll { it.isUnused }
    }
}

private class PinInputEntry(
    val digit: Digit,
    val shapeAnimations: ShapeAnimations,
) {
    private val shape = shapeAnimations.getShapeToDot(digit.sequenceNumber)
    // horizontal space occupied, used to shift contents as individual digits are animated in/out
    private val entryWidth =
        Animatable(shapeAnimations.shapeSize, Dp.VectorConverter, label = "Width of pin ($digit)")
    // intrinsic width and height of the shape, used to collapse the shape during exit animations.
    private val shapeSize =
        Animatable(shapeAnimations.shapeSize, Dp.VectorConverter, label = "Size of pin ($digit)")

    /**
     * Whether the is fully animated out. When `true`, removing this from the composable won't have
     * visual effects.
     */
    val isUnused: Boolean
        get() {
            return entryWidth.targetValue == 0.dp && !entryWidth.isRunning
        }

    /** Animate the shape appearance by growing the entry width from 0.dp to the intrinsic width. */
    suspend fun animateAppearance() = coroutineScope {
        entryWidth.snapTo(0.dp)
        entryWidth.animateTo(shapeAnimations.shapeSize, shapeAnimations.inputShiftAnimationSpec)
    }

    /**
     * Animates shape disappearance by collapsing the shape and occupied horizontal space.
     *
     * Once complete, [isUnused] will return `true`.
     */
    suspend fun animateRemoval() = coroutineScope {
        awaitAll(
            async { entryWidth.animateTo(0.dp, shapeAnimations.inputShiftAnimationSpec) },
            async { shapeSize.animateTo(0.dp, shapeAnimations.deleteShapeSizeAnimationSpec) }
        )
    }

    /** Collapses the shape in place, while still holding on to the horizontal space. */
    suspend fun animateClearAllCollapse() = coroutineScope {
        shapeSize.animateTo(0.dp, shapeAnimations.clearAllShapeSizeAnimationSpec)
    }

    @Composable
    fun Content() {
        val animatedShapeSize by shapeSize.asState()
        val animatedEntryWidth by entryWidth.asState()

        val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
        val shapeHeight = shapeAnimations.shapeSize
        var atEnd by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { atEnd = true }
        Image(
            painter = rememberAnimatedVectorPainter(shape, atEnd),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(dotColor),
            modifier =
                Modifier.layout { measurable, _ ->
                    val shapeSizePx = animatedShapeSize.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(shapeSizePx, shapeSizePx))

                    layout(animatedEntryWidth.roundToPx(), shapeHeight.roundToPx()) {
                        placeable.place(
                            ((animatedEntryWidth - animatedShapeSize) / 2f).roundToPx(),
                            ((shapeHeight - animatedShapeSize) / 2f).roundToPx()
                        )
                    }
                },
        )
    }
}

/** Animated Vector Drawables used to render the pin input. */
private class ShapeAnimations(
    /** Width and height for all the animation images listed here. */
    val shapeSize: Dp,
    /** Transitions from the dot (●) to the circle (◦). Used for the hinting pin input only. */
    val dotToCircle: AnimatedImageVector,
    /** Each of the animations transition from nothing via a shape to the dot (●). */
    private val shapesToDot: List<AnimatedImageVector>,
) {
    /**
     * Returns a transition from nothing via shape to the dot (●)., specific to the input position.
     */
    fun getShapeToDot(position: Int): AnimatedImageVector {
        return shapesToDot[position.mod(shapesToDot.size)]
    }

    /**
     * Whether the [shapeAnimation] is a image returned by [getShapeToDot], and thus is ending in
     * the dot (●) shape.
     *
     * `false` if the shape's end state is the circle (◦).
     */
    fun isDotShape(shapeAnimation: AnimatedImageVector): Boolean {
        return shapeAnimation != dotToCircle
    }

    // spec: http://shortn/_DEhE3Xl2bi
    val dismissStaggerDelay = 33.milliseconds
    val inputShiftAnimationSpec = tween<Dp>(durationMillis = 250, easing = Easings.Standard)
    val deleteShapeSizeAnimationSpec =
        tween<Dp>(durationMillis = 200, easing = Easings.StandardDecelerate)
    val clearAllShapeSizeAnimationSpec = tween<Dp>(durationMillis = 450, easing = Easings.Legacy)
}

@Composable
private fun rememberShapeAnimations(pinShapes: PinShapeAdapter): ShapeAnimations {
    // NOTE: `animatedVectorResource` does remember the returned AnimatedImageVector.
    val dotToCircle = AnimatedImageVector.animatedVectorResource(R.drawable.pin_dot_delete_avd)
    val shapesToDot = pinShapes.shapes.map { AnimatedImageVector.animatedVectorResource(it) }
    val shapeSize = dimensionResource(R.dimen.password_shape_size)

    return remember(dotToCircle, shapesToDot, shapeSize) {
        ShapeAnimations(shapeSize, dotToCircle, shapesToDot)
    }
}
