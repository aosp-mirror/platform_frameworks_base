/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.inputdevice.tutorial.ui.viewmodel

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.domain.interactor.ConnectionState
import com.android.systemui.inputdevice.tutorial.domain.interactor.KeyboardTouchpadConnectionInteractor
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_KEY
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.RequiredHardware.KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.RequiredHardware.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.ACTION_KEY
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.touchpad.tutorial.domain.interactor.TouchpadGesturesInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Optional
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch

class KeyboardTouchpadTutorialViewModel(
    private val gesturesInteractor: Optional<TouchpadGesturesInteractor>,
    private val keyboardTouchpadConnectionInteractor: KeyboardTouchpadConnectionInteractor,
    private val hasTouchpadTutorialScreens: Boolean,
    private val logger: InputDeviceTutorialLogger,
    handle: SavedStateHandle
) : ViewModel(), DefaultLifecycleObserver {

    private fun startingScreen(handle: SavedStateHandle): Screen {
        val tutorialType: String? = handle[INTENT_TUTORIAL_TYPE_KEY]
        return if (tutorialType == INTENT_TUTORIAL_TYPE_KEYBOARD) ACTION_KEY else BACK_GESTURE
    }

    private val _screen = MutableStateFlow(startingScreen(handle))
    val screen: Flow<Screen> = _screen.filter { it.canBeShown() }

    private val _closeActivity: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val closeActivity: StateFlow<Boolean> = _closeActivity

    private val screensBackStack = ArrayDeque(listOf(_screen.value))

    private var connectionState: ConnectionState =
        ConnectionState(keyboardConnected = false, touchpadConnected = false)

    init {
        viewModelScope.launch {
            keyboardTouchpadConnectionInteractor.connectionState.collect {
                logger.logNewConnectionState(connectionState)
                connectionState = it
            }
        }

        viewModelScope.launch {
            screen
                .runningFold<Screen, Pair<Screen?, Screen?>>(null to null) {
                    previousScreensPair,
                    currentScreen ->
                    previousScreensPair.second to currentScreen
                }
                .collect { (previousScreen, currentScreen) ->
                    // ignore first empty emission
                    if (currentScreen != null) {
                        setupDeviceState(previousScreen, currentScreen)
                    }
                }
        }

        viewModelScope.launch {
            // close activity if screen requires touchpad but we don't have it. This can only happen
            // when current sysui build doesn't contain touchpad module dependency
            _screen
                .filterNot { it.canBeShown() }
                .collect {
                    logger.e(
                        "Touchpad is connected but touchpad module is missing, something went wrong"
                    )
                    _closeActivity.value = true
                }
        }
    }

    override fun onCleared() {
        // this shouldn't be needed as onTutorialInvisible should already clear device state but
        // it'd be really bad if we'd block gestures/shortcuts after leaving tutorial so just to be
        // extra sure...
        clearDeviceStateForScreen(_screen.value)
    }

    override fun onStart(owner: LifecycleOwner) {
        setupDeviceState(previousScreen = null, currentScreen = _screen.value)
    }

    override fun onStop(owner: LifecycleOwner) {
        clearDeviceStateForScreen(_screen.value)
    }

    fun onDoneButtonClicked() {
        var nextScreen = _screen.value.next()
        while (nextScreen != null) {
            if (requiredHardwarePresent(nextScreen)) {
                break
            }
            logger.logNextScreenMissingHardware(nextScreen)
            nextScreen = nextScreen.next()
        }
        if (nextScreen == null) {
            logger.d("Final screen reached, closing tutorial")
            _closeActivity.value = true
        } else {
            logger.logNextScreen(nextScreen)
            _screen.value = nextScreen
            screensBackStack.add(nextScreen)
        }
    }

    private fun Screen.canBeShown() = requiredHardware != TOUCHPAD || hasTouchpadTutorialScreens

    private fun setupDeviceState(previousScreen: Screen?, currentScreen: Screen) {
        logger.logMovingBetweenScreens(previousScreen, currentScreen)
        if (previousScreen?.requiredHardware == currentScreen.requiredHardware) return
        previousScreen?.let { clearDeviceStateForScreen(it) }
        when (currentScreen.requiredHardware) {
            TOUCHPAD -> gesturesInteractor.get().disableGestures()
            KEYBOARD -> {} // TODO(b/358587037) disabled keyboard shortcuts
        }
    }

    private fun clearDeviceStateForScreen(screen: Screen) {
        when (screen.requiredHardware) {
            TOUCHPAD -> gesturesInteractor.get().enableGestures()
            KEYBOARD -> {} // TODO(b/358587037) enable keyboard shortcuts
        }
    }

    private fun requiredHardwarePresent(screen: Screen): Boolean =
        when (screen.requiredHardware) {
            KEYBOARD -> connectionState.keyboardConnected
            TOUCHPAD -> connectionState.touchpadConnected
        }

    fun onBack() {
        if (screensBackStack.size <= 1) {
            _closeActivity.value = true
        } else {
            screensBackStack.removeLast()
            logger.logGoingBack(screensBackStack.last())
            _screen.value = screensBackStack.last()
        }
    }

    class Factory
    @AssistedInject
    constructor(
        private val gesturesInteractor: Optional<TouchpadGesturesInteractor>,
        private val keyboardTouchpadConnected: KeyboardTouchpadConnectionInteractor,
        private val logger: InputDeviceTutorialLogger,
        @Assisted private val hasTouchpadTutorialScreens: Boolean,
    ) : AbstractSavedStateViewModelFactory() {

        @AssistedFactory
        fun interface ViewModelFactoryAssistedProvider {
            fun create(@Assisted hasTouchpadTutorialScreens: Boolean): Factory
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle
        ): T =
            KeyboardTouchpadTutorialViewModel(
                gesturesInteractor,
                keyboardTouchpadConnected,
                hasTouchpadTutorialScreens,
                logger,
                handle
            )
                as T
    }
}

enum class RequiredHardware {
    TOUCHPAD,
    KEYBOARD
}

enum class Screen(val requiredHardware: RequiredHardware) {
    BACK_GESTURE(requiredHardware = TOUCHPAD),
    HOME_GESTURE(requiredHardware = TOUCHPAD),
    ACTION_KEY(requiredHardware = KEYBOARD);

    fun next(): Screen? =
        when (this) {
            BACK_GESTURE -> HOME_GESTURE
            HOME_GESTURE -> ACTION_KEY
            ACTION_KEY -> null
        }
}
