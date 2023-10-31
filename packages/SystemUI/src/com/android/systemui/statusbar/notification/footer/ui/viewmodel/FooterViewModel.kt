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

package com.android.systemui.statusbar.notification.footer.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ViewModel for [FooterView]. */
class FooterViewModel(seenNotificationsInteractor: SeenNotificationsInteractor) {
    val message: Flow<FooterMessageViewModel> =
        seenNotificationsInteractor.hasFilteredOutSeenNotifications.map { hasFilteredOutNotifs ->
            FooterMessageViewModel(
                messageId = R.string.unlock_to_see_notif_text,
                iconId = R.drawable.ic_friction_lock_closed,
                visible = hasFilteredOutNotifs,
            )
        }
}

@Module
object FooterViewModelModule {
    @Provides
    @SysUISingleton
    fun provideOptional(
        seenNotificationsInteractor: Provider<SeenNotificationsInteractor>,
    ): Optional<FooterViewModel> {
        return if (FooterViewRefactor.isEnabled) {
            Optional.of(FooterViewModel(seenNotificationsInteractor.get()))
        } else {
            Optional.empty()
        }
    }
}
