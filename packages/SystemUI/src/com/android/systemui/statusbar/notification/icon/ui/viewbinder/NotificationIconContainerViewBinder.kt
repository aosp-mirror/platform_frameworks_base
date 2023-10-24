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

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.collection.ArrayMap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.internal.policy.SystemBarUtils
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconColors
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerViewModel.IconsViewData
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.util.children
import com.android.systemui.util.kotlin.mapValuesNotNullTo
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.kotlin.stateFlow
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Binds a [NotificationIconContainer] to its [view model][NotificationIconContainerViewModel]. */
object NotificationIconContainerViewBinder {
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        dozeParameters: DozeParameters,
        featureFlags: FeatureFlagsClassic,
        screenOffAnimationController: ScreenOffAnimationController,
        viewStore: IconViewStore,
    ): DisposableHandle {
        val contrastColorUtil = ContrastColorUtil.getInstance(view.context)
        return view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch { viewModel.animationsEnabled.collect(view::setAnimationsEnabled) }
                launch {
                    viewModel.isDozing.collect { (isDozing, animate) ->
                        val animateIfNotBlanking = animate && !dozeParameters.displayNeedsBlanking
                        view.setDozing(
                            /* dozing = */ isDozing,
                            /* fade = */ animateIfNotBlanking,
                            /* delay = */ 0,
                            /* endRunnable = */ viewModel::completeDozeAnimation,
                        )
                    }
                }
                // TODO(b/278765923): this should live where AOD is bound, not inside of the NIC
                //  view-binder
                launch {
                    bindVisibility(
                        viewModel,
                        view,
                        configuration,
                        featureFlags,
                        screenOffAnimationController,
                        onAnimationEnd = viewModel::completeVisibilityAnimation,
                    )
                }
                launch {
                    viewModel.iconColors
                        .mapNotNull { lookup -> lookup.iconColors(view.viewBounds) }
                        .collect { iconLookup -> applyTint(view, iconLookup, contrastColorUtil) }
                }
                launch {
                    bindIconViewData(
                        viewModel,
                        view,
                        configuration,
                        configurationController,
                        viewStore,
                    )
                }
            }
        }
    }

    private suspend fun bindIconViewData(
        viewModel: NotificationIconContainerViewModel,
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

        var prevIcons = IconsViewData()
        viewModel.iconsViewData.sample(layoutParams, ::Pair).collect {
            (iconsData: IconsViewData, layoutParams: FrameLayout.LayoutParams),
            ->
            val iconsDiff = IconsViewData.computeDifference(iconsData, prevIcons)
            prevIcons = iconsData

            val replacingIcons =
                iconsDiff.groupReplacements.mapValuesNotNullTo(ArrayMap()) { (_, v) ->
                    viewStore.iconView(v.notifKey)?.statusBarIcon
                }
            view.setReplacingIcons(replacingIcons)

            val childrenByNotifKey: Map<String, StatusBarIconView> =
                view.children.filterIsInstance<StatusBarIconView>().associateByTo(ArrayMap()) {
                    it.notification.key
                }

            iconsDiff.removed
                .mapNotNull { key -> childrenByNotifKey[key] }
                .forEach { child -> view.removeView(child) }

            val toAdd = iconsDiff.added.mapNotNull { viewStore.iconView(it.notifKey) }
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
                val expected = viewStore.iconView(iconsData.visibleKeys[i].notifKey)!!
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
    private fun applyTint(
        view: NotificationIconContainer,
        iconColors: IconColors,
        contrastColorUtil: ContrastColorUtil,
    ) {
        view.children
            .filterIsInstance<StatusBarIconView>()
            .filter { it.width != 0 }
            .forEach { iv -> updateTintForIcon(iv, iconColors, contrastColorUtil) }
    }

    private fun updateTintForIcon(
        v: StatusBarIconView,
        iconColors: IconColors,
        contrastColorUtil: ContrastColorUtil,
    ) {
        val isPreL = java.lang.Boolean.TRUE == v.getTag(R.id.icon_is_pre_L)
        val isColorized = !isPreL || NotificationUtils.isGrayscale(v, contrastColorUtil)
        v.staticDrawableColor = iconColors.staticDrawableColor(v.viewBounds, isColorized)
        v.setDecorColor(iconColors.tint)
    }

    private suspend fun bindVisibility(
        viewModel: NotificationIconContainerViewModel,
        view: NotificationIconContainer,
        configuration: ConfigurationState,
        featureFlags: FeatureFlagsClassic,
        screenOffAnimationController: ScreenOffAnimationController,
        onAnimationEnd: () -> Unit,
    ): Unit = coroutineScope {
        val iconAppearTranslation =
            configuration.getDimensionPixelSize(R.dimen.shelf_appear_translation).stateIn(this)
        val statusViewMigrated = featureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_VIEW)
        viewModel.isVisible.collect { (isVisible, animate) ->
            view.animate().cancel()
            when {
                !animate -> {
                    view.alpha = 1f
                    if (!statusViewMigrated) {
                        view.translationY = 0f
                    }
                    view.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                }
                featureFlags.isEnabled(Flags.NEW_AOD_TRANSITION) -> {
                    animateInIconTranslation(view, statusViewMigrated)
                    if (isVisible) {
                        CrossFadeHelper.fadeIn(view, onAnimationEnd)
                    } else {
                        CrossFadeHelper.fadeOut(view, onAnimationEnd)
                    }
                }
                !isVisible -> {
                    // Let's make sure the icon are translated to 0, since we cancelled it above
                    animateInIconTranslation(view, statusViewMigrated)
                    CrossFadeHelper.fadeOut(view, onAnimationEnd)
                }
                view.visibility != View.VISIBLE -> {
                    // No fading here, let's just appear the icons instead!
                    view.visibility = View.VISIBLE
                    view.alpha = 1f
                    appearIcons(
                        view,
                        animate = screenOffAnimationController.shouldAnimateAodIcons(),
                        iconAppearTranslation.value,
                        statusViewMigrated,
                    )
                    onAnimationEnd()
                }
                else -> {
                    // Let's make sure the icons are translated to 0, since we cancelled it above
                    animateInIconTranslation(view, statusViewMigrated)
                    // We were fading out, let's fade in instead
                    CrossFadeHelper.fadeIn(view, onAnimationEnd)
                }
            }
        }
    }

    private fun appearIcons(
        view: View,
        animate: Boolean,
        iconAppearTranslation: Int,
        statusViewMigrated: Boolean,
    ) {
        if (animate) {
            if (!statusViewMigrated) {
                view.translationY = -iconAppearTranslation.toFloat()
            }
            view.alpha = 0f
            animateInIconTranslation(view, statusViewMigrated)
            view
                .animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .start()
        } else {
            view.alpha = 1.0f
            if (!statusViewMigrated) {
                view.translationY = 0f
            }
        }
    }

    private fun animateInIconTranslation(view: View, statusViewMigrated: Boolean) {
        if (!statusViewMigrated) {
            view
                .animate()
                .setInterpolator(Interpolators.DECELERATE_QUINT)
                .translationY(0f)
                .setDuration(AOD_ICONS_APPEAR_DURATION)
                .start()
        }
    }

    private const val AOD_ICONS_APPEAR_DURATION: Long = 200

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

    /** External storage for [StatusBarIconView] instances. */
    fun interface IconViewStore {
        fun iconView(key: String): StatusBarIconView?
    }
}

/** [IconViewStore] for the [com.android.systemui.statusbar.NotificationShelf] */
class ShelfNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView? =
        notifCollection.getEntry(key)?.icons?.shelfIcon
}

/** [IconViewStore] for the always-on display. */
class AlwaysOnDisplayNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView? =
        notifCollection.getEntry(key)?.icons?.aodIcon
}

/** [IconViewStore] for the status bar. */
class StatusBarNotificationIconViewStore
@Inject
constructor(
    private val notifCollection: NotifCollection,
) : IconViewStore {
    override fun iconView(key: String): StatusBarIconView? =
        notifCollection.getEntry(key)?.icons?.statusBarIcon
}
