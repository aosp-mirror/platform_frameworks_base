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

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Trace
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.collection.ArrayMap
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.RefactorFlag
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinderWrapperControllerImpl
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import java.util.function.Function
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Controller class for [NotificationIconContainer]. This implementation serves as a temporary
 * wrapper around [NotificationIconContainerViewBinder], so that external code can continue to
 * depend on the [NotificationIconAreaController] interface. Once
 * [LegacyNotificationIconAreaControllerImpl] is removed, this class can go away and the ViewBinder
 * can be used directly.
 */
@SysUISingleton
class NotificationIconAreaControllerViewBinderWrapperImpl
@Inject
constructor(
    private val context: Context,
    private val configuration: ConfigurationState,
    private val wakeUpCoordinator: NotificationWakeUpCoordinator,
    private val bypassController: KeyguardBypassController,
    private val mediaManager: NotificationMediaManager,
    notificationListener: NotificationListener,
    private val dozeParameters: DozeParameters,
    private val sectionStyleProvider: SectionStyleProvider,
    private val bubblesOptional: Optional<Bubbles>,
    demoModeController: DemoModeController,
    private val featureFlags: FeatureFlagsClassic,
    private val statusBarWindowController: StatusBarWindowController,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val shelfIconsViewModel: NotificationIconContainerShelfViewModel,
    private val statusBarIconsViewModel: NotificationIconContainerStatusBarViewModel,
    private val aodIconsViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
) : NotificationIconAreaController, NotificationWakeUpCoordinator.WakeUpListener, DemoMode {

    private val updateStatusBarIcons = Runnable { updateStatusBarIcons() }
    private val shelfRefactor = RefactorFlag(featureFlags, Flags.NOTIFICATION_SHELF_REFACTOR)

    private var iconSize = 0
    private var iconHPadding = 0
    private var notificationEntries = listOf<ListEntry>()
    private var notificationIconArea: View? = null
    private var notificationIcons: NotificationIconContainer? = null
    private var shelfIcons: NotificationIconContainer? = null
    private var aodIcons: NotificationIconContainer? = null
    private var aodBindJob: DisposableHandle? = null
    private var showLowPriority = true

    @VisibleForTesting
    val settingsListener: NotificationListener.NotificationSettingsListener =
        object : NotificationListener.NotificationSettingsListener {
            override fun onStatusBarIconsBehaviorChanged(hideSilentStatusIcons: Boolean) {
                showLowPriority = !hideSilentStatusIcons
                updateStatusBarIcons()
            }
        }

    init {
        wakeUpCoordinator.addListener(this)
        demoModeController.addCallback(this)
        notificationListener.addNotificationSettingsListener(settingsListener)
        initializeNotificationAreaViews(context)
    }

    @VisibleForTesting
    fun shouldShowLowPriorityIcons(): Boolean {
        return showLowPriority
    }

    /** Called by the Keyguard*ViewController whose view contains the aod icons. */
    override fun setupAodIcons(aodIcons: NotificationIconContainer) {
        val changed = this.aodIcons != null && aodIcons !== this.aodIcons
        if (changed) {
            this.aodIcons!!.setAnimationsEnabled(false)
            this.aodIcons!!.removeAllViews()
            aodBindJob?.dispose()
        }
        this.aodIcons = aodIcons
        this.aodIcons!!.setOnLockScreen(true)
        aodBindJob =
            NotificationIconContainerViewBinder.bind(
                aodIcons,
                aodIconsViewModel,
                configuration,
                dozeParameters,
                featureFlags,
                screenOffAnimationController,
            )
        if (changed) {
            updateAodNotificationIcons()
        }
        updateIconLayoutParams(context)
    }

    override fun setupShelf(notificationShelfController: NotificationShelfController) =
        NotificationShelfViewBinderWrapperControllerImpl.unsupported

    override fun setShelfIcons(icons: NotificationIconContainer) {
        if (shelfRefactor.isUnexpectedlyInLegacyMode()) {
            NotificationIconContainerViewBinder.bind(
                icons,
                shelfIconsViewModel,
                configuration,
                dozeParameters,
                featureFlags,
                screenOffAnimationController,
            )
            shelfIcons = icons
        }
    }

    override fun onDensityOrFontScaleChanged(context: Context) {
        updateIconLayoutParams(context)
    }

    /** Returns the view that represents the notification area. */
    override fun getNotificationInnerAreaView(): View? {
        return notificationIconArea
    }

    /** Updates the notifications with the given list of notifications to display. */
    override fun updateNotificationIcons(entries: List<ListEntry>) {
        notificationEntries = entries
        updateNotificationIcons()
    }

    private fun updateStatusBarIcons() {
        updateIconsForLayout(
            { entry: NotificationEntry -> entry.icons.statusBarIcon },
            notificationIcons,
            showAmbient = false /* showAmbient */,
            showLowPriority = showLowPriority,
            hideDismissed = true /* hideDismissed */,
            hideRepliedMessages = true /* hideRepliedMessages */,
            hideCurrentMedia = false /* hideCurrentMedia */,
            hidePulsing = false /* hidePulsing */
        )
    }

    override fun updateAodNotificationIcons() {
        if (aodIcons == null) {
            return
        }
        updateIconsForLayout(
            { entry: NotificationEntry -> entry.icons.aodIcon },
            aodIcons,
            showAmbient = false /* showAmbient */,
            showLowPriority = true /* showLowPriority */,
            hideDismissed = true /* hideDismissed */,
            hideRepliedMessages = true /* hideRepliedMessages */,
            hideCurrentMedia = true /* hideCurrentMedia */,
            hidePulsing = bypassController.bypassEnabled /* hidePulsing */
        )
    }

    override fun showIconIsolated(icon: StatusBarIconView?, animated: Boolean) {
        notificationIcons!!.showIconIsolated(icon, animated)
    }

    override fun setIsolatedIconLocation(iconDrawingRect: Rect, requireStateUpdate: Boolean) {
        notificationIcons!!.setIsolatedIconLocation(iconDrawingRect, requireStateUpdate)
    }

    override fun setAnimationsEnabled(enabled: Boolean) = unsupported

    override fun onThemeChanged() = unsupported

    override fun getHeight(): Int {
        return if (aodIcons == null) 0 else aodIcons!!.height
    }

    override fun onFullyHiddenChanged(isFullyHidden: Boolean) {
        updateAodNotificationIcons()
    }

    override fun demoCommands(): List<String> {
        val commands = ArrayList<String>()
        commands.add(DemoMode.COMMAND_NOTIFICATIONS)
        return commands
    }

    override fun dispatchDemoCommand(command: String, args: Bundle) {
        if (notificationIconArea != null) {
            val visible = args.getString("visible")
            val vis = if ("false" == visible) View.INVISIBLE else View.VISIBLE
            notificationIconArea?.visibility = vis
        }
    }

    override fun onDemoModeFinished() {
        if (notificationIconArea != null) {
            notificationIconArea?.visibility = View.VISIBLE
        }
    }

    private fun inflateIconArea(inflater: LayoutInflater): View {
        return inflater.inflate(R.layout.notification_icon_area, null)
    }

    /** Initializes the views that will represent the notification area. */
    private fun initializeNotificationAreaViews(context: Context) {
        reloadDimens(context)
        val layoutInflater = LayoutInflater.from(context)
        notificationIconArea = inflateIconArea(layoutInflater)
        notificationIcons = notificationIconArea?.findViewById(R.id.notificationIcons)
        NotificationIconContainerViewBinder.bind(
            notificationIcons!!,
            statusBarIconsViewModel,
            configuration,
            dozeParameters,
            featureFlags,
            screenOffAnimationController,
        )
    }

    private fun updateIconLayoutParams(context: Context) {
        reloadDimens(context)
        val params = generateIconLayoutParams()
        for (i in 0 until notificationIcons!!.childCount) {
            val child = notificationIcons!!.getChildAt(i)
            child.layoutParams = params
        }
        if (shelfIcons != null) {
            for (i in 0 until shelfIcons!!.childCount) {
                val child = shelfIcons!!.getChildAt(i)
                child.layoutParams = params
            }
        }
        if (aodIcons != null) {
            for (i in 0 until aodIcons!!.childCount) {
                val child = aodIcons!!.getChildAt(i)
                child.layoutParams = params
            }
        }
    }

    private fun generateIconLayoutParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            iconSize + 2 * iconHPadding,
            statusBarWindowController.statusBarHeight
        )
    }

    private fun reloadDimens(context: Context) {
        val res = context.resources
        iconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size_sp)
        iconHPadding = res.getDimensionPixelSize(R.dimen.status_bar_icon_horizontal_margin)
    }

    private fun shouldShowNotificationIcon(
        entry: NotificationEntry,
        showAmbient: Boolean,
        showLowPriority: Boolean,
        hideDismissed: Boolean,
        hideRepliedMessages: Boolean,
        hideCurrentMedia: Boolean,
        hidePulsing: Boolean
    ): Boolean {
        if (!showAmbient && sectionStyleProvider.isMinimized(entry)) {
            return false
        }
        if (hideCurrentMedia && entry.key == mediaManager.mediaNotificationKey) {
            return false
        }
        if (!showLowPriority && sectionStyleProvider.isSilent(entry)) {
            return false
        }
        if (entry.isRowDismissed && hideDismissed) {
            return false
        }
        if (hideRepliedMessages && entry.isLastMessageFromReply) {
            return false
        }
        // showAmbient == show in shade but not shelf
        if (!showAmbient && entry.shouldSuppressStatusBar()) {
            return false
        }
        if (
            hidePulsing &&
                entry.showingPulsing() &&
                (!wakeUpCoordinator.notificationsFullyHidden || !entry.isPulseSuppressed)
        ) {
            return false
        }
        return if (bubblesOptional.isPresent && bubblesOptional.get().isBubbleExpanded(entry.key)) {
            false
        } else true
    }

    private fun updateNotificationIcons() {
        Trace.beginSection("NotificationIconAreaController.updateNotificationIcons")
        updateStatusBarIcons()
        updateShelfIcons()
        updateAodNotificationIcons()
        Trace.endSection()
    }

    private fun updateShelfIcons() {
        if (shelfIcons == null) {
            return
        }
        updateIconsForLayout(
            { entry: NotificationEntry -> entry.icons.shelfIcon },
            shelfIcons,
            showAmbient = true,
            showLowPriority = true,
            hideDismissed = false,
            hideRepliedMessages = false,
            hideCurrentMedia = false,
            hidePulsing = false
        )
    }

    /**
     * Updates the notification icons for a host layout. This will ensure that the notification host
     * layout will have the same icons like the ones in here.
     *
     * @param function A function to look up an icon view based on an entry
     * @param hostLayout which layout should be updated
     * @param showAmbient should ambient notification icons be shown
     * @param showLowPriority should icons from silent notifications be shown
     * @param hideDismissed should dismissed icons be hidden
     * @param hideRepliedMessages should messages that have been replied to be hidden
     * @param hidePulsing should pulsing notifications be hidden
     */
    private fun updateIconsForLayout(
        function: Function<NotificationEntry, StatusBarIconView?>,
        hostLayout: NotificationIconContainer?,
        showAmbient: Boolean,
        showLowPriority: Boolean,
        hideDismissed: Boolean,
        hideRepliedMessages: Boolean,
        hideCurrentMedia: Boolean,
        hidePulsing: Boolean,
    ) {
        val toShow = ArrayList<StatusBarIconView>(notificationEntries.size)
        // Filter out ambient notifications and notification children.
        for (i in notificationEntries.indices) {
            val entry = notificationEntries[i].representativeEntry
            if (entry != null && entry.row != null) {
                if (
                    shouldShowNotificationIcon(
                        entry,
                        showAmbient,
                        showLowPriority,
                        hideDismissed,
                        hideRepliedMessages,
                        hideCurrentMedia,
                        hidePulsing
                    )
                ) {
                    val iconView = function.apply(entry)
                    if (iconView != null) {
                        toShow.add(iconView)
                    }
                }
            }
        }

        // In case we are changing the suppression of a group, the replacement shouldn't flicker
        // and it should just be replaced instead. We therefore look for notifications that were
        // just replaced by the child or vice-versa to suppress this.
        val replacingIcons = ArrayMap<String, ArrayList<StatusBarIcon>>()
        val toRemove = ArrayList<View>()
        for (i in 0 until hostLayout!!.childCount) {
            val child = hostLayout.getChildAt(i) as? StatusBarIconView ?: continue
            if (!toShow.contains(child)) {
                var iconWasReplaced = false
                val removedGroupKey = child.notification.groupKey
                for (j in toShow.indices) {
                    val candidate = toShow[j]
                    if (
                        candidate.sourceIcon.sameAs(child.sourceIcon) &&
                            candidate.notification.groupKey == removedGroupKey
                    ) {
                        if (!iconWasReplaced) {
                            iconWasReplaced = true
                        } else {
                            iconWasReplaced = false
                            break
                        }
                    }
                }
                if (iconWasReplaced) {
                    var statusBarIcons = replacingIcons[removedGroupKey]
                    if (statusBarIcons == null) {
                        statusBarIcons = ArrayList()
                        replacingIcons[removedGroupKey] = statusBarIcons
                    }
                    statusBarIcons.add(child.statusBarIcon)
                }
                toRemove.add(child)
            }
        }
        // removing all duplicates
        val duplicates = ArrayList<String?>()
        for (key in replacingIcons.keys) {
            val statusBarIcons = replacingIcons[key]!!
            if (statusBarIcons.size != 1) {
                duplicates.add(key)
            }
        }
        replacingIcons.removeAll(duplicates)
        hostLayout.setReplacingIcons(replacingIcons)
        val toRemoveCount = toRemove.size
        for (i in 0 until toRemoveCount) {
            hostLayout.removeView(toRemove[i])
        }
        val params = generateIconLayoutParams()
        for (i in toShow.indices) {
            val v = toShow[i]
            // The view might still be transiently added if it was just removed and added again
            hostLayout.removeTransientView(v)
            if (v.parent == null) {
                if (hideDismissed) {
                    v.setOnDismissListener(updateStatusBarIcons)
                }
                hostLayout.addView(v, i, params)
            }
        }
        hostLayout.setChangingViewPositions(true)
        // Re-sort notification icons
        val childCount = hostLayout.childCount
        for (i in 0 until childCount) {
            val actual = hostLayout.getChildAt(i)
            val expected = toShow[i]
            if (actual === expected) {
                continue
            }
            hostLayout.removeView(expected)
            hostLayout.addView(expected, i)
        }
        hostLayout.setChangingViewPositions(false)
        hostLayout.setReplacingIcons(null)
    }

    companion object {
        val unsupported: Nothing
            get() =
                error(
                    "Code path not supported when NOTIFICATION_ICON_CONTAINER_REFACTOR is disabled"
                )
    }
}
