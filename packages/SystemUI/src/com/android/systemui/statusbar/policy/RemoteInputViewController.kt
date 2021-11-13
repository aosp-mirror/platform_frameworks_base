/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.view.View
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewScope
import javax.inject.Inject

interface RemoteInputViewController {
    fun bind()
    fun unbind()
}

@RemoteInputViewScope
class RemoteInputViewControllerImpl @Inject constructor(
    private val view: RemoteInputView,
    private val remoteInputQuickSettingsDisabler: RemoteInputQuickSettingsDisabler
) : RemoteInputViewController {

    private var isBound = false

    override fun bind() {
        if (isBound) return
        isBound = true

        view.addOnEditTextFocusChangedListener(onFocusChangeListener)
    }

    override fun unbind() {
        if (!isBound) return
        isBound = false

        view.removeOnEditTextFocusChangedListener(onFocusChangeListener)
    }

    private val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
        remoteInputQuickSettingsDisabler.setRemoteInputActive(hasFocus)
    }
}