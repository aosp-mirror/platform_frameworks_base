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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Provider

/** ViewModel for the list of notifications. */
class NotificationListViewModel(
    val shelf: NotificationShelfViewModel,
    val footer: Optional<FooterViewModel>,
)

@Module
object NotificationListViewModelModule {
    @JvmStatic
    @Provides
    @SysUISingleton
    fun maybeProvideViewModel(
        featureFlags: FeatureFlagsClassic,
        shelfViewModel: Provider<NotificationShelfViewModel>,
        footerViewModel: Provider<FooterViewModel>,
    ): Optional<NotificationListViewModel> {
        return if (featureFlags.isEnabled(Flags.NOTIFICATION_SHELF_REFACTOR)) {
            if (com.android.systemui.Flags.notificationsFooterViewRefactor()) {
                Optional.of(
                    NotificationListViewModel(
                        shelfViewModel.get(),
                        Optional.of(footerViewModel.get())
                    )
                )
            } else {
                Optional.of(NotificationListViewModel(shelfViewModel.get(), Optional.empty()))
            }
        } else {
            if (com.android.systemui.Flags.notificationsFooterViewRefactor()) {
                throw IllegalStateException(
                    "The com.android.systemui.notifications_footer_view_refactor flag requires " +
                        "the notification_shelf_refactor flag to be enabled. First disable the " +
                        "footer flag using `adb shell device_config put systemui " +
                        "com.android.systemui.notifications_footer_view_refactor false`, then " +
                        "enable the notification_shelf_refactor flag in Flag Flipper. " +
                        "Afterwards, you can try re-enabling the footer refactor flag via adb."
                )
            }
            Optional.empty()
        }
    }
}
