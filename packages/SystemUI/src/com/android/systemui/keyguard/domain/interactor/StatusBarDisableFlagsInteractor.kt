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

package com.android.systemui.keyguard.domain.interactor

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.provider.DeviceConfig
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
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.navigation.domain.interactor.NavigationInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logic around StatusBarService#disableForUser, which is used to disable the home and recents
 * button in certain device states.
 *
 * TODO(b/362313975): Remove post-Flexiglass, this duplicates StatusBarStartable logic.
 */
@SysUISingleton
class StatusBarDisableFlagsInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val statusBarService: IStatusBarService,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    selectedUserInteractor: SelectedUserInteractor,
    deviceConfigInteractor: DeviceConfigInteractor,
    navigationInteractor: NavigationInteractor,
    authenticationInteractor: AuthenticationInteractor,
    powerInteractor: PowerInteractor,
) : CoreStartable {

    private val disableToken: IBinder = Binder()

    private val disableFlagsForUserId =
        if (!KeyguardWmStateRefactor.isEnabled || SceneContainerFlag.isEnabled) {
            flowOf(Pair(0, StatusBarManager.DISABLE_NONE))
        } else {
            combine(
                    selectedUserInteractor.selectedUser,
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map { it.to },
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
                    val startedState = values[1] as KeyguardState
                    val isShowHomeOverLockscreen = values[2] as Boolean
                    val isGesturalMode = values[3] as Boolean
                    val authenticationMethod = values[4] as AuthenticationMethodModel
                    val wakefulnessModel = values[5] as WakefulnessModel
                    val isOccluded = startedState == KeyguardState.OCCLUDED

                    val hideHomeAndRecentsForBouncer =
                        startedState == KeyguardState.PRIMARY_BOUNCER ||
                            startedState == KeyguardState.ALTERNATE_BOUNCER
                    val isKeyguardShowing = startedState != KeyguardState.GONE
                    val isPowerGestureIntercepted =
                        with(wakefulnessModel) {
                            isAwake() &&
                                powerButtonLaunchGestureTriggered &&
                                lastSleepReason == WakeSleepReason.POWER_BUTTON
                        }

                    var flags = StatusBarManager.DISABLE_NONE

                    if (hideHomeAndRecentsForBouncer || (isKeyguardShowing && !isOccluded)) {
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
        }

    @SuppressLint("WrongConstant", "NonInjectedService")
    override fun start() {
        if (!KeyguardWmStateRefactor.isEnabled || SceneContainerFlag.isEnabled) {
            return
        }

        scope.launch {
            disableFlagsForUserId.collect { (selectedUserId, flags) ->
                if (applicationContext.getSystemService(Context.STATUS_BAR_SERVICE) == null) {
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
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
