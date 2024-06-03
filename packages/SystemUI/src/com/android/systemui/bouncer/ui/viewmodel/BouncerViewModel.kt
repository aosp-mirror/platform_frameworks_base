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

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.type
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationWipeModel
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.inputmethod.domain.interactor.InputMethodInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
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
import kotlinx.coroutines.flow.combine
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
    private val inputMethodInteractor: InputMethodInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val devicePolicyManager: DevicePolicyManager,
    bouncerMessageViewModel: BouncerMessageViewModel,
    flags: ComposeBouncerFlags,
    selectedUser: Flow<UserViewModel>,
    users: Flow<List<UserViewModel>>,
    userSwitcherMenu: Flow<List<UserActionViewModel>>,
    actionButton: Flow<BouncerActionButtonModel?>,
) {
    val selectedUserImage: StateFlow<Bitmap?> =
        selectedUser
            .map { it.image.toBitmap() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        bouncerInteractor.dismissDestination
            .map(::destinationSceneMap)
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                initialValue = destinationSceneMap(Scenes.Lockscreen),
            )

    val message: BouncerMessageViewModel = bouncerMessageViewModel

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

    // Handle to the scope of the child ViewModel (stored in [authMethod]).
    private var childViewModelScope: CoroutineScope? = null

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
     * If `null`, the lockout dialog should not be shown.
     */
    private val lockoutDialogMessage = MutableStateFlow<String?>(null)

    /**
     * A message for a dialog to show when the user has attempted the wrong credential too many
     * times and their user/profile/device data is at risk of being wiped due to a Device Manager
     * policy.
     *
     * If `null`, the wipe dialog should not be shown.
     */
    private val wipeDialogMessage = MutableStateFlow<String?>(null)

    /**
     * Models the dialog to be shown to the user, or `null` if no dialog should be shown.
     *
     * Once the dialog is shown, the UI should call [DialogViewModel.onDismiss] when the user
     * dismisses this dialog.
     */
    val dialogViewModel: StateFlow<DialogViewModel?> =
        combine(wipeDialogMessage, lockoutDialogMessage) { _, _ -> createDialogViewModel() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = createDialogViewModel(),
            )

    /**
     * The bouncer action button (Return to Call / Emergency Call). If `null`, the button should not
     * be shown.
     */
    val actionButton: StateFlow<BouncerActionButtonModel?> =
        actionButton.stateIn(
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

    private val isInputEnabled: StateFlow<Boolean> =
        bouncerMessageViewModel.isLockoutMessagePresent
            .map { lockoutMessagePresent -> !lockoutMessagePresent }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = authenticationInteractor.lockoutEndTimestamp == null,
            )

    init {
        if (flags.isComposeBouncerOrSceneContainerEnabled()) {
            // Keeps the upcoming wipe dialog up-to-date.
            applicationScope.launch {
                authenticationInteractor.upcomingWipe.collect { wipeModel ->
                    wipeDialogMessage.value = wipeModel?.message
                }
            }
        }
    }

    private fun isSideBySideSupported(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return isUserSwitcherVisible || authMethod !is PasswordBouncerViewModel
    }

    private fun isFoldSplitRequired(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return authMethod !is PasswordBouncerViewModel
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
                    authenticationMethod = authenticationMethod,
                    onIntentionalUserInput = ::onIntentionalUserInput
                )
            is AuthenticationMethodModel.Sim ->
                PinBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    simBouncerInteractor = simBouncerInteractor,
                    authenticationMethod = authenticationMethod,
                    onIntentionalUserInput = ::onIntentionalUserInput
                )
            is AuthenticationMethodModel.Password ->
                PasswordBouncerViewModel(
                    viewModelScope = newViewModelScope,
                    isInputEnabled = isInputEnabled,
                    interactor = bouncerInteractor,
                    inputMethodInteractor = inputMethodInteractor,
                    selectedUserInteractor = selectedUserInteractor,
                    onIntentionalUserInput = ::onIntentionalUserInput
                )
            is AuthenticationMethodModel.Pattern ->
                PatternBouncerViewModel(
                    applicationContext = applicationContext,
                    viewModelScope = newViewModelScope,
                    interactor = bouncerInteractor,
                    isInputEnabled = isInputEnabled,
                    onIntentionalUserInput = ::onIntentionalUserInput
                )
            else -> null
        }
    }

    private fun onIntentionalUserInput() {
        message.showDefaultMessage()
        bouncerInteractor.onIntentionalUserInput()
    }

    private fun createChildCoroutineScope(parentScope: CoroutineScope): CoroutineScope {
        return CoroutineScope(
            SupervisorJob(parent = parentScope.coroutineContext.job) + mainDispatcher
        )
    }

    /**
     * @return A message warning the user that the user/profile/device will be wiped upon a further
     *   [AuthenticationWipeModel.remainingAttempts] unsuccessful authentication attempts.
     */
    private fun AuthenticationWipeModel.getAlmostAtWipeMessage(): String {
        val message =
            applicationContext.getString(
                wipeTarget.messageIdForAlmostWipe,
                failedAttempts,
                remainingAttempts,
            )
        return if (wipeTarget == AuthenticationWipeModel.WipeTarget.ManagedProfile) {
            devicePolicyManager.resources.getString(
                DevicePolicyResources.Strings.SystemUi
                    .KEYGUARD_DIALOG_FAILED_ATTEMPTS_ALMOST_ERASING_PROFILE,
                { message },
                failedAttempts,
                remainingAttempts,
            )
                ?: message
        } else {
            message
        }
    }

    /**
     * @return A message informing the user that their user/profile/device will be wiped promptly.
     */
    private fun AuthenticationWipeModel.getWipeMessage(): String {
        val message = applicationContext.getString(wipeTarget.messageIdForWipe, failedAttempts)
        return if (wipeTarget == AuthenticationWipeModel.WipeTarget.ManagedProfile) {
            devicePolicyManager.resources.getString(
                DevicePolicyResources.Strings.SystemUi
                    .KEYGUARD_DIALOG_FAILED_ATTEMPTS_ERASING_PROFILE,
                { message },
                failedAttempts,
            )
                ?: message
        } else {
            message
        }
    }

    private val AuthenticationWipeModel.message: String
        get() = if (remainingAttempts > 0) getAlmostAtWipeMessage() else getWipeMessage()

    private fun createDialogViewModel(): DialogViewModel? {
        val wipeText = wipeDialogMessage.value
        val lockoutText = lockoutDialogMessage.value
        return when {
            // The wipe dialog takes priority over the lockout dialog.
            wipeText != null ->
                DialogViewModel(
                    text = wipeText,
                    onDismiss = { wipeDialogMessage.value = null },
                )
            lockoutText != null ->
                DialogViewModel(
                    text = lockoutText,
                    onDismiss = { lockoutDialogMessage.value = null },
                )
            else -> null // No dialog to show.
        }
    }

    private fun destinationSceneMap(prevScene: SceneKey) =
        mapOf(
            Back to UserActionResult(prevScene),
            Swipe(SwipeDirection.Down) to UserActionResult(prevScene),
        )

    /**
     * Notifies that a key event has occurred.
     *
     * @return `true` when the [KeyEvent] was consumed as user input on bouncer; `false` otherwise.
     */
    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        return (authMethodViewModel.value as? PinBouncerViewModel)?.onKeyEvent(
            keyEvent.type,
            keyEvent.nativeKeyEvent.keyCode
        )
            ?: false
    }

    data class DialogViewModel(
        val text: String,

        /** Callback to run after the dialog has been dismissed by the user. */
        val onDismiss: () -> Unit,
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
        imeInteractor: InputMethodInteractor,
        simBouncerInteractor: SimBouncerInteractor,
        actionButtonInteractor: BouncerActionButtonInteractor,
        authenticationInteractor: AuthenticationInteractor,
        selectedUserInteractor: SelectedUserInteractor,
        flags: ComposeBouncerFlags,
        userSwitcherViewModel: UserSwitcherViewModel,
        devicePolicyManager: DevicePolicyManager,
        bouncerMessageViewModel: BouncerMessageViewModel,
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            mainDispatcher = mainDispatcher,
            bouncerInteractor = bouncerInteractor,
            inputMethodInteractor = imeInteractor,
            simBouncerInteractor = simBouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            selectedUserInteractor = selectedUserInteractor,
            devicePolicyManager = devicePolicyManager,
            bouncerMessageViewModel = bouncerMessageViewModel,
            flags = flags,
            selectedUser = userSwitcherViewModel.selectedUser,
            users = userSwitcherViewModel.users,
            userSwitcherMenu = userSwitcherViewModel.menu,
            actionButton = actionButtonInteractor.actionButton,
        )
    }
}
