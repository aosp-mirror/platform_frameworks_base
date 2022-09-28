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

package com.android.systemui.user.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.systemui.R
import com.android.systemui.common.ui.drawable.CircularDrawable
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Models UI state for the user switcher feature. */
class UserSwitcherViewModel
private constructor(
    private val userInteractor: UserInteractor,
    private val guestUserInteractor: GuestUserInteractor,
    private val powerInteractor: PowerInteractor,
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    private val isNewImpl: Boolean
        get() = !featureFlags.isEnabled(Flags.USER_INTERACTOR_AND_REPO_USE_CONTROLLER)

    /** On-device users. */
    val users: Flow<List<UserViewModel>> =
        userInteractor.users.map { models -> models.map { user -> toViewModel(user) } }

    /** The maximum number of columns that the user selection grid should use. */
    val maximumUserColumns: Flow<Int> =
        users.map { LegacyUserUiHelper.getMaxUserSwitcherItemColumns(it.size) }

    private val _isMenuVisible = MutableStateFlow(false)
    /**
     * Whether the user action menu should be shown. Once the action menu is dismissed/closed, the
     * consumer must invoke [onMenuClosed].
     */
    val isMenuVisible: Flow<Boolean> = _isMenuVisible
    /** The user action menu. */
    val menu: Flow<List<UserActionViewModel>> =
        userInteractor.actions.map { actions ->
            if (isNewImpl && actions.isNotEmpty()) {
                    // If we have actions, we add NAVIGATE_TO_USER_MANAGEMENT because that's a user
                    // switcher specific action that is not known to the our data source or other
                    // features.
                    actions + listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)
                } else {
                    actions
                }
                .map { action -> toViewModel(action) }
        }

    /** Whether the button to open the user action menu is visible. */
    val isOpenMenuButtonVisible: Flow<Boolean> = menu.map { it.isNotEmpty() }

    private val hasCancelButtonBeenClicked = MutableStateFlow(false)
    private val isFinishRequiredDueToExecutedAction = MutableStateFlow(false)

    /**
     * Whether the observer should finish the experience. Once consumed, [onFinished] must be called
     * by the consumer.
     */
    val isFinishRequested: Flow<Boolean> = createFinishRequestedFlow()

    /** Notifies that the user has clicked the cancel button. */
    fun onCancelButtonClicked() {
        hasCancelButtonBeenClicked.value = true
    }

    /**
     * Notifies that the user experience is finished.
     *
     * Call this after consuming [isFinishRequested] with a `true` value in order to mark it as
     * consumed such that the next consumer doesn't immediately finish itself.
     */
    fun onFinished() {
        hasCancelButtonBeenClicked.value = false
        isFinishRequiredDueToExecutedAction.value = false
    }

    /** Notifies that the user has clicked the "open menu" button. */
    fun onOpenMenuButtonClicked() {
        _isMenuVisible.value = true
    }

    /**
     * Notifies that the user has dismissed or closed the user action menu.
     *
     * Call this after consuming [isMenuVisible] with a `true` value in order to reset it to `false`
     * such that the next consumer doesn't immediately show the menu again.
     */
    fun onMenuClosed() {
        _isMenuVisible.value = false
    }

    private fun createFinishRequestedFlow(): Flow<Boolean> {
        var mostRecentSelectedUserId: Int? = null
        var mostRecentIsInteractive: Boolean? = null

        return combine(
            // When the user is switched, we should finish.
            userInteractor.selectedUser
                .map { it.id }
                .map {
                    val selectedUserChanged =
                        mostRecentSelectedUserId != null && mostRecentSelectedUserId != it
                    mostRecentSelectedUserId = it
                    selectedUserChanged
                },
            // When the screen turns off, we should finish.
            powerInteractor.isInteractive.map {
                val screenTurnedOff = mostRecentIsInteractive == true && !it
                mostRecentIsInteractive = it
                screenTurnedOff
            },
            // When the cancel button is clicked, we should finish.
            hasCancelButtonBeenClicked,
            // If an executed action told us to finish, we should finish,
            isFinishRequiredDueToExecutedAction,
        ) { selectedUserChanged, screenTurnedOff, cancelButtonClicked, executedActionFinish ->
            selectedUserChanged || screenTurnedOff || cancelButtonClicked || executedActionFinish
        }
    }

    private fun toViewModel(
        model: UserModel,
    ): UserViewModel {
        return UserViewModel(
            viewKey = model.id,
            name = model.name,
            image = CircularDrawable(model.image),
            isSelectionMarkerVisible = model.isSelected,
            alpha =
                if (model.isSelectable) {
                    LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA
                } else {
                    LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_NOT_SELECTABLE_ALPHA
                },
            onClicked = createOnSelectedCallback(model),
        )
    }

    private fun toViewModel(
        model: UserActionModel,
    ): UserActionViewModel {
        return UserActionViewModel(
            viewKey = model.ordinal.toLong(),
            iconResourceId =
                if (model == UserActionModel.NAVIGATE_TO_USER_MANAGEMENT) {
                    R.drawable.ic_manage_users
                } else {
                    LegacyUserUiHelper.getUserSwitcherActionIconResourceId(
                        isAddSupervisedUser = model == UserActionModel.ADD_SUPERVISED_USER,
                        isAddUser = model == UserActionModel.ADD_USER,
                        isGuest = model == UserActionModel.ENTER_GUEST_MODE,
                    )
                },
            textResourceId =
                if (model == UserActionModel.NAVIGATE_TO_USER_MANAGEMENT) {
                    R.string.manage_users
                } else {
                    LegacyUserUiHelper.getUserSwitcherActionTextResourceId(
                        isGuest = model == UserActionModel.ENTER_GUEST_MODE,
                        isGuestUserAutoCreated = guestUserInteractor.isGuestUserAutoCreated,
                        isGuestUserResetting = guestUserInteractor.isGuestUserResetting,
                        isAddSupervisedUser = model == UserActionModel.ADD_SUPERVISED_USER,
                        isAddUser = model == UserActionModel.ADD_USER,
                    )
                },
            onClicked = {
                userInteractor.executeAction(action = model)
                // We don't finish because we want to show a dialog over the full-screen UI and
                // that dialog can be dismissed in case the user changes their mind and decides not
                // to add a user.
                //
                // We finish for all other actions because they navigate us away from the
                // full-screen experience or are destructive (like changing to the guest user).
                val shouldFinish = model != UserActionModel.ADD_USER
                if (shouldFinish) {
                    isFinishRequiredDueToExecutedAction.value = true
                }
            },
        )
    }

    private fun createOnSelectedCallback(model: UserModel): (() -> Unit)? {
        return if (!model.isSelectable) {
            null
        } else {
            { userInteractor.selectUser(model.id) }
        }
    }

    class Factory
    @Inject
    constructor(
        private val userInteractor: UserInteractor,
        private val guestUserInteractor: GuestUserInteractor,
        private val powerInteractor: PowerInteractor,
        private val featureFlags: FeatureFlags,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return UserSwitcherViewModel(
                userInteractor = userInteractor,
                guestUserInteractor = guestUserInteractor,
                powerInteractor = powerInteractor,
                featureFlags = featureFlags,
            )
                as T
        }
    }
}
