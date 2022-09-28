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

import android.annotation.SuppressLint
import android.annotation.UserIdInt
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.android.internal.util.UserIcons
import com.android.systemui.R
import com.android.systemui.SystemUISecondaryUserService
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.util.kotlin.pairwise
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** Encapsulates business logic to interact with user data and systems. */
@SysUISingleton
class UserInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val repository: UserRepository,
    private val controller: UserSwitcherController,
    private val activityStarter: ActivityStarter,
    private val keyguardInteractor: KeyguardInteractor,
    private val featureFlags: FeatureFlags,
    private val manager: UserManager,
    @Application private val applicationScope: CoroutineScope,
    telephonyInteractor: TelephonyInteractor,
    broadcastDispatcher: BroadcastDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val activityManager: ActivityManager,
    private val refreshUsersScheduler: RefreshUsersScheduler,
    private val guestUserInteractor: GuestUserInteractor,
) {
    /**
     * Defines interface for classes that can be notified when the state of users on the device is
     * changed.
     */
    fun interface UserCallback {
        /** Notifies that the state of users on the device has changed. */
        fun onUserStateChanged()
    }

    private val isNewImpl: Boolean
        get() = featureFlags.isEnabled(Flags.REFACTORED_USER_SWITCHER_CONTROLLER)

    private val supervisedUserPackageName: String?
        get() =
            applicationContext.getString(
                com.android.internal.R.string.config_supervisedUserCreationPackage
            )

    private val callbacks = Collections.newSetFromMap(WeakHashMap<UserCallback, Boolean>())

    /** List of current on-device users to select from. */
    val users: Flow<List<UserModel>>
        get() =
            if (isNewImpl) {
                combine(
                    repository.userInfos,
                    repository.selectedUserInfo,
                    repository.userSwitcherSettings,
                ) { userInfos, selectedUserInfo, settings ->
                    toUserModels(
                        userInfos = userInfos,
                        selectedUserId = selectedUserInfo.id,
                        isUserSwitcherEnabled = settings.isUserSwitcherEnabled,
                    )
                }
            } else {
                repository.users
            }

    /** The currently-selected user. */
    val selectedUser: Flow<UserModel>
        get() =
            if (isNewImpl) {
                combine(
                    repository.selectedUserInfo,
                    repository.userSwitcherSettings,
                ) { selectedUserInfo, settings ->
                    val selectedUserId = selectedUserInfo.id
                    checkNotNull(
                        toUserModel(
                            userInfo = selectedUserInfo,
                            selectedUserId = selectedUserId,
                            canSwitchUsers = canSwitchUsers(selectedUserId),
                            isUserSwitcherEnabled = settings.isUserSwitcherEnabled,
                        )
                    )
                }
            } else {
                repository.selectedUser
            }

    /** List of user-switcher related actions that are available. */
    val actions: Flow<List<UserActionModel>>
        get() =
            if (isNewImpl) {
                combine(
                    repository.userInfos,
                    repository.userSwitcherSettings,
                    keyguardInteractor.isKeyguardShowing,
                ) { userInfos, settings, isDeviceLocked ->
                    buildList {
                        val hasGuestUser = userInfos.any { it.isGuest }
                        if (
                            !hasGuestUser &&
                                (guestUserInteractor.isGuestUserAutoCreated ||
                                    UserActionsUtil.canCreateGuest(
                                        manager,
                                        repository,
                                        settings.isUserSwitcherEnabled,
                                        settings.isAddUsersFromLockscreen,
                                    ))
                        ) {
                            add(UserActionModel.ENTER_GUEST_MODE)
                        }

                        if (isDeviceLocked && !settings.isAddUsersFromLockscreen) {
                            // The device is locked and our setting to allow actions that add users
                            // from the lock-screen is not enabled. The guest action from above is
                            // always allowed, even when the device is locked, but the various "add
                            // user" actions below are not. We can finish building the list here.
                            return@buildList
                        }

                        if (
                            UserActionsUtil.canCreateUser(
                                manager,
                                repository,
                                settings.isUserSwitcherEnabled,
                                settings.isAddUsersFromLockscreen,
                            )
                        ) {
                            add(UserActionModel.ADD_USER)
                        }

                        if (
                            UserActionsUtil.canCreateSupervisedUser(
                                manager,
                                repository,
                                settings.isUserSwitcherEnabled,
                                settings.isAddUsersFromLockscreen,
                                supervisedUserPackageName,
                            )
                        ) {
                            add(UserActionModel.ADD_SUPERVISED_USER)
                        }
                    }
                }
            } else {
                combine(
                        repository.isActionableWhenLocked,
                        keyguardInteractor.isKeyguardShowing,
                    ) { isActionableWhenLocked, isLocked ->
                        isActionableWhenLocked || !isLocked
                    }
                    .flatMapLatest { isActionable ->
                        if (isActionable) {
                            repository.actions.map { actions ->
                                actions +
                                    if (actions.isNotEmpty()) {
                                        // If we have actions, we add NAVIGATE_TO_USER_MANAGEMENT
                                        // because that's a user switcher specific action that is
                                        // not known to the our data source or other features.
                                        listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)
                                    } else {
                                        // If no actions, don't add the navigate action.
                                        emptyList()
                                    }
                            }
                        } else {
                            // If not actionable it means that we're not allowed to show actions
                            // when
                            // locked and we are locked. Therefore, we should show no actions.
                            flowOf(emptyList())
                        }
                    }
            }

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean = guestUserInteractor.isGuestUserAutoCreated

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean = guestUserInteractor.isGuestUserResetting

    private val _dialogShowRequests = MutableStateFlow<ShowDialogRequestModel?>(null)
    val dialogShowRequests: Flow<ShowDialogRequestModel?> = _dialogShowRequests.asStateFlow()

    private val _dialogDismissRequests = MutableStateFlow<Unit?>(null)
    val dialogDismissRequests: Flow<Unit?> = _dialogDismissRequests.asStateFlow()

    val isSimpleUserSwitcher: Boolean
        get() =
            if (isNewImpl) {
                repository.isSimpleUserSwitcher()
            } else {
                error("Not supported in the old implementation!")
            }

    fun addCallback(callback: UserCallback) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: UserCallback) {
        callbacks.remove(callback)
    }

    fun onDialogShown() {
        _dialogShowRequests.value = null
    }

    fun onDialogDismissed() {
        _dialogDismissRequests.value = null
    }

    private fun showDialog(request: ShowDialogRequestModel) {
        _dialogShowRequests.value = request
    }

    private fun dismissDialog() {
        _dialogDismissRequests.value = Unit
    }

    init {
        if (isNewImpl) {
            refreshUsersScheduler.refreshIfNotPaused()
            telephonyInteractor.callState
                .distinctUntilChanged()
                .onEach { refreshUsersScheduler.refreshIfNotPaused() }
                .launchIn(applicationScope)

            combine(
                    broadcastDispatcher.broadcastFlow(
                        filter =
                            IntentFilter().apply {
                                addAction(Intent.ACTION_USER_ADDED)
                                addAction(Intent.ACTION_USER_REMOVED)
                                addAction(Intent.ACTION_USER_INFO_CHANGED)
                                addAction(Intent.ACTION_USER_SWITCHED)
                                addAction(Intent.ACTION_USER_STOPPED)
                                addAction(Intent.ACTION_USER_UNLOCKED)
                            },
                        user = UserHandle.SYSTEM,
                        map = { intent, _ -> intent },
                    ),
                    repository.selectedUserInfo.pairwise(null),
                ) { intent, selectedUserChange ->
                    Pair(intent, selectedUserChange.previousValue)
                }
                .onEach { (intent, previousSelectedUser) ->
                    onBroadcastReceived(intent, previousSelectedUser)
                }
                .launchIn(applicationScope)
        }
    }

    fun onDeviceBootCompleted() {
        guestUserInteractor.onDeviceBootCompleted()
    }

    /** Switches to the user with the given user ID. */
    fun selectUser(
        newlySelectedUserId: Int,
    ) {
        if (isNewImpl) {
            val currentlySelectedUserInfo = repository.getSelectedUserInfo()
            if (
                newlySelectedUserId == currentlySelectedUserInfo.id &&
                    currentlySelectedUserInfo.isGuest
            ) {
                // Here when clicking on the currently-selected guest user to leave guest mode
                // and return to the previously-selected non-guest user.
                showDialog(
                    ShowDialogRequestModel.ShowExitGuestDialog(
                        guestUserId = currentlySelectedUserInfo.id,
                        targetUserId = repository.lastSelectedNonGuestUserId,
                        isGuestEphemeral = currentlySelectedUserInfo.isEphemeral,
                        isKeyguardShowing = keyguardInteractor.isKeyguardShowing(),
                        onExitGuestUser = this::exitGuestUser,
                    )
                )
                return
            }

            if (currentlySelectedUserInfo.isGuest) {
                // Here when switching from guest to a non-guest user.
                showDialog(
                    ShowDialogRequestModel.ShowExitGuestDialog(
                        guestUserId = currentlySelectedUserInfo.id,
                        targetUserId = newlySelectedUserId,
                        isGuestEphemeral = currentlySelectedUserInfo.isEphemeral,
                        isKeyguardShowing = keyguardInteractor.isKeyguardShowing(),
                        onExitGuestUser = this::exitGuestUser,
                    )
                )
                return
            }

            switchUser(newlySelectedUserId)
        } else {
            controller.onUserSelected(newlySelectedUserId, /* dialogShower= */ null)
        }
    }

    /** Executes the given action. */
    fun executeAction(action: UserActionModel) {
        if (isNewImpl) {
            when (action) {
                UserActionModel.ENTER_GUEST_MODE ->
                    guestUserInteractor.createAndSwitchTo(
                        this::showDialog,
                        this::dismissDialog,
                        this::selectUser,
                    )
                UserActionModel.ADD_USER -> {
                    val currentUser = repository.getSelectedUserInfo()
                    showDialog(
                        ShowDialogRequestModel.ShowAddUserDialog(
                            userHandle = currentUser.userHandle,
                            isKeyguardShowing = keyguardInteractor.isKeyguardShowing(),
                            showEphemeralMessage = currentUser.isGuest && currentUser.isEphemeral,
                        )
                    )
                }
                UserActionModel.ADD_SUPERVISED_USER ->
                    activityStarter.startActivity(
                        Intent()
                            .setAction(UserManager.ACTION_CREATE_SUPERVISED_USER)
                            .setPackage(supervisedUserPackageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        /* dismissShade= */ false,
                    )
                UserActionModel.NAVIGATE_TO_USER_MANAGEMENT ->
                    activityStarter.startActivity(
                        Intent(Settings.ACTION_USER_SETTINGS),
                        /* dismissShade= */ false,
                    )
            }
        } else {
            when (action) {
                UserActionModel.ENTER_GUEST_MODE -> controller.createAndSwitchToGuestUser(null)
                UserActionModel.ADD_USER -> controller.showAddUserDialog(null)
                UserActionModel.ADD_SUPERVISED_USER -> controller.startSupervisedUserActivity()
                UserActionModel.NAVIGATE_TO_USER_MANAGEMENT ->
                    activityStarter.startActivity(
                        Intent(Settings.ACTION_USER_SETTINGS),
                        /* dismissShade= */ false,
                    )
            }
        }
    }

    private fun exitGuestUser(
        @UserIdInt guestUserId: Int,
        @UserIdInt targetUserId: Int,
        forceRemoveGuestOnExit: Boolean,
    ) {
        guestUserInteractor.exit(
            guestUserId = guestUserId,
            targetUserId = targetUserId,
            forceRemoveGuestOnExit = forceRemoveGuestOnExit,
            showDialog = this::showDialog,
            dismissDialog = this::dismissDialog,
            switchUser = this::switchUser,
        )
    }

    private fun switchUser(userId: Int) {
        // TODO(b/246631653): track jank and lantecy like in the old impl.
        refreshUsersScheduler.pause()
        try {
            activityManager.switchUser(userId)
        } catch (e: RemoteException) {
            Log.e(TAG, "Couldn't switch user.", e)
        }
    }

    private suspend fun onBroadcastReceived(
        intent: Intent,
        previousUserInfo: UserInfo?,
    ) {
        val shouldRefreshAllUsers =
            when (intent.action) {
                Intent.ACTION_USER_SWITCHED -> {
                    dismissDialog()
                    val selectedUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)
                    if (previousUserInfo?.id != selectedUserId) {
                        callbacks.forEach { it.onUserStateChanged() }
                        restartSecondaryService(selectedUserId)
                    }
                    if (guestUserInteractor.isGuestUserAutoCreated) {
                        guestUserInteractor.guaranteePresent()
                    }
                    true
                }
                Intent.ACTION_USER_INFO_CHANGED -> true
                Intent.ACTION_USER_UNLOCKED -> {
                    // If we unlocked the system user, we should refresh all users.
                    intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL,
                    ) == UserHandle.USER_SYSTEM
                }
                else -> true
            }

        if (shouldRefreshAllUsers) {
            refreshUsersScheduler.unpauseAndRefresh()
        }
    }

    private fun restartSecondaryService(@UserIdInt userId: Int) {
        val intent = Intent(applicationContext, SystemUISecondaryUserService::class.java)
        // Disconnect from the old secondary user's service
        val secondaryUserId = repository.secondaryUserId
        if (secondaryUserId != UserHandle.USER_NULL) {
            applicationContext.stopServiceAsUser(
                intent,
                UserHandle.of(secondaryUserId),
            )
            repository.secondaryUserId = UserHandle.USER_NULL
        }

        // Connect to the new secondary user's service (purely to ensure that a persistent
        // SystemUI application is created for that user)
        if (userId != UserHandle.USER_SYSTEM) {
            applicationContext.startServiceAsUser(
                intent,
                UserHandle.of(userId),
            )
            repository.secondaryUserId = userId
        }
    }

    private suspend fun toUserModels(
        userInfos: List<UserInfo>,
        selectedUserId: Int,
        isUserSwitcherEnabled: Boolean,
    ): List<UserModel> {
        val canSwitchUsers = canSwitchUsers(selectedUserId)

        return userInfos
            // The guest user should go in the last position.
            .sortedBy { it.isGuest }
            .mapNotNull { userInfo ->
                toUserModel(
                    userInfo = userInfo,
                    selectedUserId = selectedUserId,
                    canSwitchUsers = canSwitchUsers,
                    isUserSwitcherEnabled = isUserSwitcherEnabled,
                )
            }
    }

    private suspend fun toUserModel(
        userInfo: UserInfo,
        selectedUserId: Int,
        canSwitchUsers: Boolean,
        isUserSwitcherEnabled: Boolean,
    ): UserModel? {
        val userId = userInfo.id
        val isSelected = userId == selectedUserId

        return when {
            // When the user switcher is not enabled in settings, we only show the primary user.
            !isUserSwitcherEnabled && !userInfo.isPrimary -> null

            // We avoid showing disabled users.
            !userInfo.isEnabled -> null
            userInfo.isGuest ->
                UserModel(
                    id = userId,
                    name = Text.Loaded(userInfo.name),
                    image =
                        getUserImage(
                            isGuest = true,
                            userId = userId,
                        ),
                    isSelected = isSelected,
                    isSelectable = canSwitchUsers,
                    isGuest = true,
                )
            userInfo.supportsSwitchToByUser() ->
                UserModel(
                    id = userId,
                    name = Text.Loaded(userInfo.name),
                    image =
                        getUserImage(
                            isGuest = false,
                            userId = userId,
                        ),
                    isSelected = isSelected,
                    isSelectable = canSwitchUsers || isSelected,
                    isGuest = false,
                )
            else -> null
        }
    }

    private suspend fun canSwitchUsers(selectedUserId: Int): Boolean {
        return withContext(backgroundDispatcher) {
            manager.getUserSwitchability(UserHandle.of(selectedUserId))
        } == UserManager.SWITCHABILITY_STATUS_OK
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private suspend fun getUserImage(
        isGuest: Boolean,
        userId: Int,
    ): Drawable {
        if (isGuest) {
            return checkNotNull(applicationContext.getDrawable(R.drawable.ic_account_circle))
        }

        // TODO(b/246631653): cache the bitmaps to avoid the background work to fetch them.
        // TODO(b/246631653): downscale the bitmaps to R.dimen.max_avatar_size if requested.
        val userIcon = withContext(backgroundDispatcher) { manager.getUserIcon(userId) }
        if (userIcon != null) {
            return BitmapDrawable(userIcon)
        }

        return UserIcons.getDefaultUserIcon(
            applicationContext.resources,
            userId,
            /* light= */ false
        )
    }

    companion object {
        private const val TAG = "UserInteractor"
    }
}
