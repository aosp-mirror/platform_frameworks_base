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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.user.ui.viewmodel.UserViewModel
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Holds UI state and handles user input on bouncer UIs. */
class BouncerViewModel(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val bouncerInteractor: BouncerInteractor,
    authenticationInteractor: AuthenticationInteractor,
    flags: SceneContainerFlags,
    selectedUser: Flow<UserViewModel>,
    users: Flow<List<UserViewModel>>,
    userSwitcherMenu: Flow<List<UserActionViewModel>>,
    actionButtonInteractor: BouncerActionButtonInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
) {
    val selectedUserImage: StateFlow<Bitmap?> =
        selectedUser
            .map { it.image.toBitmap() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    val userSwitcherDropdown: StateFlow<List<UserSwitcherDropdownItemViewModel>> =
        combine(
                users,
                userSwitcherMenu,
            ) { users, actions ->
                users.map { user ->
                    UserSwitcherDropdownItemViewModel(
                        icon = Icon.Loaded(user.image, contentDescription = null),
                        text = user.name,
                        onClick = user.onClicked ?: {},
                    )
                } +
                    actions.map { action ->
                        UserSwitcherDropdownItemViewModel(
                            icon = Icon.Resource(action.iconResourceId, contentDescription = null),
                            text = Text.Resource(action.textResourceId),
                            onClick = action.onClicked,
                        )
                    }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    val isUserSwitcherVisible: Boolean
        get() = bouncerInteractor.isUserSwitcherVisible

    private val isInputEnabled: StateFlow<Boolean> =
        bouncerInteractor.lockout
            .map { it == null }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = bouncerInteractor.lockout.value == null,
            )

    // Handle to the scope of the child ViewModel (stored in [authMethod]).
    private var childViewModelScope: CoroutineScope? = null
    private val _dialogMessage = MutableStateFlow<String?>(null)

    /** View-model for the current UI, based on the current authentication method. */
    val authMethodViewModel: StateFlow<AuthMethodBouncerViewModel?> =
        authenticationInteractor.authenticationMethod
            .map(::getChildViewModel)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /**
     * A message for a dialog to show when the user has attempted the wrong credential too many
     * times and now must wait a while before attempting again.
     *
     * If `null`, no dialog should be shown.
     *
     * Once the dialog is shown, the UI should call [onDialogDismissed] when the user dismisses this
     * dialog.
     */
    val dialogMessage: StateFlow<String?> = _dialogMessage.asStateFlow()

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<MessageViewModel> =
        combine(bouncerInteractor.message, bouncerInteractor.lockout) { message, lockout ->
                toMessageViewModel(message, isLockedOut = lockout != null)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    toMessageViewModel(
                        message = bouncerInteractor.message.value,
                        isLockedOut = bouncerInteractor.lockout.value != null,
                    ),
            )

    /**
     * The bouncer action button (Return to Call / Emergency Call). If `null`, the button should not
     * be shown.
     */
    val actionButton: StateFlow<BouncerActionButtonModel?> =
        actionButtonInteractor.actionButton.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    /**
     * Whether the "side-by-side" layout is supported.
     *
     * When presented on its own, without a user switcher (e.g. not on communal devices like
     * tablets, for example), some authentication method UIs don't do well if they're shown in the
     * side-by-side layout; these need to be shown with the standard layout so they can take up as
     * much width as possible.
     */
    val isSideBySideSupported: StateFlow<Boolean> =
        authMethodViewModel
            .map { authMethod -> isSideBySideSupported(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isSideBySideSupported(authMethodViewModel.value),
            )

    /**
     * Whether the splitting the UI around the fold seam (where the hinge is on a foldable device)
     * is required.
     */
    val isFoldSplitRequired: StateFlow<Boolean> =
        authMethodViewModel
            .map { authMethod -> isFoldSplitRequired(authMethod) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isFoldSplitRequired(authMethodViewModel.value),
            )

    init {
        if (flags.isEnabled()) {
            applicationScope.launch {
                combine(bouncerInteractor.lockout, authMethodViewModel) {
                        lockout,
                        authMethodViewModel ->
                        if (lockout != null && authMethodViewModel != null) {
                            applicationContext.getString(
                                authMethodViewModel.lockoutMessageId,
                                lockout.failedAttemptCount,
                                lockout.remainingSeconds,
                            )
                        } else {
                            null
                        }
                    }
                    .distinctUntilChanged()
                    .collect { dialogMessage -> _dialogMessage.value = dialogMessage }
            }
        }
    }

    /** Notifies that the dialog has been dismissed by the user. */
    fun onDialogDismissed() {
        _dialogMessage.value = null
    }

    private fun isSideBySideSupported(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return isUserSwitcherVisible || authMethod !is PasswordBouncerViewModel
    }

    private fun isFoldSplitRequired(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return authMethod !is PasswordBouncerViewModel
    }

    private fun toMessageViewModel(
        message: String?,
        isLockedOut: Boolean,
    ): MessageViewModel {
        return MessageViewModel(
            text = message ?: "",
            isUpdateAnimated = !isLockedOut,
        )
    }

    private fun getChildViewModel(
        authenticationMethod: AuthenticationMethodModel,
    ): AuthMethodBouncerViewModel? {
        // If the current child view-model matches the authentication method, reuse it instead of
        // creating a new instance.
        val childViewModel = authMethodViewModel.value
        if (authenticationMethod == childViewModel?.authenticationMethod) {
            return childViewModel
        }

        childViewModelScope?.cancel()
        val newViewModelScope = createChildCoroutineScope(applicationScope)
        childViewModelScope = newViewModelScope
        return when (authenticationMethod) {
            is AuthenticationMethodModel.Pin ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    simBouncerInteractor = simBouncerInteractor,
                    authenticationMethod = authenticationMethod
                )
            is AuthenticationMethodModel.Sim ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    simBouncerInteractor = simBouncerInteractor,
                    authenticationMethod = authenticationMethod,
                )
            is AuthenticationMethodModel.Password ->
                PasswordBouncerViewModel(
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            is AuthenticationMethodModel.Pattern ->
                PatternBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                )
            else -> null
        }
    }

    private fun createChildCoroutineScope(parentScope: CoroutineScope): CoroutineScope {
        return CoroutineScope(
            SupervisorJob(parent = parentScope.coroutineContext.job) + mainDispatcher
        )
    }

    data class MessageViewModel(
        val text: String,

        /**
         * Whether updates to the message should be cross-animated from one message to another.
         *
         * If `false`, no animation should be applied, the message text should just be replaced
         * instantly.
         */
        val isUpdateAnimated: Boolean,
    )

    data class UserSwitcherDropdownItemViewModel(
        val icon: Icon,
        val text: Text,
        val onClick: () -> Unit,
    )
}

@Module
object BouncerViewModelModule {

    @Provides
    @SysUISingleton
    fun viewModel(
        @Application applicationContext: Context,
        @Application applicationScope: CoroutineScope,
        @Main mainDispatcher: CoroutineDispatcher,
        bouncerInteractor: BouncerInteractor,
        authenticationInteractor: AuthenticationInteractor,
        flags: SceneContainerFlags,
        userSwitcherViewModel: UserSwitcherViewModel,
        actionButtonInteractor: BouncerActionButtonInteractor,
        simBouncerInteractor: SimBouncerInteractor,
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            mainDispatcher = mainDispatcher,
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            flags = flags,
            selectedUser = userSwitcherViewModel.selectedUser,
            users = userSwitcherViewModel.users,
            userSwitcherMenu = userSwitcherViewModel.menu,
            actionButtonInteractor = actionButtonInteractor,
            simBouncerInteractor = simBouncerInteractor,
        )
    }
}
