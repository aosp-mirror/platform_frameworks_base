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
 *
 */

package com.android.systemui.user.ui.dialog

import android.app.Dialog
import android.content.Context
import com.android.settingslib.users.UserCreatingDialog
import com.android.systemui.CoreStartable
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Coordinates dialogs for user switcher logic. */
@SysUISingleton
class UserSwitcherDialogCoordinator
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val falsingManager: FalsingManager,
    private val broadcastSender: BroadcastSender,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val interactor: UserInteractor,
    private val featureFlags: FeatureFlags,
) : CoreStartable(context) {

    private var currentDialog: Dialog? = null

    override fun start() {
        if (featureFlags.isEnabled(Flags.USER_INTERACTOR_AND_REPO_USE_CONTROLLER)) {
            return
        }

        startHandlingDialogShowRequests()
        startHandlingDialogDismissRequests()
    }

    private fun startHandlingDialogShowRequests() {
        applicationScope.launch {
            interactor.dialogShowRequests.filterNotNull().collect { request ->
                currentDialog?.let {
                    if (it.isShowing) {
                        it.cancel()
                    }
                }

                currentDialog =
                    when (request) {
                        is ShowDialogRequestModel.ShowAddUserDialog ->
                            AddUserDialog(
                                context = context,
                                userHandle = request.userHandle,
                                isKeyguardShowing = request.isKeyguardShowing,
                                showEphemeralMessage = request.showEphemeralMessage,
                                falsingManager = falsingManager,
                                broadcastSender = broadcastSender,
                                dialogLaunchAnimator = dialogLaunchAnimator,
                            )
                        is ShowDialogRequestModel.ShowUserCreationDialog ->
                            UserCreatingDialog(
                                context,
                                request.isGuest,
                            )
                        is ShowDialogRequestModel.ShowExitGuestDialog ->
                            ExitGuestDialog(
                                context = context,
                                guestUserId = request.guestUserId,
                                isGuestEphemeral = request.isGuestEphemeral,
                                targetUserId = request.targetUserId,
                                isKeyguardShowing = request.isKeyguardShowing,
                                falsingManager = falsingManager,
                                dialogLaunchAnimator = dialogLaunchAnimator,
                                onExitGuestUserListener = request.onExitGuestUser,
                            )
                    }

                currentDialog?.show()
                interactor.onDialogShown()
            }
        }
    }

    private fun startHandlingDialogDismissRequests() {
        applicationScope.launch {
            interactor.dialogDismissRequests.filterNotNull().collect {
                currentDialog?.let {
                    if (it.isShowing) {
                        it.cancel()
                    }
                }

                interactor.onDialogDismissed()
            }
        }
    }
}
