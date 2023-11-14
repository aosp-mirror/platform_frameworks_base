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
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.collection.ArrayMap
import androidx.lifecycle.lifecycleScope
import com.android.internal.policy.SystemBarUtils
import com.android.internal.statusbar.StatusBarIcon
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.icon.IconPack
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconColors
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData.LimitType
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import com.android.systemui.util.kotlin.mapValuesNotNullTo
import com.android.systemui.util.kotlin.stateFlow
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
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
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: ShelfNotificationIconViewStore,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            lifecycleScope.launch {
                viewModel.icons.bindIcons(
                    view,
                    configuration,
                    configurationController,
                    notifyBindingFailures = { failureTracker.shelfFailures = it },
                    viewStore,
                )
            }
        }
    }

    @JvmStatic
    fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerStatusBarViewModel,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: StatusBarNotificationIconViewStore,
    ): DisposableHandle {
        val contrastColorUtil = ContrastColorUtil.getInstance(view.context)
        return view.repeatWhenAttached {
            lifecycleScope.run {
                launch {
                    val iconColors: Flow<NotificationIconColors> =
                        viewModel.iconColors.mapNotNull { it.iconColors(view.viewBounds) }
                    viewModel.icons.bindIcons(
                        view,
                        configuration,
                        configurationController,
                        notifyBindingFailures = { failureTracker.statusBarFailures = it },
                        viewStore,
                    ) { _, sbiv ->
                        StatusBarIconViewBinder.bindIconColors(
                            sbiv,
                            iconColors,
                            contrastColorUtil,
                        )
                    }
                }
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
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: IconViewStore,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            lifecycleScope.launch {
                view.setUseIncreasedIconScale(true)
                launch {
                    viewModel.icons.bindIcons(
                        view,
                        configuration,
                        configurationController,
                        notifyBindingFailures = { failureTracker.aodFailures = it },
                        viewStore,
                    ) { _, sbiv ->
                        viewModel.bindAodStatusBarIconView(sbiv, configuration)
                    }
                }
                launch { viewModel.areContainerChangesAnimated.bindAnimationsEnabled(view) }
            }
        }
    }

    private suspend fun NotificationIconContainerAlwaysOnDisplayViewModel.bindAodStatusBarIconView(
        sbiv: StatusBarIconView,
        configuration: ConfigurationState,
    ) {
        coroutineScope {
            launch {
                val color: Flow<Int> =
                    configuration.getColorAttr(
                        R.attr.wallpaperTextColor,
                        DEFAULT_AOD_ICON_COLOR,
                    )
                StatusBarIconViewBinder.bindColor(sbiv, color)
            }
            launch { StatusBarIconViewBinder.bindTintAlpha(sbiv, tintAlpha) }
            launch { StatusBarIconViewBinder.bindAnimationsEnabled(sbiv, areIconAnimationsEnabled) }
        }
    }

    /** Binds to [NotificationIconContainer.setAnimationsEnabled] */
    private suspend fun Flow<Boolean>.bindAnimationsEnabled(view: NotificationIconContainer) {
        collect(view::setAnimationsEnabled)
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

    /**
     * Binds [NotificationIconsViewData] to a [NotificationIconContainer]'s children.
     *
     * [bindIcon] will be invoked to bind a child [StatusBarIconView] to an icon associated with the
     * given `iconKey`. The parent [Job] of this coroutine will be cancelled automatically when the
     * view is to be unbound.
     */
    private suspend fun Flow<NotificationIconsViewData>.bindIcons(
        view: NotificationIconContainer,
        configuration: ConfigurationState,
        configurationController: ConfigurationController,
        notifyBindingFailures: (Collection<String>) -> Unit,
        viewStore: IconViewStore,
        bindIcon: suspend (iconKey: String, view: StatusBarIconView) -> Unit = { _, _ -> },
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
        val failedBindings = mutableSetOf<String>()
        val boundViewsByNotifKey = ArrayMap<String, Pair<StatusBarIconView, Job>>()
        var prevIcons = NotificationIconsViewData()
        collect { iconsData: NotificationIconsViewData ->
            val iconsDiff = NotificationIconsViewData.computeDifference(iconsData, prevIcons)
            prevIcons = iconsData

            // Lookup 1:1 group icon replacements
            val replacingIcons: ArrayMap<String, StatusBarIcon> =
                iconsDiff.groupReplacements.mapValuesNotNullTo(ArrayMap()) { (_, notifKey) ->
                    boundViewsByNotifKey[notifKey]?.first?.statusBarIcon
                }
            view.withIconReplacements(replacingIcons) {
                // Remove and unbind.
                for (notifKey in iconsDiff.removed) {
                    failedBindings.remove(notifKey)
                    val (child, job) = boundViewsByNotifKey.remove(notifKey) ?: continue
                    view.removeView(child)
                    job.cancel()
                }

                // Add and bind.
                val toAdd: Sequence<String> = iconsDiff.added.asSequence() + failedBindings
                for ((idx, notifKey) in toAdd.withIndex()) {
                    // Lookup the StatusBarIconView from the store.
                    val sbiv = viewStore.iconView(notifKey)
                    if (sbiv == null) {
                        failedBindings.add(notifKey)
                        continue
                    }
                    // The view might still be transiently added if it was just removed and added
                    // again
                    view.removeTransientView(sbiv)
                    view.addView(sbiv, idx)
                    boundViewsByNotifKey.remove(notifKey)?.second?.cancel()
                    boundViewsByNotifKey[notifKey] =
                        Pair(
                            sbiv,
                            launch {
                                launch { layoutParams.collect { sbiv.layoutParams = it } }
                                bindIcon(notifKey, sbiv)
                            },
                        )
                }

                // Set the maximum number of icons to show in the container. Any icons over this
                // amount will render as an "overflow dot".
                val maxIconsAmount: Int =
                    when (iconsData.limitType) {
                        LimitType.MaximumIndex -> {
                            iconsData.visibleIcons
                                .asSequence()
                                .take(iconsData.iconLimit)
                                .count { info -> info.notifKey in boundViewsByNotifKey }
                        }
                        LimitType.MaximumAmount -> {
                            iconsData.iconLimit
                        }
                    }
                view.setMaxIconsAmount(maxIconsAmount)

                // Track the binding failures so that they appear in dumpsys.
                notifyBindingFailures(failedBindings)

                // Re-sort notification icons
                view.changeViewPositions {
                    val expectedChildren: List<StatusBarIconView> =
                        iconsData.visibleIcons.mapNotNull {
                            boundViewsByNotifKey[it.notifKey]?.first
                        }
                    val childCount = view.childCount
                    for (i in 0 until childCount) {
                        val actual = view.getChildAt(i)
                        val expected = expectedChildren[i]
                        if (actual === expected) {
                            continue
                        }
                        view.removeView(expected)
                        view.addView(expected, i)
                    }
                }
            }
            // Recalculate all icon positions, to reflect our updates.
            view.calculateIconXTranslations()
        }
    }

    /**
     * Track which groups are being replaced with a different icon instance, but with the same
     * visual icon. This prevents a weird animation where it looks like an icon disappears and
     * reappears unchanged.
     */
    // TODO(b/305739416): Ideally we wouldn't swap out the StatusBarIconView at all, and instead use
    //  a single SBIV instance for the group. Then this whole concept can go away.
    private inline fun <R> NotificationIconContainer.withIconReplacements(
        replacements: ArrayMap<String, StatusBarIcon>,
        block: () -> R
    ): R {
        setReplacingIcons(replacements)
        return block().also { setReplacingIcons(null) }
    }

    /**
     * Any invocations of [NotificationIconContainer.addView] /
     * [NotificationIconContainer.removeView] inside of [block] will not cause a new add / remove
     * animation.
     */
    private inline fun <R> NotificationIconContainer.changeViewPositions(block: () -> R): R {
        setChangingViewPositions(true)
        return block().also { setChangingViewPositions(false) }
    }

    /** External storage for [StatusBarIconView] instances. */
    fun interface IconViewStore {
        fun iconView(key: String): StatusBarIconView?
    }

    @ColorInt private val DEFAULT_AOD_ICON_COLOR = Color.WHITE
}

/** [IconViewStore] for the [com.android.systemui.statusbar.NotificationShelf] */
class ShelfNotificationIconViewStore @Inject constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.shelfIcon })

/** [IconViewStore] for the always-on display. */
class AlwaysOnDisplayNotificationIconViewStore
@Inject
constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.aodIcon })

/** [IconViewStore] for the status bar. */
class StatusBarNotificationIconViewStore @Inject constructor(notifCollection: NotifCollection) :
    IconViewStore by (notifCollection.iconViewStoreBy { it.statusBarIcon })

private fun NotifCollection.iconViewStoreBy(block: (IconPack) -> StatusBarIconView?) =
    IconViewStore { key ->
        getEntry(key)?.icons?.let(block)
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
