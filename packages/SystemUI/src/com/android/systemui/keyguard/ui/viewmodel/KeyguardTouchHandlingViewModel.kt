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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Models UI state to support top-level touch handling in the lock screen. */
@SysUISingleton
class KeyguardTouchHandlingViewModel
@Inject
constructor(
    private val interactor: KeyguardTouchHandlingInteractor,
) {

    /** Whether the long-press handling feature should be enabled. */
    val isLongPressHandlingEnabled: Flow<Boolean> = interactor.isLongPressHandlingEnabled

    /** Notifies that the user has long-pressed on the lock screen.
     *
     * @param isA11yAction: Whether the action was performed as an a11y action
     */
    fun onLongPress(isA11yAction: Boolean) {
        interactor.onLongPress(isA11yAction)
    }

    /**
     * Notifies that some input gesture has started somewhere outside of the lock screen settings
     * menu item pop-up.
     */
    fun onTouchedOutside() {
        interactor.onTouchedOutside()
    }

    /** Notifies that the lockscreen has been clicked at position [x], [y]. */
    fun onClick(x: Float, y: Float) {
        interactor.onClick(x, y)
    }

    /** Notifies that the lockscreen has been double clicked. */
    fun onDoubleClick() {
        interactor.onDoubleClick()
    }
}
