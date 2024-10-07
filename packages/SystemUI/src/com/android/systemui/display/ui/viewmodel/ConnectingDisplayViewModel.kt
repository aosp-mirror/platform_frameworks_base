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
import com.android.server.policy.feature.flags.Flags
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.ui.view.MirroringConfirmationDialogDelegate
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

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
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val bottomSheetFactory: MirroringConfirmationDialogDelegate.Factory,
) : CoreStartable {

    private var dialog: Dialog? = null

    /** Starts listening for pending displays. */
    @OptIn(FlowPreview::class)
    override fun start() {
        val pendingDisplayFlow = connectedDisplayInteractor.pendingDisplay
        val concurrentDisplaysInProgessFlow =
            if (Flags.enableDualDisplayBlocking()) {
                connectedDisplayInteractor.concurrentDisplaysInProgress
            } else {
                flow { emit(false) }
            }
        pendingDisplayFlow
            // Let's debounce for 2 reasons:
            // - prevent fast dialog flashes in case pending displays are available for just a few
            // millis
            // - Prevent jumps related to inset changes: when in 3 buttons navigation, device
            // unlock triggers a change in insets that might result in a jump of the dialog (if a
            // display was connected while on the lockscreen).
            .debounce(200.milliseconds)
            .combine(concurrentDisplaysInProgessFlow) { pendingDisplay, concurrentDisplaysInProgress
                ->
                if (pendingDisplay == null) {
                    dismissDialog()
                } else {
                    showDialog(pendingDisplay, concurrentDisplaysInProgress)
                }
            }
            .launchIn(scope)
    }

    private fun showDialog(pendingDisplay: PendingDisplay, concurrentDisplaysInProgess: Boolean) {
        dismissDialog()
        dialog =
            bottomSheetFactory
                .createDialog(
                    onStartMirroringClickListener = {
                        scope.launch(bgDispatcher) { pendingDisplay.enable() }
                        dismissDialog()
                    },
                    onCancelMirroring = {
                        scope.launch(bgDispatcher) { pendingDisplay.ignore() }
                        dismissDialog()
                    },
                    navbarBottomInsetsProvider = { Utils.getNavbarInsets(context).bottom },
                    showConcurrentDisplayInfo = concurrentDisplaysInProgess
                )
                .apply { show() }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    @Module
    interface StartableModule {
        @Binds
        @IntoMap
        @ClassKey(ConnectingDisplayViewModel::class)
        fun bindsConnectingDisplayViewModel(impl: ConnectingDisplayViewModel): CoreStartable
    }
}
