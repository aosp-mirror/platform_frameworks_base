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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.provider.DeviceConfig
import android.util.Log
import com.android.compose.animation.scene.SceneKey
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceconfig.domain.interactor.DeviceConfigInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.navigation.domain.interactor.NavigationInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class StatusBarStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val applicationContext: Context,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val sceneInteractor: SceneInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val sceneContainerOcclusionInteractor: SceneContainerOcclusionInteractor,
    private val deviceConfigInteractor: DeviceConfigInteractor,
    private val navigationInteractor: NavigationInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val powerInteractor: PowerInteractor,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val statusBarService: IStatusBarService,
) : CoreStartable {

    private val disableToken: IBinder = Binder()

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        applicationScope.launch {
            combine(
                    selectedUserInteractor.selectedUser,
                    sceneInteractor.currentScene,
                    deviceEntryInteractor.isDeviceEntered,
                    sceneContainerOcclusionInteractor.invisibleDueToOcclusion,
                    deviceConfigInteractor.property(
                        namespace = DeviceConfig.NAMESPACE_SYSTEMUI,
                        name = SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN,
                        default = true,
                    ),
                    navigationInteractor.isGesturalMode,
                    authenticationInteractor.authenticationMethod,
                    powerInteractor.detailedWakefulness,
                ) { values ->
                    val selectedUserId = values[0] as Int
                    val currentScene = values[1] as SceneKey
                    val isDeviceEntered = values[2] as Boolean
                    val isOccluded = values[3] as Boolean
                    val isShowHomeOverLockscreen = values[4] as Boolean
                    val isGesturalMode = values[5] as Boolean
                    val authenticationMethod = values[6] as AuthenticationMethodModel
                    val wakefulnessModel = values[7] as WakefulnessModel

                    val isForceHideHomeAndRecents = currentScene == Scenes.Bouncer
                    val isKeyguardShowing = !isDeviceEntered
                    val isPowerGestureIntercepted =
                        with(wakefulnessModel) {
                            isAwake() &&
                                powerButtonLaunchGestureTriggered &&
                                lastSleepReason == WakeSleepReason.POWER_BUTTON
                        }

                    var flags = StatusBarManager.DISABLE_NONE

                    if (isForceHideHomeAndRecents || (isKeyguardShowing && !isOccluded)) {
                        if (!isShowHomeOverLockscreen || !isGesturalMode) {
                            flags = flags or StatusBarManager.DISABLE_HOME
                        }
                        flags = flags or StatusBarManager.DISABLE_RECENT
                    }

                    if (
                        isPowerGestureIntercepted &&
                            isOccluded &&
                            authenticationMethod.isSecure &&
                            deviceEntryFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()
                    ) {
                        flags = flags or StatusBarManager.DISABLE_RECENT
                    }

                    selectedUserId to flags
                }
                .distinctUntilChanged()
                .collect { (selectedUserId, flags) ->
                    @SuppressLint("WrongConstant", "NonInjectedService")
                    if (applicationContext.getSystemService(Context.STATUS_BAR_SERVICE) == null) {
                        Log.w(TAG, "Could not get status bar manager")
                        return@collect
                    }

                    withContext(backgroundDispatcher) {
                        try {
                            statusBarService.disableForUser(
                                flags,
                                disableToken,
                                applicationContext.packageName,
                                selectedUserId,
                            )
                        } catch (e: RemoteException) {
                            Log.d(TAG, "Failed to set disable flags: $flags", e)
                        }
                    }
                }
        }
    }

    override fun onBootCompleted() {
        applicationScope.launch(backgroundDispatcher) {
            try {
                statusBarService.disableForUser(
                    StatusBarManager.DISABLE_NONE,
                    disableToken,
                    applicationContext.packageName,
                    selectedUserInteractor.getSelectedUserId(),
                )
            } catch (e: RemoteException) {
                Log.d(TAG, "Failed to clear flags", e)
            }
        }
    }

    companion object {
        private const val TAG = "StatusBarStartable"
    }
}
