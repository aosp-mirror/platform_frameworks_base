/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.ColorRes
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import android.util.MathUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.graphics.ColorUtils
import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.concurrency.DelayableExecutor
import kotlin.reflect.KClass

/**
 * Wraps the widgets that make up the UI representation of a {@link Control}. Updates to the view
 * are signaled via calls to {@link #bindData}. Similar to the ViewHolder concept used in
 * RecyclerViews.
 */
class ControlViewHolder(
    val layout: ViewGroup,
    val controlsController: ControlsController,
    val uiExecutor: DelayableExecutor,
    val bgExecutor: DelayableExecutor,
    val controlActionCoordinator: ControlActionCoordinator
) {

    companion object {
        const val STATE_ANIMATION_DURATION = 700L
        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 0
        private const val STATUS_ALPHA_ENABLED = 1f
        private const val STATUS_ALPHA_DIMMED = 0.45f
        private val FORCE_PANEL_DEVICES = setOf(
            DeviceTypes.TYPE_THERMOSTAT,
            DeviceTypes.TYPE_CAMERA
        )
        private val ATTR_ENABLED = intArrayOf(android.R.attr.state_enabled)
        private val ATTR_DISABLED = intArrayOf(-android.R.attr.state_enabled)
        const val MIN_LEVEL = 0
        const val MAX_LEVEL = 10000

        fun findBehaviorClass(
            status: Int,
            template: ControlTemplate,
            deviceType: Int
        ): KClass<out Behavior> {
            return when {
                status != Control.STATUS_OK -> StatusBehavior::class
                deviceType == DeviceTypes.TYPE_CAMERA -> TouchBehavior::class
                template == ControlTemplate.NO_TEMPLATE -> TouchBehavior::class
                template is ToggleTemplate -> ToggleBehavior::class
                template is StatelessTemplate -> TouchBehavior::class
                template is ToggleRangeTemplate -> ToggleRangeBehavior::class
                template is RangeTemplate -> ToggleRangeBehavior::class
                template is TemperatureControlTemplate -> TemperatureControlBehavior::class
                else -> DefaultBehavior::class
            }
        }
    }

    private val toggleBackgroundIntensity: Float = layout.context.resources
            .getFraction(R.fraction.controls_toggle_bg_intensity, 1, 1)
    private var stateAnimator: ValueAnimator? = null
    private var statusAnimator: Animator? = null
    private val baseLayer: GradientDrawable
    val icon: ImageView = layout.requireViewById(R.id.icon)
    private val status: TextView = layout.requireViewById(R.id.status)
    private var nextStatusText: CharSequence = ""
    val title: TextView = layout.requireViewById(R.id.title)
    val subtitle: TextView = layout.requireViewById(R.id.subtitle)
    val context: Context = layout.getContext()
    val clipLayer: ClipDrawable
    lateinit var cws: ControlWithState
    var behavior: Behavior? = null
    var lastAction: ControlAction? = null
    var isLoading = false
    var visibleDialog: Dialog? = null
    private var lastChallengeDialog: Dialog? = null
    private val onDialogCancel: () -> Unit = { lastChallengeDialog = null }

    val deviceType: Int
        get() = cws.control?.let { it.deviceType } ?: cws.ci.deviceType
    val controlStatus: Int
        get() = cws.control?.let { it.status } ?: Control.STATUS_UNKNOWN
    val controlTemplate: ControlTemplate
        get() = cws.control?.let { it.controlTemplate } ?: ControlTemplate.NO_TEMPLATE

    var userInteractionInProgress = false

    init {
        val ld = layout.getBackground() as LayerDrawable
        ld.mutate()
        clipLayer = ld.findDrawableByLayerId(R.id.clip_layer) as ClipDrawable
        clipLayer.alpha = ALPHA_DISABLED
        baseLayer = ld.findDrawableByLayerId(R.id.background) as GradientDrawable
        // needed for marquee to start
        status.setSelected(true)
    }

    fun bindData(cws: ControlWithState) {
        // If an interaction is in progress, the update may visually interfere with the action the
        // action the user wants to make. Don't apply the update, and instead assume a new update
        // will coming from when the user interaction is complete.
        if (userInteractionInProgress) return

        this.cws = cws

        // For the following statuses only, assume the title/subtitle could not be set properly
        // by the app and instead use the last known information from favorites
        if (controlStatus == Control.STATUS_UNKNOWN || controlStatus == Control.STATUS_NOT_FOUND) {
            title.setText(cws.ci.controlTitle)
            subtitle.setText(cws.ci.controlSubtitle)
        } else {
            cws.control?.let {
                title.setText(it.title)
                subtitle.setText(it.subtitle)
            }
        }

        cws.control?.let {
            layout.setClickable(true)
            layout.setOnLongClickListener(View.OnLongClickListener() {
                controlActionCoordinator.longPress(this@ControlViewHolder)
                true
            })

            controlActionCoordinator.runPendingAction(cws.ci.controlId)
        }

        isLoading = false
        behavior = bindBehavior(behavior,
            findBehaviorClass(controlStatus, controlTemplate, deviceType))
        updateContentDescription()
    }

    fun actionResponse(@ControlAction.ResponseResult response: Int) {
        controlActionCoordinator.enableActionOnTouch(cws.ci.controlId)

        // OK responses signal normal behavior, and the app will provide control updates
        val failedAttempt = lastChallengeDialog != null
        when (response) {
            ControlAction.RESPONSE_OK ->
                lastChallengeDialog = null
            ControlAction.RESPONSE_UNKNOWN -> {
                lastChallengeDialog = null
                setErrorStatus()
            }
            ControlAction.RESPONSE_FAIL -> {
                lastChallengeDialog = null
                setErrorStatus()
            }
            ControlAction.RESPONSE_CHALLENGE_PIN -> {
                lastChallengeDialog = ChallengeDialogs.createPinDialog(
                    this, false /* useAlphanumeric */, failedAttempt, onDialogCancel)
                lastChallengeDialog?.show()
            }
            ControlAction.RESPONSE_CHALLENGE_PASSPHRASE -> {
                lastChallengeDialog = ChallengeDialogs.createPinDialog(
                    this, true /* useAlphanumeric */, failedAttempt, onDialogCancel)
                lastChallengeDialog?.show()
            }
            ControlAction.RESPONSE_CHALLENGE_ACK -> {
                lastChallengeDialog = ChallengeDialogs.createConfirmationDialog(
                    this, onDialogCancel)
                lastChallengeDialog?.show()
            }
        }
    }

    fun dismiss() {
        lastChallengeDialog?.dismiss()
        lastChallengeDialog = null
        visibleDialog?.dismiss()
        visibleDialog = null
    }

    fun setErrorStatus() {
        val text = context.resources.getString(R.string.controls_error_failed)
        animateStatusChange(/* animated */ true, {
            setStatusText(text, /* immediately */ true)
        })
    }

    private fun updateContentDescription() =
        layout.setContentDescription("${title.text} ${subtitle.text} ${status.text}")

    fun action(action: ControlAction) {
        lastAction = action
        controlsController.action(cws.componentName, cws.ci, action)
    }

    fun usePanel(): Boolean {
        return deviceType in ControlViewHolder.FORCE_PANEL_DEVICES ||
            controlTemplate == ControlTemplate.NO_TEMPLATE
    }

    fun bindBehavior(
        existingBehavior: Behavior?,
        clazz: KClass<out Behavior>,
        offset: Int = 0
    ): Behavior {
        val behavior = if (existingBehavior == null || existingBehavior!!::class != clazz) {
            // Behavior changes can signal a change in template from the app or
            // first time setup
            val newBehavior = clazz.java.newInstance()
            newBehavior.initialize(this)

            // let behaviors define their own, if necessary, and clear any existing ones
            layout.setAccessibilityDelegate(null)
            newBehavior
        } else {
            existingBehavior
        }

        return behavior.also {
            it.bind(cws, offset)
        }
    }

    internal fun applyRenderInfo(enabled: Boolean, offset: Int, animated: Boolean = true) {
        val deviceTypeOrError = if (controlStatus == Control.STATUS_OK ||
                controlStatus == Control.STATUS_UNKNOWN) {
            deviceType
        } else {
            RenderInfo.ERROR_ICON
        }
        val ri = RenderInfo.lookup(context, cws.componentName, deviceTypeOrError, offset)
        val fg = context.resources.getColorStateList(ri.foreground, context.theme)
        val newText = nextStatusText
        val control = cws.control

        var shouldAnimate = animated
        if (newText == status.text) {
            shouldAnimate = false
        }
        animateStatusChange(shouldAnimate) {
            updateStatusRow(enabled, newText, ri.icon, fg, control)
        }

        animateBackgroundChange(shouldAnimate, enabled, ri.enabledBackground)
    }

    fun getStatusText() = status.text

    fun setStatusTextSize(textSize: Float) =
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)

    fun setStatusText(text: CharSequence, immediately: Boolean = false) {
        if (immediately) {
            status.alpha = STATUS_ALPHA_ENABLED
            status.text = text
            updateContentDescription()
        }
        nextStatusText = text
    }

    private fun animateBackgroundChange(
        animated: Boolean,
        enabled: Boolean,
        @ColorRes bgColor: Int
    ) {
        val bg = context.resources.getColor(R.color.control_default_background, context.theme)
        var (newClipColor, newAlpha) = if (enabled) {
            // allow color overrides for the enabled state only
            val color = cws.control?.getCustomColor()?.let {
                val state = intArrayOf(android.R.attr.state_enabled)
                it.getColorForState(state, it.getDefaultColor())
            } ?: context.resources.getColor(bgColor, context.theme)
            listOf(color, ALPHA_ENABLED)
        } else {
            listOf(
                context.resources.getColor(R.color.control_default_background, context.theme),
                ALPHA_DISABLED
            )
        }

        (clipLayer.getDrawable() as GradientDrawable).apply {
            val newBaseColor = if (behavior is ToggleRangeBehavior) {
                ColorUtils.blendARGB(bg, newClipColor, toggleBackgroundIntensity)
            } else {
                bg
            }
            stateAnimator?.cancel()
            if (animated) {
                val oldColor = color?.defaultColor ?: newClipColor
                val oldBaseColor = baseLayer.color?.defaultColor ?: newBaseColor
                val oldAlpha = layout.alpha
                stateAnimator = ValueAnimator.ofInt(clipLayer.alpha, newAlpha).apply {
                    addUpdateListener {
                        alpha = it.animatedValue as Int
                        setColor(ColorUtils.blendARGB(oldColor, newClipColor, it.animatedFraction))
                        baseLayer.setColor(ColorUtils.blendARGB(oldBaseColor,
                                newBaseColor, it.animatedFraction))
                        layout.alpha = MathUtils.lerp(oldAlpha, 1f, it.animatedFraction)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            stateAnimator = null
                        }
                    })
                    duration = STATE_ANIMATION_DURATION
                    interpolator = Interpolators.CONTROL_STATE
                    start()
                }
            } else {
                alpha = newAlpha
                setColor(newClipColor)
                baseLayer.setColor(newBaseColor)
                layout.alpha = 1f
            }
        }
    }

    private fun animateStatusChange(animated: Boolean, statusRowUpdater: () -> Unit) {
        statusAnimator?.cancel()

        if (!animated) {
            statusRowUpdater.invoke()
            return
        }

        if (isLoading) {
            statusRowUpdater.invoke()
            statusAnimator = ObjectAnimator.ofFloat(status, "alpha", STATUS_ALPHA_DIMMED).apply {
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                duration = 500L
                interpolator = Interpolators.LINEAR
                startDelay = 900L
                start()
            }
        } else {
            val fadeOut = ObjectAnimator.ofFloat(status, "alpha", 0f).apply {
                duration = 200L
                interpolator = Interpolators.LINEAR
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        statusRowUpdater.invoke()
                    }
                })
            }
            val fadeIn = ObjectAnimator.ofFloat(status, "alpha", STATUS_ALPHA_ENABLED).apply {
                duration = 200L
                interpolator = Interpolators.LINEAR
            }
            statusAnimator = AnimatorSet().apply {
                playSequentially(fadeOut, fadeIn)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        status.alpha = STATUS_ALPHA_ENABLED
                        statusAnimator = null
                    }
                })
                start()
            }
        }
    }

    private fun updateStatusRow(
        enabled: Boolean,
        text: CharSequence,
        drawable: Drawable,
        color: ColorStateList,
        control: Control?
    ) {
        setEnabled(enabled)

        status.text = text
        updateContentDescription()

        status.setTextColor(color)

        control?.getCustomIcon()?.let {
            // do not tint custom icons, assume the intended icon color is correct
            if (icon.imageTintList != null) {
                icon.imageTintList = null
            }
            icon.setImageIcon(it)
        } ?: run {
            if (drawable is StateListDrawable) {
                // Only reset the drawable if it is a different resource, as it will interfere
                // with the image state and animation.
                if (icon.drawable == null || !(icon.drawable is StateListDrawable)) {
                    icon.setImageDrawable(drawable)
                }
                val state = if (enabled) ATTR_ENABLED else ATTR_DISABLED
                icon.setImageState(state, true)
            } else {
                icon.setImageDrawable(drawable)
            }

            // do not color app icons
            if (deviceType != DeviceTypes.TYPE_ROUTINE) {
                icon.imageTintList = color
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        status.setEnabled(enabled)
        icon.setEnabled(enabled)
    }
}
