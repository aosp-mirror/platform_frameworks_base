/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tileimpl

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources.ID_NULL
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.android.settingslib.Utils
import com.android.systemui.FontSizeUtils
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTileView
import java.util.Objects

private const val TAG = "QSTileViewImpl"
open class QSTileViewImpl @JvmOverloads constructor(
    context: Context,
    private val _icon: QSIconView,
    private val collapsed: Boolean = false
) : QSTileView(context), HeightOverrideable {

    companion object {
        private const val INVALID = -1
    }

    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE

    private val colorActive = Utils.getColorAttrDefaultColor(context,
            com.android.internal.R.attr.colorAccentPrimary)
    private val colorInactive = Utils.getColorAttrDefaultColor(context, R.attr.offStateColor)
    private val colorUnavailable =
            Utils.getColorAttrDefaultColor(context, android.R.attr.colorBackground)

    private val colorLabelActive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimaryInverse)
    private val colorLabelInactive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
    private val colorLabelUnavailable =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorTertiary)

    private val sideView = ImageView(context)
    private lateinit var label: TextView
    protected lateinit var secondaryLabel: TextView
    private lateinit var labelContainer: IgnorableChildLinearLayout

    protected var showRippleEffect = true

    private lateinit var ripple: RippleDrawable
    private lateinit var colorBackgroundDrawable: Drawable
    private var paintColor = Color.WHITE
    private var paintAnimator: ValueAnimator? = null
    private var labelAnimator: ValueAnimator? = null
    private var secondaryLabelAnimator: ValueAnimator? = null

    private var accessibilityClass: String? = null
    private var stateDescriptionDeltas: CharSequence? = null
    private var lastStateDescription: CharSequence? = null
    private var tileState = false
    private var lastState = INVALID

    private val locInScreen = IntArray(2)

    init {
        setId(generateViewId())
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        background = createTileBackground()

        val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
        val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
        setPaddingRelative(startPadding, padding, padding, padding)

        val iconSize = resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        addView(_icon, LayoutParams(iconSize, iconSize))

        createAndAddLabels()

        sideView.visibility = GONE
        val sideViewLayoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        }
        addView(sideView, sideViewLayoutParams)
        sideView.adjustViewBounds = true
        sideView.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        FontSizeUtils.updateFontSize(label, R.dimen.qs_tile_text_size)
        FontSizeUtils.updateFontSize(secondaryLabel, R.dimen.qs_tile_text_size)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        _icon.layoutParams.apply {
            height = iconSize
            width = iconSize
        }

        val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
        val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
        setPaddingRelative(startPadding, padding, padding, padding)

        val labelMargin = context.resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        (labelContainer.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }
        (sideView.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }
    }

    private fun createAndAddLabels() {
        labelContainer = LayoutInflater.from(context)
                .inflate(R.layout.qs_tile_label, this, false) as IgnorableChildLinearLayout
        label = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        if (collapsed) {
            labelContainer.ignoreLastView = true
            secondaryLabel.alpha = 0f
        }
        label.setTextColor(getLabelColor(0)) // Default state
        secondaryLabel.setTextColor(getSecondaryLabelColor(0))
        addView(labelContainer)
    }

    fun createTileBackground(): Drawable {
        ripple = mContext.getDrawable(R.drawable.qs_tile_background) as RippleDrawable
        colorBackgroundDrawable = ripple.findDrawableByLayerId(R.id.background)
        return ripple
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (heightOverride != HeightOverrideable.NO_OVERRIDE) {
            bottom = top + heightOverride
        }
    }

    override fun updateAccessibilityOrder(previousView: View?): View {
        accessibilityTraversalAfter = previousView?.id ?: ID_NULL
        return this
    }

    override fun getIcon(): QSIconView {
        return _icon
    }

    override fun getIconWithBackground(): View {
        return icon
    }

    override fun init(tile: QSTile) {
        init(
                { v: View? -> tile.click(this) },
                { view: View? ->
                    tile.longClick(this)
                    true
                }
        )
    }

    private fun init(
        click: OnClickListener?,
        longClick: OnLongClickListener?
    ) {
        setOnClickListener(click)
        onLongClickListener = longClick
    }

    override fun onStateChanged(state: QSTile.State) {
        post {
            handleStateChanged(state)
        }
    }

    override fun getDetailY(): Int {
        return top + height / 2
    }

    override fun hasOverlappingRendering(): Boolean {
        // Avoid layers for this layout - we don't need them.
        return false
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        background = if (clickable && showRippleEffect) {
            ripple.also {
                // In case that the colorBackgroundDrawable was used as the background, make sure
                // it has the correct callback instead of null
                colorBackgroundDrawable.callback = it
            }
        } else {
            colorBackgroundDrawable
        }
    }

    override fun getLabelContainer(): View {
        return labelContainer
    }

    override fun getSecondaryLabel(): View {
        return secondaryLabel
    }

    override fun getSecondaryIcon(): View {
        return sideView
    }

    // Accessibility

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!TextUtils.isEmpty(accessibilityClass)) {
            event.className = accessibilityClass
        }
        if (event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
                stateDescriptionDeltas != null) {
            event.text.add(stateDescriptionDeltas)
            stateDescriptionDeltas = null
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Clear selected state so it is not announce by talkback.
        info.isSelected = false
        if (!TextUtils.isEmpty(accessibilityClass)) {
            info.className = accessibilityClass
            if (Switch::class.java.name == accessibilityClass) {
                val label = resources.getString(
                        if (tileState) R.string.switch_bar_on else R.string.switch_bar_off)
                // Set the text here for tests in
                // android.platform.test.scenario.sysui.quicksettings. Can be removed when
                // UiObject2 has a new getStateDescription() API and tests are updated.
                info.text = label
                info.isChecked = tileState
                info.isCheckable = true
                if (isLongClickable) {
                    info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                                    resources.getString(
                                            R.string.accessibility_long_click_tile)))
                }
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName).append('[')
        sb.append("locInScreen=(${locInScreen[0]}, ${locInScreen[1]})")
        sb.append(", iconView=$_icon")
        sb.append(", tileState=$tileState")
        sb.append("]")
        return sb.toString()
    }

    // HANDLE STATE CHANGES RELATED METHODS

    protected open fun handleStateChanged(state: QSTile.State) {
        val allowAnimations = animationsEnabled() && paintColor != Color.WHITE
        showRippleEffect = state.showRippleEffect
        isClickable = state.state != Tile.STATE_UNAVAILABLE
        isLongClickable = state.handlesLongClick
        icon.setIcon(state, allowAnimations)
        contentDescription = state.contentDescription

        // Background color animation
        val newColor = getCircleColor(state.state)
        if (allowAnimations) {
            animateBackground(newColor)
        } else {
            clearBackgroundAnimator()
            colorBackgroundDrawable.setTintList(ColorStateList.valueOf(newColor)).also {
                paintColor = newColor
            }
            paintColor = newColor
        }
        //

        // State handling and description
        val stateDescription = StringBuilder()
        val stateText = getStateText(state)
        if (!TextUtils.isEmpty(stateText)) {
            stateDescription.append(stateText)
            if (TextUtils.isEmpty(state.secondaryLabel)) {
                state.secondaryLabel = stateText
            }
        }
        if (!TextUtils.isEmpty(state.stateDescription)) {
            stateDescription.append(", ")
            stateDescription.append(state.stateDescription)
            if (lastState != INVALID && state.state == lastState &&
                    state.stateDescription != lastStateDescription) {
                stateDescriptionDeltas = state.stateDescription
            }
        }

        setStateDescription(stateDescription.toString())
        lastStateDescription = state.stateDescription

        accessibilityClass = if (state.state == Tile.STATE_UNAVAILABLE) {
            null
        } else {
            state.expandedAccessibilityClassName
        }

        if (state is BooleanState) {
            val newState = state.value
            if (tileState != newState) {
                tileState = newState
            }
        }
        //

        // Labels
        if (!Objects.equals(label.text, state.label)) {
            label.text = state.label
        }
        if (!Objects.equals(secondaryLabel.text, state.secondaryLabel)) {
            secondaryLabel.text = state.secondaryLabel
            secondaryLabel.visibility = if (TextUtils.isEmpty(state.secondaryLabel)) {
                GONE
            } else {
                VISIBLE
            }
        }

        if (allowAnimations) {
            animateLabelColor(getLabelColor(state.state))
            animateSecondaryLabelColor(getSecondaryLabelColor(state.state))
        } else {
            label.setTextColor(getLabelColor(state.state))
            secondaryLabel.setTextColor(getSecondaryLabelColor(state.state))
        }

        label.isEnabled = !state.disabledByPolicy

        loadSideViewDrawableIfNecessary(state)

        lastState = state.state
    }

    private fun loadSideViewDrawableIfNecessary(state: QSTile.State) {
        if (state.sideViewDrawable != null) {
            sideView.setImageDrawable(state.sideViewDrawable)
            sideView.visibility = View.VISIBLE
        } else {
            sideView.setImageDrawable(null)
            sideView.visibility = GONE
        }
    }

    private fun getStateText(state: QSTile.State): String {
        return if (state.disabledByPolicy) {
            context.getString(R.string.tile_disabled)
        } else if (state.state == Tile.STATE_UNAVAILABLE) {
            context.getString(R.string.tile_unavailable)
        } else if (state is BooleanState) {
            if (state.state == Tile.STATE_INACTIVE) {
                context.getString(R.string.switch_bar_off)
            } else {
                context.getString(R.string.switch_bar_on)
            }
        } else {
            ""
        }
    }

    /*
     * The view should not be animated if it's not on screen and no part of it is visible.
     */
    protected open fun animationsEnabled(): Boolean {
        if (!isShown) {
            return false
        }
        if (alpha != 1f) {
            return false
        }
        getLocationOnScreen(locInScreen)
        return locInScreen.get(1) >= -height
    }

    private fun animateBackground(newBackgroundColor: Int) {
        if (newBackgroundColor != paintColor) {
            clearBackgroundAnimator()
            paintAnimator = ValueAnimator.ofArgb(paintColor, newBackgroundColor)
                    .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                        addUpdateListener { animation: ValueAnimator ->
                            val c = animation.animatedValue as Int
                            colorBackgroundDrawable.setTintList(ColorStateList.valueOf(c)).also {
                                paintColor = c
                            }
                        }
                        start()
                    }
        }
    }

    private fun animateLabelColor(color: Int) {
        val currentColor = label.textColors.defaultColor
        if (currentColor != color) {
            clearLabelAnimator()
            labelAnimator = ValueAnimator.ofArgb(currentColor, color)
                    .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                        addUpdateListener {
                            label.setTextColor(it.animatedValue as Int)
                        }
                        start()
                    }
        }
    }

    private fun animateSecondaryLabelColor(color: Int) {
        val currentColor = secondaryLabel.textColors.defaultColor
        if (currentColor != color) {
            clearSecondaryLabelAnimator()
            secondaryLabelAnimator = ValueAnimator.ofArgb(currentColor, color)
                    .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                        addUpdateListener {
                            secondaryLabel.setTextColor(it.animatedValue as Int)
                        }
                        start()
                    }
        }
    }

    private fun clearBackgroundAnimator() {
        paintAnimator?.cancel()?.also { paintAnimator = null }
    }

    private fun clearLabelAnimator() {
        labelAnimator?.cancel()?.also { labelAnimator = null }
    }

    private fun clearSecondaryLabelAnimator() {
        secondaryLabelAnimator?.cancel()?.also { secondaryLabelAnimator = null }
    }

    private fun getCircleColor(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorActive
            Tile.STATE_INACTIVE -> colorInactive
            Tile.STATE_UNAVAILABLE -> colorUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getLabelColor(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorLabelActive
            Tile.STATE_INACTIVE -> colorLabelInactive
            Tile.STATE_UNAVAILABLE -> colorLabelUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getSecondaryLabelColor(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorLabelActive
            Tile.STATE_INACTIVE, Tile.STATE_UNAVAILABLE -> colorLabelUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }
}