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

package com.android.systemui.statusbar.notification.shelf.ui.viewbinder

import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.LegacyNotificationShelfControllerImpl
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.notification.row.ActivatableNotificationViewController
import com.android.systemui.statusbar.notification.row.ExpandableOutlineViewController
import com.android.systemui.statusbar.notification.row.ExpandableViewController
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.NotificationTapHelper
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import com.android.systemui.util.kotlin.getValue
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
    private val viewModel: NotificationShelfViewModel,
    featureFlags: FeatureFlags,
    private val notifTapHelperFactory: NotificationTapHelper.Factory,
    private val a11yManager: AccessibilityManager,
    private val falsingManager: FalsingManager,
    private val falsingCollector: FalsingCollector,
    hostControllerLazy: Lazy<NotificationStackScrollLayoutController>,
) : NotificationShelfController {

    private val hostController: NotificationStackScrollLayoutController by hostControllerLazy

    override val view: NotificationShelf
        get() = unsupported

    init {
        shelf.apply {
            setRefactorFlagEnabled(featureFlags.isEnabled(Flags.NOTIFICATION_SHELF_REFACTOR))
            useRoundnessSourceTypes(featureFlags.isEnabled(Flags.USE_ROUNDNESS_SOURCETYPES))
            setSensitiveRevealAnimEndabled(featureFlags.isEnabled(Flags.SENSITIVE_REVEAL_ANIM))
        }
    }

    fun init() {
        NotificationShelfViewBinder.bind(viewModel, shelf)

        ActivatableNotificationViewController(
                shelf,
                notifTapHelperFactory,
                ExpandableOutlineViewController(shelf, ExpandableViewController(shelf)),
                a11yManager,
                falsingManager,
                falsingCollector,
            )
            .init()
        hostController.setShelf(shelf)
        hostController.setOnNotificationRemovedListener { child, _ ->
            view.requestRoundnessResetFor(child)
        }
    }

    override val intrinsicHeight: Int
        get() = shelf.intrinsicHeight

    override val shelfIcons: NotificationIconContainer
        get() = shelf.shelfIcons

    override fun canModifyColorOfNotifications(): Boolean = unsupported

    override fun setOnActivatedListener(listener: ActivatableNotificationView.OnActivatedListener) {
        shelf.setOnActivatedListener(listener)
    }

    override fun bind(
        ambientState: AmbientState,
        notificationStackScrollLayoutController: NotificationStackScrollLayoutController,
    ) = unsupported

    override fun setOnClickListener(listener: View.OnClickListener) {
        shelf.setOnClickListener(listener)
    }

    private val unsupported: Nothing
        get() = NotificationShelfController.throwIllegalFlagStateError(expected = true)
}

/** Binds a [NotificationShelf] to its backend. */
object NotificationShelfViewBinder {
    fun bind(viewModel: NotificationShelfViewModel, shelf: NotificationShelf) {
        shelf.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.canModifyColorOfNotifications
                    .onEach(shelf::setCanModifyColorOfNotifications)
                    .launchIn(this)
                viewModel.isClickable.onEach(shelf::setCanInteract).launchIn(this)
            }
        }
    }
}
