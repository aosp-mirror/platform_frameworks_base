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
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.users.UserCreatingDialog
import com.android.systemui.CoreStartable
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.tiles.UserDetailView
import com.android.systemui.user.UserSwitchFullscreenDialog
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Coordinates dialogs for user switcher logic. */
@SysUISingleton
class UserSwitcherDialogCoordinator
@Inject
constructor(
    @Application private val context: Lazy<Context>,
    @Application private val applicationScope: Lazy<CoroutineScope>,
    private val falsingManager: Lazy<FalsingManager>,
    private val broadcastSender: Lazy<BroadcastSender>,
    private val dialogLaunchAnimator: Lazy<DialogLaunchAnimator>,
    private val interactor: Lazy<UserSwitcherInteractor>,
    private val userDetailAdapterProvider: Provider<UserDetailView.Adapter>,
    private val eventLogger: Lazy<UiEventLogger>,
    private val activityStarter: Lazy<ActivityStarter>,
    private val falsingCollector: Lazy<FalsingCollector>,
    private val userSwitcherViewModel: Lazy<UserSwitcherViewModel>,
) : CoreStartable {

    private var currentDialog: Dialog? = null

    override fun start() {
        startHandlingDialogShowRequests()
        startHandlingDialogDismissRequests()
    }

    private fun startHandlingDialogShowRequests() {
        applicationScope.get().launch {
            interactor.get().dialogShowRequests.filterNotNull().collect { request ->
                val (dialog, dialogCuj) =
                    when (request) {
                        is ShowDialogRequestModel.ShowAddUserDialog ->
                            Pair(
                                AddUserDialog(
                                    context = context.get(),
                                    userHandle = request.userHandle,
                                    isKeyguardShowing = request.isKeyguardShowing,
                                    showEphemeralMessage = request.showEphemeralMessage,
                                    falsingManager = falsingManager.get(),
                                    broadcastSender = broadcastSender.get(),
                                    dialogLaunchAnimator = dialogLaunchAnimator.get(),
                                ),
                                DialogCuj(
                                    InteractionJankMonitor.CUJ_USER_DIALOG_OPEN,
                                    INTERACTION_JANK_ADD_NEW_USER_TAG,
                                ),
                            )
                        is ShowDialogRequestModel.ShowUserCreationDialog ->
                            Pair(
                                UserCreatingDialog(
                                    context.get(),
                                    request.isGuest,
                                ),
                                null,
                            )
                        is ShowDialogRequestModel.ShowExitGuestDialog ->
                            Pair(
                                ExitGuestDialog(
                                    context = context.get(),
                                    guestUserId = request.guestUserId,
                                    isGuestEphemeral = request.isGuestEphemeral,
                                    targetUserId = request.targetUserId,
                                    isKeyguardShowing = request.isKeyguardShowing,
                                    falsingManager = falsingManager.get(),
                                    dialogLaunchAnimator = dialogLaunchAnimator.get(),
                                    onExitGuestUserListener = request.onExitGuestUser,
                                ),
                                DialogCuj(
                                    InteractionJankMonitor.CUJ_USER_DIALOG_OPEN,
                                    INTERACTION_JANK_EXIT_GUEST_MODE_TAG,
                                ),
                            )
                        is ShowDialogRequestModel.ShowUserSwitcherDialog ->
                            Pair(
                                UserSwitchDialog(
                                    context = context.get(),
                                    adapter = userDetailAdapterProvider.get(),
                                    uiEventLogger = eventLogger.get(),
                                    falsingManager = falsingManager.get(),
                                    activityStarter = activityStarter.get(),
                                    dialogLaunchAnimator = dialogLaunchAnimator.get(),
                                ),
                                DialogCuj(
                                    InteractionJankMonitor.CUJ_USER_DIALOG_OPEN,
                                    INTERACTION_JANK_EXIT_GUEST_MODE_TAG,
                                ),
                            )
                        is ShowDialogRequestModel.ShowUserSwitcherFullscreenDialog ->
                            Pair(
                                UserSwitchFullscreenDialog(
                                    context = context.get(),
                                    falsingCollector = falsingCollector.get(),
                                    userSwitcherViewModel = userSwitcherViewModel.get(),
                                ),
                                null, /* dialogCuj */
                            )
                    }
                currentDialog = dialog

                val controller = request.expandable?.dialogLaunchController(dialogCuj)
                if (controller != null) {
                    dialogLaunchAnimator.get().show(dialog, controller)
                } else if (request.dialogShower != null && dialogCuj != null) {
                    request.dialogShower?.showDialog(dialog, dialogCuj)
                } else {
                    dialog.show()
                }

                interactor.get().onDialogShown()
            }
        }
    }

    private fun startHandlingDialogDismissRequests() {
        applicationScope.get().launch {
            interactor.get().dialogDismissRequests.filterNotNull().collect {
                currentDialog?.let {
                    if (it.isShowing) {
                        it.cancel()
                    }
                }

                interactor.get().onDialogDismissed()
            }
        }
    }

    companion object {
        private const val INTERACTION_JANK_ADD_NEW_USER_TAG = "add_new_user"
        private const val INTERACTION_JANK_EXIT_GUEST_MODE_TAG = "exit_guest_mode"
    }
}
