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
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.collection.ArrayMap
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.traceSection
import com.android.internal.R as RInternal
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
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData.LimitType
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.util.kotlin.mapValuesNotNullTo
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Binds a view-model to a [NotificationIconContainer]. */
object NotificationIconContainerViewBinder {

    suspend fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerStatusBarViewModel,
        configuration: ConfigurationState,
        systemBarUtilsState: SystemBarUtilsState,
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: IconViewStore,
    ): Unit = coroutineScope {
        launch {
            val contrastColorUtil = ContrastColorUtil.getInstance(view.context)
            val iconColors: StateFlow<NotificationIconColors> =
                viewModel.iconColors.mapNotNull { it.iconColors(view.viewBounds) }.stateIn(this)
            viewModel.icons.bindIcons(
                logTag = "statusbar",
                view = view,
                configuration = configuration,
                systemBarUtilsState = systemBarUtilsState,
                notifyBindingFailures = { failureTracker.statusBarFailures = it },
                viewStore = viewStore,
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

    @JvmStatic
    fun bindWhileAttached(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
        configuration: ConfigurationState,
        systemBarUtilsState: SystemBarUtilsState,
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: IconViewStore,
    ): DisposableHandle {
        return view.repeatWhenAttached {
            lifecycleScope.launch {
                bind(view, viewModel, configuration, systemBarUtilsState, failureTracker, viewStore)
            }
        }
    }

    suspend fun bind(
        view: NotificationIconContainer,
        viewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
        configuration: ConfigurationState,
        systemBarUtilsState: SystemBarUtilsState,
        failureTracker: StatusBarIconViewBindingFailureTracker,
        viewStore: IconViewStore,
    ): Unit = coroutineScope {
        view.setUseIncreasedIconScale(true)
        launch {
            // Collect state shared across all icon views, so that we are not duplicating collects
            // for each individual icon.
            val color: StateFlow<Int> =
                configuration
                    .getColorAttr(R.attr.wallpaperTextColor, DEFAULT_AOD_ICON_COLOR)
                    .stateIn(this)
            val tintAlpha = viewModel.tintAlpha.stateIn(this)
            val animsEnabled = viewModel.areIconAnimationsEnabled.stateIn(this)
            viewModel.icons.bindIcons(
                logTag = "aod",
                view = view,
                configuration = configuration,
                systemBarUtilsState = systemBarUtilsState,
                notifyBindingFailures = { failureTracker.aodFailures = it },
                viewStore = viewStore,
            ) { _, sbiv ->
                coroutineScope {
                    launch { StatusBarIconViewBinder.bindColor(sbiv, color) }
                    launch { StatusBarIconViewBinder.bindTintAlpha(sbiv, tintAlpha) }
                    launch { StatusBarIconViewBinder.bindAnimationsEnabled(sbiv, animsEnabled) }
                }
            }
        }
        launch { viewModel.areContainerChangesAnimated.bindAnimationsEnabled(view) }
    }

    /** Binds to [NotificationIconContainer.setAnimationsEnabled] */
    private suspend fun Flow<Boolean>.bindAnimationsEnabled(view: NotificationIconContainer) {
        collectTracingEach("NIC#bindAnimationsEnabled", view::setAnimationsEnabled)
    }

    private suspend fun NotificationIconContainerStatusBarViewModel.bindIsolatedIcon(
        view: NotificationIconContainer,
        viewStore: IconViewStore,
    ) {
        coroutineScope {
            launch {
                isolatedIconLocation.collectTracingEach("NIC#isolatedIconLocation") { location ->
                    view.setIsolatedIconLocation(location, true)
                }
            }
            launch {
                isolatedIcon.collectTracingEach("NIC#showIconIsolated") { iconInfo ->
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
    suspend fun Flow<NotificationIconsViewData>.bindIcons(
        logTag: String,
        view: NotificationIconContainer,
        configuration: ConfigurationState,
        systemBarUtilsState: SystemBarUtilsState,
        notifyBindingFailures: (Collection<String>) -> Unit,
        viewStore: IconViewStore,
        bindIcon: suspend (iconKey: String, view: StatusBarIconView) -> Unit = { _, _ -> },
    ): Unit = coroutineScope {
        val iconSizeFlow: Flow<Int> =
            configuration.getDimensionPixelSize(RInternal.dimen.status_bar_icon_size_sp)
        val iconHorizontalPaddingFlow: Flow<Int> =
            configuration.getDimensionPixelSize(R.dimen.status_bar_icon_horizontal_margin)
        val layoutParams: StateFlow<FrameLayout.LayoutParams> =
            combine(iconSizeFlow, iconHorizontalPaddingFlow, systemBarUtilsState.statusBarHeight) {
                    iconSize,
                    iconHPadding,
                    statusBarHeight,
                    ->
                    FrameLayout.LayoutParams(iconSize + 2 * iconHPadding, statusBarHeight)
                }
                .stateIn(this)
        try {
            bindIcons(logTag, view, layoutParams, notifyBindingFailures, viewStore, bindIcon)
        } finally {
            // Detach everything so that child SBIVs don't hold onto a reference to the container.
            view.detachAllIcons()
        }
    }

    private suspend fun Flow<NotificationIconsViewData>.bindIcons(
        logTag: String,
        view: NotificationIconContainer,
        layoutParams: StateFlow<FrameLayout.LayoutParams>,
        notifyBindingFailures: (Collection<String>) -> Unit,
        viewStore: IconViewStore,
        bindIcon: suspend (iconKey: String, view: StatusBarIconView) -> Unit,
    ): Unit = coroutineScope {
        val failedBindings = mutableSetOf<String>()
        val boundViewsByNotifKey = ArrayMap<String, Pair<StatusBarIconView, Job>>()
        var prevIcons = NotificationIconsViewData()
        collectTracingEach({ "NIC($logTag)#bindIcons" }) { iconsData: NotificationIconsViewData ->
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
                    traceSection("removeIcon") {
                        view.removeView(child)
                        job.cancel()
                    }
                }

                // Add and bind.
                val toAdd: Sequence<String> = iconsDiff.added.asSequence() + failedBindings.toList()
                for (notifKey in toAdd) {
                    // Lookup the StatusBarIconView from the store.
                    val sbiv = viewStore.iconView(notifKey)
                    if (sbiv == null) {
                        failedBindings.add(notifKey)
                        continue
                    }
                    failedBindings.remove(notifKey)
                    traceSection("addIcon") {
                        (sbiv.parent as? ViewGroup)?.run {
                            if (this !== view) {
                                Log.wtf(
                                    TAG,
                                    "[$logTag] SBIV($notifKey) has an unexpected parent",
                                )
                            }
                            // If the container was re-inflated and re-bound, then SBIVs might still
                            // be attached to the prior view.
                            removeView(sbiv)
                            // The view might still be transiently added if it was just removed and
                            // added again.
                            removeTransientView(sbiv)
                        }
                        view.addView(sbiv, layoutParams.value)
                        boundViewsByNotifKey.remove(notifKey)?.second?.cancel()
                        boundViewsByNotifKey[notifKey] =
                            Pair(
                                sbiv,
                                launch {
                                    launch {
                                        layoutParams.collectTracingEach(
                                            tag = { "[$logTag] SBIV#bindLayoutParams" },
                                        ) {
                                            if (it != sbiv.layoutParams) {
                                                sbiv.layoutParams = it
                                            }
                                        }
                                    }
                                    bindIcon(notifKey, sbiv)
                                },
                            )
                    }
                }

                // Set the maximum number of icons to show in the container. Any icons over this
                // amount will render as an "overflow dot".
                val maxIconsAmount: Int =
                    when (iconsData.limitType) {
                        LimitType.MaximumIndex -> {
                            iconsData.visibleIcons.asSequence().take(iconsData.iconLimit).count {
                                info ->
                                info.notifKey in boundViewsByNotifKey
                            }
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
                    traceSection("re-sort") {
                        val expectedChildren: List<StatusBarIconView> =
                            iconsData.visibleIcons.mapNotNull {
                                boundViewsByNotifKey[it.notifKey]?.first
                            }
                        val childCount = view.childCount
                        val toRemove = mutableListOf<View>()
                        for (i in 0 until childCount) {
                            val actual = view.getChildAt(i)
                            val expected = expectedChildren.getOrNull(i)
                            if (expected == null) {
                                Log.wtf(TAG, "[$logTag] Unexpected child $actual")
                                toRemove.add(actual)
                                continue
                            }
                            if (actual === expected) {
                                continue
                            }
                            view.removeView(expected)
                            view.addView(expected, i)
                        }
                        for (child in toRemove) {
                            view.removeView(child)
                        }
                    }
                }
            }
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

    @ColorInt private const val DEFAULT_AOD_ICON_COLOR = Color.WHITE
    private const val TAG = "NotifIconContainerViewBinder"
}

/**
 * Convenience builder for [IconViewStore] that uses [block] to extract the relevant
 * [StatusBarIconView] from an [IconPack] stored inside of the [NotifCollection].
 */
fun NotifCollection.iconViewStoreBy(block: (IconPack) -> StatusBarIconView?) =
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

private suspend inline fun <T> Flow<T>.collectTracingEach(
    tag: String,
    crossinline collector: (T) -> Unit,
) = collect { traceSection(tag) { collector(it) } }

private suspend inline fun <T> Flow<T>.collectTracingEach(
    noinline tag: () -> String,
    crossinline collector: (T) -> Unit,
) {
    val lazyTag = lazy(mode = LazyThreadSafetyMode.PUBLICATION, tag)
    collect { traceSection({ lazyTag.value }) { collector(it) } }
}
