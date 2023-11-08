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
package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.collection.ArrayMap
import androidx.lifecycle.lifecycleScope
import com.android.internal.policy.SystemBarUtils
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconColorLookup
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconColors
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.util.children
import com.android.systemui.util.kotlin.mapValuesNotNullTo
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.kotlin.stateFlow
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/** Binds a view-model to a [NotificationIconContainer]. */
object NotificationIconContainerViewBinder {
    @JvmStatic
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerShelfViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        viewStore: ShelfNotificationIconViewStore,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            lifecycleScope.launch {
                viewModel.icons.bindIcons(view, configuration, configurationController, viewStore)
            }
        }
    }

    @JvmStatic
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerStatusBarViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        viewStore: StatusBarNotificationIconViewStore,
    ): DisposableHandle {
        val contrastColorUtil = ContrastColorUtil.getInstance(view.context)
        return view.repeatWhenAttached {
            lifecycleScope.run {
                launch {
                    viewModel.icons.bindIcons(
                        view,
                        configuration,
                        configurationController,
                        viewStore
                    )
                }
                launch { viewModel.iconColors.bindIconColors(view, contrastColorUtil) }
                launch { viewModel.bindIsolatedIcon(view, viewStore) }
                launch { viewModel.animationsEnabled.bindAnimationsEnabled(view) }
            }
        }
    }

    @JvmStatic
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        dozeParameters: DozeParameters,
        viewStore: IconViewStore,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            lifecycleScope.launch {
                launch {
                    viewModel.icons.bindIcons(
                        view,
                        configuration,
                        configurationController,
                        viewStore,
                    )
                }
                launch { viewModel.animationsEnabled.bindAnimationsEnabled(view) }
                launch { viewModel.isDozing.bindIsDozing(view, dozeParameters) }
                launch {
                    configuration
                        .getColorAttr(R.attr.wallpaperTextColor, DEFAULT_AOD_ICON_COLOR)
                        .bindIconColors(view)
                }
            }
        }
    }

    /** Binds to [NotificationIconContainer.setAnimationsEnabled] */
    private suspend fun Flow<Boolean>.bindAnimationsEnabled(view: NotificationIconContainer) {
        collect(view::setAnimationsEnabled)
    }

    /**
     * Binds to the [StatusBarIconView.setStaticDrawableColor] and [StatusBarIconView.setDecorColor]
     * of the [children] of an [NotificationIconContainer].
     */
    private suspend fun Flow<NotificationIconColorLookup>.bindIconColors(
        view: NotificationIconContainer,
        contrastColorUtil: ContrastColorUtil,
    ) {
        mapNotNull { lookup -> lookup.iconColors(view.viewBounds) }
            .collect { iconLookup -> view.applyTint(iconLookup, contrastColorUtil) }
    }

    /**
     * Binds to the [StatusBarIconView.setStaticDrawableColor] and [StatusBarIconView.setDecorColor]
     * of the [children] of an [NotificationIconContainer].
     */
    private suspend fun Flow<Int>.bindIconColors(view: NotificationIconContainer) {
        collect { tint ->
            view.children.filterIsInstance<StatusBarIconView>().forEach { icon ->
                icon.staticDrawableColor = tint
                icon.setDecorColor(tint)
            }
        }
    }

    private suspend fun Flow<AnimatedValue<Boolean>>.bindIsDozing(
        view: NotificationIconContainer,
        dozeParameters: DozeParameters,
    ) {
        collect { isDozing ->
            if (isDozing.isAnimating) {
                val animate = !dozeParameters.displayNeedsBlanking
                view.setDozing(
                    /* dozing = */ isDozing.value,
                    /* fade = */ animate,
                    /* delay = */ 0,
                    /* endRunnable = */ isDozing::stopAnimating,
                )
            } else {
                view.setDozing(
                    /* dozing = */ isDozing.value,
                    /* fade= */ false,
                    /* delay= */ 0,
                )
            }
        }
    }

    private suspend fun NotificationIconContainerStatusBarViewModel.bindIsolatedIcon(
        view: NotificationIconContainer,
        viewStore: IconViewStore,
    ) {
        coroutineScope {
            launch {
                isolatedIconLocation.collect { location ->
                    view.setIsolatedIconLocation(location, true)
                }
            }
            launch {
                isolatedIcon.collect { iconInfo ->
                    val iconView = iconInfo.value?.let { viewStore.iconView(it.notifKey) }
                    if (iconInfo.isAnimating) {
                        view.showIconIsolatedAnimated(iconView, iconInfo::stopAnimating)
                    } else {
                        view.showIconIsolated(iconView)
                    }
                }
            }
        }
    }

    /** Binds [NotificationIconsViewData] to a [NotificationIconContainer]'s [children]. */
    private suspend fun Flow<NotificationIconsViewData>.bindIcons(
        view: NotificationIconContainer,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        viewStore: IconViewStore,
    ): Unit = coroutineScope {
        val iconSizeFlow: Flow<Int> =
            configuration.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size_sp,
            )
        val iconHorizontalPaddingFlow: Flow<Int> =
            configuration.getDimensionPixelSize(R.dimen.status_bar_icon_horizontal_margin)
        val statusBarHeightFlow: StateFlow<Int> =
            stateFlow(changedSignals = configurationController.onConfigChanged) {
                SystemBarUtils.getStatusBarHeight(view.context)
            }
        val layoutParams: Flow<FrameLayout.LayoutParams> =
            combine(iconSizeFlow, iconHorizontalPaddingFlow, statusBarHeightFlow) {
                iconSize,
                iconHPadding,
                statusBarHeight,
                ->
                FrameLayout.LayoutParams(iconSize + 2 * iconHPadding, statusBarHeight)
            }

        launch {
            layoutParams.collect { params: FrameLayout.LayoutParams ->
                for (child in view.children) {
                    child.layoutParams = params
                }
            }
        }

        var prevIcons = NotificationIconsViewData()
        sample(layoutParams, ::Pair).collect {
            (iconsData: NotificationIconsViewData, layoutParams: FrameLayout.LayoutParams),
            ->
            val iconsDiff = NotificationIconsViewData.computeDifference(iconsData, prevIcons)
            prevIcons = iconsData

            val replacingIcons =
                iconsDiff.groupReplacements.mapValuesNotNullTo(ArrayMap()) { (_, v) ->
                    viewStore.iconView(v.notifKey).statusBarIcon
                }
            view.setReplacingIcons(replacingIcons)

            val childrenByNotifKey: Map<String, StatusBarIconView> =
                view.children.filterIsInstance<StatusBarIconView>().associateByTo(ArrayMap()) {
                    it.notification.key
                }

            iconsDiff.removed
                .mapNotNull { key -> childrenByNotifKey[key] }
                .forEach { child -> view.removeView(child) }

            val toAdd = iconsDiff.added.map { viewStore.iconView(it.notifKey) }
            for ((i, sbiv) in toAdd.withIndex()) {
                // The view might still be transiently added if it was just removed
                // and added again
                view.removeTransientView(sbiv)
                view.addView(sbiv, i, layoutParams)
            }

            view.setChangingViewPositions(true)
            // Re-sort notification icons
            val childCount = view.childCount
            for (i in 0 until childCount) {
                val actual = view.getChildAt(i)
                val expected = viewStore.iconView(iconsData.visibleKeys[i].notifKey)
                if (actual === expected) {
                    continue
                }
                view.removeView(expected)
                view.addView(expected, i)
            }
            view.setChangingViewPositions(false)

            view.setReplacingIcons(null)
        }
    }

    // TODO(b/305739416): Once StatusBarIconView has its own Recommended Architecture stack, this
    //  can be moved there and cleaned up.
    private fun ViewGroup.applyTint(
        iconColors: NotificationIconColors,
        contrastColorUtil: ContrastColorUtil,
    ) {
        children
            .filterIsInstance<StatusBarIconView>()
            .filter { it.width != 0 }
            .forEach { iv -> iv.updateTintForIcon(iconColors, contrastColorUtil) }
    }

    private fun StatusBarIconView.updateTintForIcon(
        iconColors: NotificationIconColors,
        contrastColorUtil: ContrastColorUtil,
    ) {
        val isPreL = java.lang.Boolean.TRUE == getTag(R.id.icon_is_pre_L)
        val isColorized = !isPreL || NotificationUtils.isGrayscale(this, contrastColorUtil)
        staticDrawableColor = iconColors.staticDrawableColor(viewBounds, isColorized)
        setDecorColor(iconColors.tint)
    }

    /** External storage for [StatusBarIconView] instances. */
    fun interface IconViewStore {
        fun iconView(key: String): StatusBarIconView
    }

    @ColorInt private val DEFAULT_AOD_ICON_COLOR = Color.WHITE
}

/** [IconViewStore] for the [com.android.systemui.statusbar.NotificationShelf] */
class ShelfNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView {
        val entry = notifCollection.getEntry(key) ?: error("No entry found for key: $key")
        return entry.icons.shelfIcon ?: error("No shelf IconView found for key: $key")
    }
}

/** [IconViewStore] for the always-on display. */
class AlwaysOnDisplayNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView {
        val entry = notifCollection.getEntry(key) ?: error("No entry found for key: $key")
        return entry.icons.aodIcon ?: error("No AOD IconView found for key: $key")
    }
}

/** [IconViewStore] for the status bar. */
class StatusBarNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView {
        val entry = notifCollection.getEntry(key) ?: error("No entry found for key: $key")
        return entry.icons.statusBarIcon ?: error("No status bar IconView found for key: $key")
    }
}

private val View.viewBounds: Rect
    get() {
        val tmpArray = intArrayOf(0, 0)
        getLocationOnScreen(tmpArray)
        return Rect(
            /* left = */ tmpArray[0],
            /* top = */ tmpArray[1],
            /* right = */ left + width,
            /* bottom = */ top + height,
        )
    }
