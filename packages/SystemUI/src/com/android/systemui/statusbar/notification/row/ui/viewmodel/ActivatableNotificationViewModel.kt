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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ViewModel for [com.android.systemui.statusbar.notification.row.ActivatableNotificationView]. */
interface ActivatableNotificationViewModel : ExpandableOutlineViewModel {
    /** Does the view react to touches? */
    val isTouchable: Flow<Boolean>

    companion object {
        operator fun invoke(
            a11yInteractor: AccessibilityInteractor,
        ): ActivatableNotificationViewModel = ActivatableNotificationViewModelImpl(a11yInteractor)
    }
}

private class ActivatableNotificationViewModelImpl(
    a11yInteractor: AccessibilityInteractor,
) : ActivatableNotificationViewModel {
    override val isTouchable: Flow<Boolean> =
        // If a11y touch exploration is enabled, then the activatable view should ignore touches
        a11yInteractor.isTouchExplorationEnabled.map { !it }
}

@Module
object ActivatableNotificationViewModelModule {
    @Provides
    fun provideViewModel(interactor: AccessibilityInteractor) =
        ActivatableNotificationViewModel(interactor)
}
