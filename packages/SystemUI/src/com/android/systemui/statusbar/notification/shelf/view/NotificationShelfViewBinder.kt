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

package com.android.systemui.statusbar.notification.shelf.view

import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.accessibility.AccessibilityManager
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.LegacyNotificationShelfControllerImpl
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.notification.row.ActivatableNotificationViewController
import com.android.systemui.statusbar.notification.row.ExpandableOutlineViewController
import com.android.systemui.statusbar.notification.row.ExpandableViewController
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.NotificationTapHelper
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import dagger.Binds
import dagger.Module
import javax.inject.Inject

/** Binds a [NotificationShelf] to its backend. */
interface NotificationShelfViewBinder {
    fun bind(shelf: NotificationShelf)
}

/**
 * Controller class for [NotificationShelf]. This implementation serves as a temporary wrapper
 * around a [NotificationShelfViewBinder], so that external code can continue to depend on the
 * [NotificationShelfController] interface. Once the [LegacyNotificationShelfControllerImpl] is
 * removed, this class can go away and the ViewBinder can be used directly.
 */
@CentralSurfacesScope
class NotificationShelfViewBinderWrapperControllerImpl
@Inject
constructor(
    private val shelf: NotificationShelf,
    private val viewBinder: NotificationShelfViewBinder,
    private val keyguardBypassController: KeyguardBypassController,
    featureFlags: FeatureFlags,
    private val notifTapHelperFactory: NotificationTapHelper.Factory,
    private val a11yManager: AccessibilityManager,
    private val falsingManager: FalsingManager,
    private val falsingCollector: FalsingCollector,
    private val statusBarStateController: SysuiStatusBarStateController,
) : NotificationShelfController {

    private var ambientState: AmbientState? = null

    override val view: NotificationShelf
        get() = shelf

    init {
        shelf.apply {
            useRoundnessSourceTypes(featureFlags.isEnabled(Flags.USE_ROUNDNESS_SOURCETYPES))
            setSensitiveRevealAnimEndabled(featureFlags.isEnabled(Flags.SENSITIVE_REVEAL_ANIM))
        }
    }

    fun init() {
        viewBinder.bind(shelf)

        ActivatableNotificationViewController(
                shelf,
                notifTapHelperFactory,
                ExpandableOutlineViewController(shelf, ExpandableViewController(shelf)),
                a11yManager,
                falsingManager,
                falsingCollector,
            )
            .init()
        shelf.setController(this)
        val onAttachStateListener =
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    statusBarStateController.addCallback(
                        shelf,
                        SysuiStatusBarStateController.RANK_SHELF,
                    )
                }

                override fun onViewDetachedFromWindow(v: View) {
                    statusBarStateController.removeCallback(shelf)
                }
            }
        shelf.addOnAttachStateChangeListener(onAttachStateListener)
        if (shelf.isAttachedToWindow) {
            onAttachStateListener.onViewAttachedToWindow(shelf)
        }
    }

    override val intrinsicHeight: Int
        get() = shelf.intrinsicHeight

    override val shelfIcons: NotificationIconContainer
        get() = shelf.shelfIcons

    override fun canModifyColorOfNotifications(): Boolean {
        return (ambientState?.isShadeExpanded == true &&
            !(ambientState?.isOnKeyguard == true && keyguardBypassController.bypassEnabled))
    }

    override fun setOnActivatedListener(listener: ActivatableNotificationView.OnActivatedListener) {
        shelf.setOnActivatedListener(listener)
    }

    override fun bind(
        ambientState: AmbientState,
        notificationStackScrollLayoutController: NotificationStackScrollLayoutController
    ) {
        shelf.bind(ambientState, notificationStackScrollLayoutController)
        this.ambientState = ambientState
    }

    override fun setOnClickListener(listener: View.OnClickListener) {
        shelf.setOnClickListener(listener)
    }
}

@Module(includes = [PrivateShelfViewBinderModule::class]) object NotificationShelfViewBinderModule

@Module
private interface PrivateShelfViewBinderModule {
    @Binds fun bindImpl(impl: NotificationShelfViewBinderImpl): NotificationShelfViewBinder
}

@CentralSurfacesScope
private class NotificationShelfViewBinderImpl @Inject constructor() : NotificationShelfViewBinder {
    override fun bind(shelf: NotificationShelf) {}
}
