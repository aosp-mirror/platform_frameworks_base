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
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.UserSwitcherActivity
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.user.legacyhelper.data.LegacyUserDataHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.util.kotlin.pairwise
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encapsulates business logic to interact with user data and systems. */
@SysUISingleton
class UserInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val repository: UserRepository,
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
    interface UserCallback {
        /** Returns `true` if this callback can be cleaned-up. */
        fun isEvictable(): Boolean = false

        /** Notifies that the state of users on the device has changed. */
        fun onUserStateChanged()
    }

    private val supervisedUserPackageName: String?
        get() =
            applicationContext.getString(
                com.android.internal.R.string.config_supervisedUserCreationPackage
            )

    private val callbackMutex = Mutex()
    private val callbacks = mutableSetOf<UserCallback>()
    private val userInfos: Flow<List<UserInfo>> =
        repository.userInfos.map { userInfos -> userInfos.filter { it.isFull } }

    /** List of current on-device users to select from. */
    val users: Flow<List<UserModel>>
        get() =
            combine(
                userInfos,
                repository.selectedUserInfo,
                repository.userSwitcherSettings,
            ) { userInfos, selectedUserInfo, settings ->
                toUserModels(
                    userInfos = userInfos,
                    selectedUserId = selectedUserInfo.id,
                    isUserSwitcherEnabled = settings.isUserSwitcherEnabled,
                )
            }

    /** The currently-selected user. */
    val selectedUser: Flow<UserModel>
        get() =
            repository.selectedUserInfo.map { selectedUserInfo ->
                val selectedUserId = selectedUserInfo.id
                toUserModel(
                    userInfo = selectedUserInfo,
                    selectedUserId = selectedUserId,
                    canSwitchUsers = canSwitchUsers(selectedUserId)
                )
            }

    /** List of user-switcher related actions that are available. */
    val actions: Flow<List<UserActionModel>>
        get() =
            combine(
                repository.selectedUserInfo,
                userInfos,
                repository.userSwitcherSettings,
                keyguardInteractor.isKeyguardShowing,
            ) { _, userInfos, settings, isDeviceLocked ->
                buildList {
                    if (!isDeviceLocked || settings.isAddUsersFromLockscreen) {
                        // The device is locked and our setting to allow actions that add users
                        // from the lock-screen is not enabled. We can finish building the list
                        // here.
                        val isFullScreen = featureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)

                        val actionList: List<UserActionModel> =
                            if (isFullScreen) {
                                listOf(
                                    UserActionModel.ADD_USER,
                                    UserActionModel.ADD_SUPERVISED_USER,
                                    UserActionModel.ENTER_GUEST_MODE,
                                )
                            } else {
                                listOf(
                                    UserActionModel.ENTER_GUEST_MODE,
                                    UserActionModel.ADD_USER,
                                    UserActionModel.ADD_SUPERVISED_USER,
                                )
                            }
                        actionList.map {
                            when (it) {
                                UserActionModel.ENTER_GUEST_MODE -> {
                                    val hasGuestUser = userInfos.any { it.isGuest }
                                    if (!hasGuestUser && canCreateGuestUser(settings)) {
                                        add(UserActionModel.ENTER_GUEST_MODE)
                                    }
                                }
                                UserActionModel.ADD_USER -> {
                                    val canCreateUsers =
                                        UserActionsUtil.canCreateUser(
                                            manager,
                                            repository,
                                            settings.isUserSwitcherEnabled,
                                            settings.isAddUsersFromLockscreen,
                                        )

                                    if (canCreateUsers) {
                                        add(UserActionModel.ADD_USER)
                                    }
                                }
                                UserActionModel.ADD_SUPERVISED_USER -> {
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
                                else -> Unit
                            }
                        }
                    }
                    if (
                        UserActionsUtil.canManageUsers(
                            repository,
                            settings.isUserSwitcherEnabled,
                            settings.isAddUsersFromLockscreen,
                        )
                    ) {
                        add(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)
                    }
                }
            }

    val userRecords: StateFlow<ArrayList<UserRecord>> =
        combine(
                userInfos,
                repository.selectedUserInfo,
                actions,
                repository.userSwitcherSettings,
            ) { userInfos, selectedUserInfo, actionModels, settings ->
                ArrayList(
                    userInfos.map {
                        toRecord(
                            userInfo = it,
                            selectedUserId = selectedUserInfo.id,
                        )
                    } +
                        actionModels.map {
                            toRecord(
                                action = it,
                                selectedUserId = selectedUserInfo.id,
                                isRestricted =
                                    it != UserActionModel.ENTER_GUEST_MODE &&
                                        it != UserActionModel.NAVIGATE_TO_USER_MANAGEMENT &&
                                        !settings.isAddUsersFromLockscreen,
                            )
                        }
                )
            }
            .onEach { notifyCallbacks() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ArrayList(),
            )

    val selectedUserRecord: StateFlow<UserRecord?> =
        repository.selectedUserInfo
            .map { selectedUserInfo ->
                toRecord(userInfo = selectedUserInfo, selectedUserId = selectedUserInfo.id)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean = guestUserInteractor.isGuestUserAutoCreated

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean = guestUserInteractor.isGuestUserResetting

    /** Whether to enable the user chip in the status bar */
    val isStatusBarUserChipEnabled: Boolean = repository.isStatusBarUserChipEnabled

    private val _dialogShowRequests = MutableStateFlow<ShowDialogRequestModel?>(null)
    val dialogShowRequests: Flow<ShowDialogRequestModel?> = _dialogShowRequests.asStateFlow()

    private val _dialogDismissRequests = MutableStateFlow<Unit?>(null)
    val dialogDismissRequests: Flow<Unit?> = _dialogDismissRequests.asStateFlow()

    val isSimpleUserSwitcher: Boolean
        get() = repository.isSimpleUserSwitcher()

    init {
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

    fun addCallback(callback: UserCallback) {
        applicationScope.launch { callbackMutex.withLock { callbacks.add(callback) } }
    }

    fun removeCallback(callback: UserCallback) {
        applicationScope.launch { callbackMutex.withLock { callbacks.remove(callback) } }
    }

    fun refreshUsers() {
        refreshUsersScheduler.refreshIfNotPaused()
    }

    fun onDialogShown() {
        _dialogShowRequests.value = null
    }

    fun onDialogDismissed() {
        _dialogDismissRequests.value = null
    }

    fun dump(pw: PrintWriter) {
        pw.println("UserInteractor state:")
        pw.println("  lastSelectedNonGuestUserId=${repository.lastSelectedNonGuestUserId}")

        val users = userRecords.value.filter { it.info != null }
        pw.println("  userCount=${userRecords.value.count { LegacyUserDataHelper.isUser(it) }}")
        for (i in users.indices) {
            pw.println("    ${users[i]}")
        }

        val actions = userRecords.value.filter { it.info == null }
        pw.println("  actionCount=${userRecords.value.count { !LegacyUserDataHelper.isUser(it) }}")
        for (i in actions.indices) {
            pw.println("    ${actions[i]}")
        }

        pw.println("isSimpleUserSwitcher=$isSimpleUserSwitcher")
        pw.println("isGuestUserAutoCreated=$isGuestUserAutoCreated")
    }

    fun onDeviceBootCompleted() {
        guestUserInteractor.onDeviceBootCompleted()
    }

    /** Switches to the user or executes the action represented by the given record. */
    fun onRecordSelected(
        record: UserRecord,
        dialogShower: UserSwitchDialogController.DialogShower? = null,
    ) {
        if (LegacyUserDataHelper.isUser(record)) {
            // It's safe to use checkNotNull around record.info because isUser only returns true
            // if record.info is not null.
            selectUser(checkNotNull(record.info).id, dialogShower)
        } else {
            executeAction(LegacyUserDataHelper.toUserActionModel(record), dialogShower)
        }
    }

    /** Switches to the user with the given user ID. */
    fun selectUser(
        newlySelectedUserId: Int,
        dialogShower: UserSwitchDialogController.DialogShower? = null,
    ) {
        val currentlySelectedUserInfo = repository.getSelectedUserInfo()
        if (
            newlySelectedUserId == currentlySelectedUserInfo.id && currentlySelectedUserInfo.isGuest
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
                    dialogShower = dialogShower,
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
                    dialogShower = dialogShower,
                )
            )
            return
        }

        dialogShower?.dismiss()

        switchUser(newlySelectedUserId)
    }

    /** Executes the given action. */
    fun executeAction(
        action: UserActionModel,
        dialogShower: UserSwitchDialogController.DialogShower? = null,
    ) {
        when (action) {
            UserActionModel.ENTER_GUEST_MODE ->
                guestUserInteractor.createAndSwitchTo(
                    this::showDialog,
                    this::dismissDialog,
                ) { userId ->
                    selectUser(userId, dialogShower)
                }
            UserActionModel.ADD_USER -> {
                val currentUser = repository.getSelectedUserInfo()
                showDialog(
                    ShowDialogRequestModel.ShowAddUserDialog(
                        userHandle = currentUser.userHandle,
                        isKeyguardShowing = keyguardInteractor.isKeyguardShowing(),
                        showEphemeralMessage = currentUser.isGuest && currentUser.isEphemeral,
                        dialogShower = dialogShower,
                    )
                )
            }
            UserActionModel.ADD_SUPERVISED_USER ->
                activityStarter.startActivity(
                    Intent()
                        .setAction(UserManager.ACTION_CREATE_SUPERVISED_USER)
                        .setPackage(supervisedUserPackageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    /* dismissShade= */ true,
                )
            UserActionModel.NAVIGATE_TO_USER_MANAGEMENT ->
                activityStarter.startActivity(
                    Intent(Settings.ACTION_USER_SETTINGS),
                    /* dismissShade= */ true,
                )
        }
    }

    fun exitGuestUser(
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

    fun removeGuestUser(
        @UserIdInt guestUserId: Int,
        @UserIdInt targetUserId: Int,
    ) {
        applicationScope.launch {
            guestUserInteractor.remove(
                guestUserId = guestUserId,
                targetUserId = targetUserId,
                ::showDialog,
                ::dismissDialog,
                ::selectUser,
            )
        }
    }

    fun showUserSwitcher(context: Context, expandable: Expandable) {
        if (!featureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)) {
            showDialog(ShowDialogRequestModel.ShowUserSwitcherDialog(expandable))
            return
        }

        val intent =
            Intent(context, UserSwitcherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        activityStarter.startActivity(
            intent,
            true /* dismissShade */,
            expandable.activityLaunchController(),
            true /* showOverlockscreenwhenlocked */,
            UserHandle.SYSTEM,
        )
    }

    private fun showDialog(request: ShowDialogRequestModel) {
        _dialogShowRequests.value = request
    }

    private fun dismissDialog() {
        _dialogDismissRequests.value = Unit
    }

    private fun notifyCallbacks() {
        applicationScope.launch {
            callbackMutex.withLock {
                val iterator = callbacks.iterator()
                while (iterator.hasNext()) {
                    val callback = iterator.next()
                    if (!callback.isEvictable()) {
                        callback.onUserStateChanged()
                    } else {
                        iterator.remove()
                    }
                }
            }
        }
    }

    private suspend fun toRecord(
        userInfo: UserInfo,
        selectedUserId: Int,
    ): UserRecord {
        return LegacyUserDataHelper.createRecord(
            context = applicationContext,
            manager = manager,
            userInfo = userInfo,
            picture = null,
            isCurrent = userInfo.id == selectedUserId,
            canSwitchUsers = canSwitchUsers(selectedUserId),
        )
    }

    private suspend fun toRecord(
        action: UserActionModel,
        selectedUserId: Int,
        isRestricted: Boolean,
    ): UserRecord {
        return LegacyUserDataHelper.createRecord(
            context = applicationContext,
            selectedUserId = selectedUserId,
            actionType = action,
            isRestricted = isRestricted,
            isSwitchToEnabled =
                canSwitchUsers(selectedUserId) &&
                    // If the user is auto-created is must not be currently resetting.
                    !(isGuestUserAutoCreated && isGuestUserResetting),
        )
    }

    private fun switchUser(userId: Int) {
        // TODO(b/246631653): track jank and latency like in the old impl.
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
                        notifyCallbacks()
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
                filterAndMapToUserModel(
                    userInfo = userInfo,
                    selectedUserId = selectedUserId,
                    canSwitchUsers = canSwitchUsers,
                    isUserSwitcherEnabled = isUserSwitcherEnabled,
                )
            }
    }

    /**
     * Maps UserInfo to UserModel based on some parameters and return null under certain conditions
     * to be filtered out.
     */
    private suspend fun filterAndMapToUserModel(
        userInfo: UserInfo,
        selectedUserId: Int,
        canSwitchUsers: Boolean,
        isUserSwitcherEnabled: Boolean,
    ): UserModel? {
        return when {
            // When the user switcher is not enabled in settings, we only show the primary user.
            !isUserSwitcherEnabled && !userInfo.isPrimary -> null
            // We avoid showing disabled users.
            !userInfo.isEnabled -> null
            // We meet the conditions to return the UserModel.
            userInfo.isGuest || userInfo.supportsSwitchToByUser() ->
                toUserModel(userInfo, selectedUserId, canSwitchUsers)
            else -> null
        }
    }

    /** Maps UserInfo to UserModel based on some parameters. */
    private suspend fun toUserModel(
        userInfo: UserInfo,
        selectedUserId: Int,
        canSwitchUsers: Boolean
    ): UserModel {
        val userId = userInfo.id
        val isSelected = userId == selectedUserId
        return if (userInfo.isGuest) {
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
        } else {
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

    private fun canCreateGuestUser(settings: UserSwitcherSettingsModel): Boolean {
        return guestUserInteractor.isGuestUserAutoCreated ||
            UserActionsUtil.canCreateGuest(
                manager,
                repository,
                settings.isUserSwitcherEnabled,
                settings.isAddUsersFromLockscreen,
            )
    }

    companion object {
        private const val TAG = "UserInteractor"
    }
}
