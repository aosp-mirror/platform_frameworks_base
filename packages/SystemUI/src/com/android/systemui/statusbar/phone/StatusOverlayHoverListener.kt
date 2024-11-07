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

package com.android.systemui.statusbar.phone

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.PaintDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.View.OnHoverListener
import androidx.annotation.ColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationControllerStore
import com.android.systemui.statusbar.data.repository.SysuiDarkIconDispatcherStore
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class StatusOverlayHoverListenerFactory
@Inject
constructor(
    @Main private val resources: Resources,
    private val configurationController: ConfigurationController,
    private val darkIconDispatcherStore: SysuiDarkIconDispatcherStore,
    private val statusBarConfigurationControllerStore: StatusBarConfigurationControllerStore,
) {

    /** Creates listener always using the same light color for overlay */
    fun createListener(view: View) =
        StatusOverlayHoverListener(
            view,
            configurationController,
            resources,
            flowOf(HoverTheme.LIGHT),
        )

    /**
     * Creates listener using [DarkIconDispatcher] to determine light or dark color of the overlay
     */
    fun createDarkAwareListener(view: View) =
        createDarkAwareListener(view, view.darkIconDispatcher.darkChangeFlow())

    /**
     * Creates listener using [DarkIconDispatcher] to determine light or dark color of the overlay
     * Also sets margins for hover background relative to view bounds
     */
    fun createDarkAwareListener(
        view: View,
        leftHoverMargin: Int = 0,
        rightHoverMargin: Int = 0,
        topHoverMargin: Int = 0,
        bottomHoverMargin: Int = 0,
    ) =
        createDarkAwareListener(
            view,
            view.darkIconDispatcher.darkChangeFlow(),
            leftHoverMargin,
            rightHoverMargin,
            topHoverMargin,
            bottomHoverMargin,
        )

    /**
     * Creates listener using provided [DarkChange] producer to determine light or dark color of the
     * overlay
     */
    fun createDarkAwareListener(view: View, darkFlow: StateFlow<DarkChange>) =
        StatusOverlayHoverListener(
            view,
            view.statusBarConfigurationController,
            view.resources,
            darkFlow.map { toHoverTheme(view, it) },
        )

    private fun createDarkAwareListener(
        view: View,
        darkFlow: StateFlow<DarkChange>,
        leftHoverMargin: Int = 0,
        rightHoverMargin: Int = 0,
        topHoverMargin: Int = 0,
        bottomHoverMargin: Int = 0,
    ) =
        StatusOverlayHoverListener(
            view,
            view.statusBarConfigurationController,
            view.resources,
            darkFlow.map { toHoverTheme(view, it) },
            leftHoverMargin,
            rightHoverMargin,
            topHoverMargin,
            bottomHoverMargin,
        )

    private val View.statusBarConfigurationController
        get() = statusBarConfigurationControllerStore.forDisplay(context.displayId)

    private val View.darkIconDispatcher
        get() = darkIconDispatcherStore.forDisplay(context.displayId)

    private fun toHoverTheme(view: View, darkChange: DarkChange): HoverTheme {
        val calculatedTint = DarkIconDispatcher.getTint(darkChange.areas, view, darkChange.tint)
        // currently calculated tint is either white or some shade of black.
        // So checking for Color.WHITE is deterministic compared to checking for Color.BLACK.
        // In the future checking Color.luminance() might be more appropriate.
        return if (calculatedTint == Color.WHITE) HoverTheme.LIGHT else HoverTheme.DARK
    }
}

/**
 * theme of hover drawable - it's different from device theme. This theme depends on view's
 * background and/or dark value returned from [DarkIconDispatcher]
 */
enum class HoverTheme {
    LIGHT,
    DARK,
}

/**
 * [OnHoverListener] that adds [Drawable] overlay on top of the status icons when cursor/stylus
 * starts hovering over them and removes overlay when status icons are no longer hovered
 */
class StatusOverlayHoverListener(
    view: View,
    configurationController: ConfigurationController,
    private val resources: Resources,
    private val themeFlow: Flow<HoverTheme>,
    private val leftHoverMargin: Int = 0,
    private val rightHoverMargin: Int = 0,
    private val topHoverMargin: Int = 0,
    private val bottomHoverMargin: Int = 0,
) : OnHoverListener {

    @ColorInt private var darkColor: Int = 0
    @ColorInt private var lightColor: Int = 0
    private var cornerRadius = 0f
    private var leftHoverMarginInPx: Int = 0
    private var rightHoverMarginInPx: Int = 0
    private var topHoverMarginInPx: Int = 0
    private var bottomHoverMarginInPx: Int = 0

    private var lastTheme = HoverTheme.LIGHT

    val backgroundColor
        get() = if (lastTheme == HoverTheme.LIGHT) lightColor else darkColor

    init {
        view.repeatWhenAttached {
            lifecycleScope.launch {
                val configurationListener =
                    object : ConfigurationListener {
                        override fun onConfigChanged(newConfig: Configuration?) {
                            updateResources()
                        }
                    }
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    configurationController.addCallback(configurationListener)
                }
                configurationController.removeCallback(configurationListener)
            }
            lifecycleScope.launch { themeFlow.collect { lastTheme = it } }
        }
        updateResources()
    }

    override fun onHover(v: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
            val drawable =
                PaintDrawable(backgroundColor).apply {
                    setCornerRadius(cornerRadius)
                    setBounds(
                        /*left = */ 0 + leftHoverMarginInPx,
                        /*top = */ 0 + topHoverMarginInPx,
                        /*right = */ v.width - rightHoverMarginInPx,
                        /*bottom = */ v.height - bottomHoverMarginInPx,
                    )
                }
            v.overlay.add(drawable)
        } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
            v.overlay.clear()
        }
        return true
    }

    private fun updateResources() {
        lightColor = resources.getColor(R.color.status_bar_icons_hover_color_light)
        darkColor = resources.getColor(R.color.status_bar_icons_hover_color_dark)
        cornerRadius = resources.getDimension(R.dimen.status_icons_hover_state_background_radius)
        leftHoverMarginInPx = leftHoverMargin.dpToPx(resources)
        rightHoverMarginInPx = rightHoverMargin.dpToPx(resources)
        topHoverMarginInPx = topHoverMargin.dpToPx(resources)
        bottomHoverMarginInPx = bottomHoverMargin.dpToPx(resources)
    }

    private fun Int.dpToPx(resources: Resources): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                toFloat(),
                resources.displayMetrics,
            )
            .toInt()
    }
}
