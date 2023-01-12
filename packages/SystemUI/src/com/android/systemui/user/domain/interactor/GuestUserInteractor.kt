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

package com.android.systemui.user.domain.interactor

import android.annotation.UserIdInt
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.UserInfo
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.WindowManagerGlobal
import android.widget.Toast
import com.android.internal.logging.UiEventLogger
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Encapsulates business logic to interact with guest user data and systems. */
@SysUISingleton
class GuestUserInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val manager: UserManager,
    private val repository: UserRepository,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val devicePolicyManager: DevicePolicyManager,
    private val refreshUsersScheduler: RefreshUsersScheduler,
    private val uiEventLogger: UiEventLogger,
    resumeSessionReceiver: GuestResumeSessionReceiver,
    resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver,
) {
    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean = repository.isGuestUserAutoCreated

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean = repository.isGuestUserResetting

    init {
        resumeSessionReceiver.register()
        resetOrExitSessionReceiver.register()
    }

    /** Notifies that the device has finished booting. */
    fun onDeviceBootCompleted() {
        applicationScope.launch {
            if (isDeviceAllowedToAddGuest()) {
                guaranteePresent()
                return@launch
            }

            suspendCancellableCoroutine<Unit> { continuation ->
                val callback =
                    object : DeviceProvisionedController.DeviceProvisionedListener {
                        override fun onDeviceProvisionedChanged() {
                            continuation.resumeWith(Result.success(Unit))
                            deviceProvisionedController.removeCallback(this)
                        }
                    }

                deviceProvisionedController.addCallback(callback)
            }

            if (isDeviceAllowedToAddGuest()) {
                guaranteePresent()
            }
        }
    }

    /** Creates a guest user and switches to it. */
    fun createAndSwitchTo(
        showDialog: (ShowDialogRequestModel) -> Unit,
        dismissDialog: () -> Unit,
        selectUser: (userId: Int) -> Unit,
    ) {
        applicationScope.launch {
            val newGuestUserId = create(showDialog, dismissDialog)
            if (newGuestUserId != UserHandle.USER_NULL) {
                selectUser(newGuestUserId)
            }
        }
    }

    /** Exits the guest user, switching back to the last non-guest user or to the default user. */
    fun exit(
        @UserIdInt guestUserId: Int,
        @UserIdInt targetUserId: Int,
        forceRemoveGuestOnExit: Boolean,
        showDialog: (ShowDialogRequestModel) -> Unit,
        dismissDialog: () -> Unit,
        switchUser: (userId: Int) -> Unit,
    ) {
        val currentUserInfo = repository.getSelectedUserInfo()
        if (currentUserInfo.id != guestUserId) {
            Log.w(
                TAG,
                "User requesting to start a new session ($guestUserId) is not current user" +
                    " (${currentUserInfo.id})"
            )
            return
        }

        if (!currentUserInfo.isGuest) {
            Log.w(TAG, "User requesting to start a new session ($guestUserId) is not a guest")
            return
        }

        applicationScope.launch {
            var newUserId = UserHandle.USER_SYSTEM
            if (targetUserId == UserHandle.USER_NULL) {
                // When a target user is not specified switch to last non guest user:
                val lastSelectedNonGuestUserHandle = repository.lastSelectedNonGuestUserId
                if (lastSelectedNonGuestUserHandle != UserHandle.USER_SYSTEM) {
                    val info =
                        withContext(backgroundDispatcher) {
                            manager.getUserInfo(lastSelectedNonGuestUserHandle)
                        }
                    if (info != null && info.isEnabled && info.supportsSwitchToByUser()) {
                        newUserId = info.id
                    }
                }
            } else {
                newUserId = targetUserId
            }

            if (currentUserInfo.isEphemeral || forceRemoveGuestOnExit) {
                uiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE)
                remove(currentUserInfo.id, newUserId, showDialog, dismissDialog, switchUser)
            } else {
                uiEventLogger.log(QSUserSwitcherEvent.QS_USER_SWITCH)
                switchUser(newUserId)
            }
        }
    }

    /**
     * Guarantees that the guest user is present on the device, creating it if needed and if allowed
     * to.
     */
    suspend fun guaranteePresent() {
        if (!isDeviceAllowedToAddGuest()) {
            return
        }

        val guestUser = withContext(backgroundDispatcher) { manager.findCurrentGuestUser() }
        if (guestUser == null) {
            scheduleCreation()
        }
    }

    /** Removes the guest user from the device. */
    suspend fun remove(
        @UserIdInt guestUserId: Int,
        @UserIdInt targetUserId: Int,
        showDialog: (ShowDialogRequestModel) -> Unit,
        dismissDialog: () -> Unit,
        switchUser: (userId: Int) -> Unit,
    ) {
        val currentUser: UserInfo = repository.getSelectedUserInfo()
        if (currentUser.id != guestUserId) {
            Log.w(
                TAG,
                "User requesting to start a new session ($guestUserId) is not current user" +
                    " ($currentUser.id)"
            )
            return
        }

        if (!currentUser.isGuest) {
            Log.w(TAG, "User requesting to start a new session ($guestUserId) is not a guest")
            return
        }

        val marked =
            withContext(backgroundDispatcher) { manager.markGuestForDeletion(currentUser.id) }
        if (!marked) {
            Log.w(TAG, "Couldn't mark the guest for deletion for user $guestUserId")
            return
        }

        if (targetUserId == UserHandle.USER_NULL) {
            // Create a new guest in the foreground, and then immediately switch to it
            val newGuestId = create(showDialog, dismissDialog)
            if (newGuestId == UserHandle.USER_NULL) {
                Log.e(TAG, "Could not create new guest, switching back to system user")
                switchUser(UserHandle.USER_SYSTEM)
                withContext(backgroundDispatcher) { manager.removeUser(currentUser.id) }
                try {
                    WindowManagerGlobal.getWindowManagerService().lockNow(/* options= */ null)
                } catch (e: RemoteException) {
                    Log.e(
                        TAG,
                        "Couldn't remove guest because ActivityManager or WindowManager is dead"
                    )
                }
                return
            }

            switchUser(newGuestId)

            withContext(backgroundDispatcher) { manager.removeUser(currentUser.id) }
        } else {
            if (repository.isGuestUserAutoCreated) {
                repository.isGuestUserResetting = true
            }
            switchUser(targetUserId)
            manager.removeUser(currentUser.id)
        }
    }

    /**
     * Creates the guest user and adds it to the device.
     *
     * @param showDialog A function to invoke to show a dialog.
     * @param dismissDialog A function to invoke to dismiss a dialog.
     * @return The user ID of the newly-created guest user.
     */
    private suspend fun create(
        showDialog: (ShowDialogRequestModel) -> Unit,
        dismissDialog: () -> Unit,
    ): Int {
        return withContext(mainDispatcher) {
            showDialog(ShowDialogRequestModel.ShowUserCreationDialog(isGuest = true))
            val guestUserId = createInBackground()
            dismissDialog()
            if (guestUserId != UserHandle.USER_NULL) {
                uiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_ADD)
            } else {
                Toast.makeText(
                        applicationContext,
                        com.android.settingslib.R.string.add_guest_failed,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }

            guestUserId
        }
    }

    /** Schedules the creation of the guest user. */
    private suspend fun scheduleCreation() {
        if (!repository.isGuestUserCreationScheduled.compareAndSet(false, true)) {
            return
        }

        withContext(backgroundDispatcher) {
            val newGuestUserId = createInBackground()
            repository.isGuestUserCreationScheduled.set(false)
            repository.isGuestUserResetting = false
            if (newGuestUserId == UserHandle.USER_NULL) {
                Log.w(TAG, "Could not create new guest while exiting existing guest")
                // Refresh users so that we still display "Guest" if
                // config_guestUserAutoCreated=true
                refreshUsersScheduler.refreshIfNotPaused()
            }
        }
    }

    /**
     * Creates a guest user and return its multi-user user ID.
     *
     * This method does not check if a guest already exists before it makes a call to [UserManager]
     * to create a new one.
     *
     * @return The multi-user user ID of the newly created guest user, or [UserHandle.USER_NULL] if
     * the guest couldn't be created.
     */
    @UserIdInt
    private suspend fun createInBackground(): Int {
        return withContext(backgroundDispatcher) {
            try {
                val guestUser = manager.createGuest(applicationContext)
                if (guestUser != null) {
                    guestUser.id
                } else {
                    Log.e(
                        TAG,
                        "Couldn't create guest, most likely because there already exists one!"
                    )
                    UserHandle.USER_NULL
                }
            } catch (e: UserManager.UserOperationException) {
                Log.e(TAG, "Couldn't create guest user!", e)
                UserHandle.USER_NULL
            }
        }
    }

    private fun isDeviceAllowedToAddGuest(): Boolean {
        return deviceProvisionedController.isDeviceProvisioned &&
            !devicePolicyManager.isDeviceManaged
    }

    companion object {
        private const val TAG = "GuestUserInteractor"
    }
}
