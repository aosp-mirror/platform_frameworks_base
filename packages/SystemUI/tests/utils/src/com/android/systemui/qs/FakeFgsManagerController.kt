/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs

import com.android.systemui.animation.Expandable
import com.android.systemui.qs.FgsManagerController.OnDialogDismissedListener
import com.android.systemui.qs.FgsManagerController.OnNumberOfPackagesChangedListener
import kotlinx.coroutines.flow.MutableStateFlow

/** A fake [FgsManagerController] to be used in tests. */
class FakeFgsManagerController(
    isAvailable: Boolean = true,
    showFooterDot: Boolean = false,
    numRunningPackages: Int = 0,
) : FgsManagerController {
    override val isAvailable: MutableStateFlow<Boolean> = MutableStateFlow(isAvailable)

    override var numRunningPackages = numRunningPackages
        set(value) {
            if (value != field) {
                field = value
                newChangesSinceDialogWasDismissed = true
                numRunningPackagesListeners.forEach { it.onNumberOfPackagesChanged(value) }
            }
        }

    override var newChangesSinceDialogWasDismissed = false
        private set

    override val showFooterDot: MutableStateFlow<Boolean> = MutableStateFlow(showFooterDot)

    override var includesUserVisibleJobs = false
        private set

    private val numRunningPackagesListeners = LinkedHashSet<OnNumberOfPackagesChangedListener>()
    private val dialogDismissedListeners = LinkedHashSet<OnDialogDismissedListener>()

    /** Simulate that a fgs dialog was just dismissed. */
    fun simulateDialogDismiss() {
        newChangesSinceDialogWasDismissed = false
        dialogDismissedListeners.forEach { it.onDialogDismissed() }
    }

    override fun init() {}

    override fun showDialog(expandable: Expandable?) {}

    override fun addOnNumberOfPackagesChangedListener(listener: OnNumberOfPackagesChangedListener) {
        numRunningPackagesListeners.add(listener)
    }

    override fun removeOnNumberOfPackagesChangedListener(
        listener: OnNumberOfPackagesChangedListener
    ) {
        numRunningPackagesListeners.remove(listener)
    }

    override fun addOnDialogDismissedListener(listener: OnDialogDismissedListener) {
        dialogDismissedListeners.add(listener)
    }

    override fun removeOnDialogDismissedListener(listener: OnDialogDismissedListener) {
        dialogDismissedListeners.remove(listener)
    }

    override fun visibleButtonsCount(): Int = 0
}
