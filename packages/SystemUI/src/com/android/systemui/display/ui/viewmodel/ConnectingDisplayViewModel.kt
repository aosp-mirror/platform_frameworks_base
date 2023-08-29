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
package com.android.systemui.display.ui.viewmodel

import android.app.Dialog
import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.ui.view.MirroringConfirmationDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Shows/hides a dialog to allow the user to decide whether to use the external display for
 * mirroring.
 */
@SysUISingleton
class ConnectingDisplayViewModel
@Inject
constructor(
    private val context: Context,
    private val connectedDisplayInteractor: ConnectedDisplayInteractor,
    @Application private val scope: CoroutineScope,
) {

    private var dialog: Dialog? = null

    /** Starts listening for pending displays. */
    fun init() {
        connectedDisplayInteractor.pendingDisplay
            .onEach { pendingDisplay ->
                if (pendingDisplay == null) {
                    hideDialog()
                } else {
                    showDialog(pendingDisplay)
                }
            }
            .launchIn(scope)
    }

    private fun showDialog(pendingDisplay: PendingDisplay) {
        hideDialog()
        dialog =
            MirroringConfirmationDialog(
                    context,
                    onStartMirroringClickListener = {
                        pendingDisplay.enable()
                        hideDialog()
                    },
                    onDismissClickListener = { hideDialog() }
                )
                .apply { show() }
    }

    private fun hideDialog() {
        dialog?.hide()
        dialog = null
    }
}
