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
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationWipeModel
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Models UI state for the content of the bouncer scene. */
class BouncerSceneContentViewModel
@AssistedInject
constructor(
    @Application private val applicationContext: Context,
    private val bouncerInteractor: BouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val devicePolicyManager: DevicePolicyManager,
    private val bouncerMessageViewModelFactory: BouncerMessageViewModel.Factory,
    private val userSwitcher: UserSwitcherViewModel,
    private val actionButtonInteractor: BouncerActionButtonInteractor,
    private val pinViewModelFactory: PinBouncerViewModel.Factory,
    private val patternViewModelFactory: PatternBouncerViewModel.Factory,
    private val passwordViewModelFactory: PasswordBouncerViewModel.Factory,
    private val bouncerHapticPlayer: BouncerHapticPlayer,
) : ExclusiveActivatable() {
    private val _selectedUserImage = MutableStateFlow<Bitmap?>(null)
    val selectedUserImage: StateFlow<Bitmap?> = _selectedUserImage.asStateFlow()

    val message: BouncerMessageViewModel by lazy { bouncerMessageViewModelFactory.create() }

    private val _userSwitcherDropdown =
        MutableStateFlow<List<UserSwitcherDropdownItemViewModel>>(emptyList())
    val userSwitcherDropdown: StateFlow<List<UserSwitcherDropdownItemViewModel>> =
        _userSwitcherDropdown.asStateFlow()

    val isUserSwitcherVisible: Boolean
        get() = bouncerInteractor.isUserSwitcherVisible

    /** View-model for the current UI, based on the current authentication method. */
    private val _authMethodViewModel = MutableStateFlow<AuthMethodBouncerViewModel?>(null)
    val authMethodViewModel: StateFlow<AuthMethodBouncerViewModel?> =
        _authMethodViewModel.asStateFlow()

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

    private val _dialogViewModel = MutableStateFlow<DialogViewModel?>(createDialogViewModel())
    /**
     * Models the dialog to be shown to the user, or `null` if no dialog should be shown.
     *
     * Once the dialog is shown, the UI should call [DialogViewModel.onDismiss] when the user
     * dismisses this dialog.
     */
    val dialogViewModel: StateFlow<DialogViewModel?> = _dialogViewModel.asStateFlow()

    private val _actionButton = MutableStateFlow<BouncerActionButtonModel?>(null)
    /**
     * The bouncer action button (Return to Call / Emergency Call). If `null`, the button should not
     * be shown.
     */
    val actionButton: StateFlow<BouncerActionButtonModel?> = _actionButton.asStateFlow()

    private val _isSideBySideSupported =
        MutableStateFlow(isSideBySideSupported(authMethodViewModel.value))
    /**
     * Whether the "side-by-side" layout is supported.
     *
     * When presented on its own, without a user switcher (e.g. not on communal devices like
     * tablets, for example), some authentication method UIs don't do well if they're shown in the
     * side-by-side layout; these need to be shown with the standard layout so they can take up as
     * much width as possible.
     */
    val isSideBySideSupported: StateFlow<Boolean> = _isSideBySideSupported.asStateFlow()

    private val _isFoldSplitRequired =
        MutableStateFlow(isFoldSplitRequired(authMethodViewModel.value))
    /**
     * Whether the splitting the UI around the fold seam (where the hinge is on a foldable device)
     * is required.
     */
    val isFoldSplitRequired: StateFlow<Boolean> = _isFoldSplitRequired.asStateFlow()

    private val _isInputEnabled =
        MutableStateFlow(authenticationInteractor.lockoutEndTimestamp == null)
    private val isInputEnabled: StateFlow<Boolean> = _isInputEnabled.asStateFlow()

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { message.activate() }
            launch {
                authenticationInteractor.authenticationMethod
                    .map(::getChildViewModel)
                    .collectLatest { childViewModelOrNull ->
                        _authMethodViewModel.value = childViewModelOrNull
                        childViewModelOrNull?.let { traceCoroutine(it.traceName) { it.activate() } }
                    }
            }

            launch {
                authenticationInteractor.upcomingWipe.collect { wipeModel ->
                    wipeDialogMessage.value = wipeModel?.message
                }
            }

            launch {
                userSwitcher.selectedUser
                    .map { it.image.toBitmap() }
                    .collect { _selectedUserImage.value = it }
            }

            launch {
                combine(userSwitcher.users, userSwitcher.menu) { users, actions ->
                        users.map { user ->
                            UserSwitcherDropdownItemViewModel(
                                icon = Icon.Loaded(user.image, contentDescription = null),
                                text = user.name,
                                onClick = user.onClicked ?: {},
                            )
                        } +
                            actions.map { action ->
                                UserSwitcherDropdownItemViewModel(
                                    icon =
                                        Icon.Resource(
                                            action.iconResourceId,
                                            contentDescription = null,
                                        ),
                                    text = Text.Resource(action.textResourceId),
                                    onClick = action.onClicked,
                                )
                            }
                    }
                    .collect { _userSwitcherDropdown.value = it }
            }

            launch {
                combine(wipeDialogMessage, lockoutDialogMessage) { _, _ -> createDialogViewModel() }
                    .collect { _dialogViewModel.value = it }
            }

            launch { actionButtonInteractor.actionButton.collect { _actionButton.value = it } }

            launch {
                authMethodViewModel
                    .map { authMethod -> isSideBySideSupported(authMethod) }
                    .collect { _isSideBySideSupported.value = it }
            }

            launch {
                authMethodViewModel
                    .map { authMethod -> isFoldSplitRequired(authMethod) }
                    .collect { _isFoldSplitRequired.value = it }
            }

            launch {
                message.isLockoutMessagePresent
                    .map { lockoutMessagePresent -> !lockoutMessagePresent }
                    .collect { _isInputEnabled.value = it }
            }

            awaitCancellation()
        }
    }

    private fun isSideBySideSupported(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return isUserSwitcherVisible || authMethod !is PasswordBouncerViewModel
    }

    private fun isFoldSplitRequired(authMethod: AuthMethodBouncerViewModel?): Boolean {
        return authMethod !is PasswordBouncerViewModel
    }

    private fun getChildViewModel(
        authenticationMethod: AuthenticationMethodModel
    ): AuthMethodBouncerViewModel? {
        // If the current child view-model matches the authentication method, reuse it instead of
        // creating a new instance.
        val childViewModel = authMethodViewModel.value
        if (authenticationMethod == childViewModel?.authenticationMethod) {
            return childViewModel
        }

        return when (authenticationMethod) {
            is AuthenticationMethodModel.Pin ->
                pinViewModelFactory.create(
                    authenticationMethod = authenticationMethod,
                    onIntentionalUserInput = ::onIntentionalUserInput,
                    isInputEnabled = isInputEnabled,
                    bouncerHapticPlayer = bouncerHapticPlayer,
                )
            is AuthenticationMethodModel.Sim ->
                pinViewModelFactory.create(
                    authenticationMethod = authenticationMethod,
                    onIntentionalUserInput = ::onIntentionalUserInput,
                    isInputEnabled = isInputEnabled,
                    bouncerHapticPlayer = bouncerHapticPlayer,
                )
            is AuthenticationMethodModel.Password ->
                passwordViewModelFactory.create(
                    onIntentionalUserInput = ::onIntentionalUserInput,
                    isInputEnabled = isInputEnabled,
                )
            is AuthenticationMethodModel.Pattern ->
                patternViewModelFactory.create(
                    onIntentionalUserInput = ::onIntentionalUserInput,
                    isInputEnabled = isInputEnabled,
                    bouncerHapticPlayer = bouncerHapticPlayer,
                )
            else -> null
        }
    }

    private fun onIntentionalUserInput() {
        message.showDefaultMessage()
        bouncerInteractor.onIntentionalUserInput()
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
            ) ?: message
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
            ) ?: message
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
                DialogViewModel(text = wipeText, onDismiss = { wipeDialogMessage.value = null })
            lockoutText != null ->
                DialogViewModel(
                    text = lockoutText,
                    onDismiss = { lockoutDialogMessage.value = null },
                )
            else -> null // No dialog to show.
        }
    }

    /**
     * Notifies that a key event has occurred.
     *
     * @return `true` when the [KeyEvent] was consumed as user input on bouncer; `false` otherwise.
     */
    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        return (authMethodViewModel.value as? PinBouncerViewModel)?.onKeyEvent(
            keyEvent.type,
            keyEvent.nativeKeyEvent.keyCode,
        ) ?: false
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

    @AssistedFactory
    interface Factory {
        fun create(): BouncerSceneContentViewModel
    }
}
