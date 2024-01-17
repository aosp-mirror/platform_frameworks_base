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

package com.android.systemui.deviceentry.ui.binder

import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.android.keyguard.ActiveUnlockConfig
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.Assert
import com.android.systemui.util.sensors.AsyncSensorManager
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Triggers face auth and active unlock on lift when the device is showing the lock screen or
 * bouncer. Only initialized if face auth is supported on the device. Not to be confused with the
 * lift to wake gesture which is handled by {@link com.android.server.policy.PhoneWindowManager}.
 */
@SysUISingleton
class LiftToRunFaceAuthBinder
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val packageManager: PackageManager,
    private val asyncSensorManager: AsyncSensorManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    keyguardInteractor: KeyguardInteractor,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    powerInteractor: PowerInteractor,
) : CoreStartable {

    private var pickupSensor: Sensor? = null
    private val isListening: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val stoppedListening: Flow<Unit> = isListening.filterNot { it }.map {} // map to Unit

    private val onAwakeKeyguard: Flow<Boolean> =
        combine(
            powerInteractor.isInteractive,
            keyguardInteractor.isKeyguardVisible,
        ) { isInteractive, isKeyguardVisible ->
            isInteractive && isKeyguardVisible
        }
    private val bouncerShowing: Flow<Boolean> =
        combine(
            primaryBouncerInteractor.isShowing,
            alternateBouncerInteractor.isVisible,
        ) { primaryBouncerShowing, alternateBouncerShowing ->
            primaryBouncerShowing || alternateBouncerShowing
        }
    private val listenForPickupSensor: Flow<Boolean> =
        combine(
            stoppedListening,
            bouncerShowing,
            onAwakeKeyguard,
        ) { _, bouncerShowing, onAwakeKeyguard ->
            (onAwakeKeyguard || bouncerShowing) &&
                deviceEntryFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()
        }

    override fun start() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            init()
        }
    }

    private fun init() {
        pickupSensor = asyncSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE)
        scope.launch {
            listenForPickupSensor.collect { listenForPickupSensor ->
                updateListeningState(listenForPickupSensor)
            }
        }
    }

    private val listener: TriggerEventListener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                Assert.isMainThread()
                deviceEntryFaceAuthInteractor.onDeviceLifted()
                keyguardUpdateMonitor.requestActiveUnlock(
                    ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE,
                    "KeyguardLiftController"
                )

                // Not listening anymore since trigger events unregister themselves
                isListening.value = false
            }
        }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("LiftToRunFaceAuthBinder:")
        pw.println("  pickupSensor: $pickupSensor")
        pw.println("  isListening: ${isListening.value}")
    }

    private fun updateListeningState(shouldListen: Boolean) {
        if (pickupSensor == null) {
            return
        }
        if (shouldListen != isListening.value) {
            isListening.value = shouldListen

            if (shouldListen) {
                asyncSensorManager.requestTriggerSensor(listener, pickupSensor)
            } else {
                asyncSensorManager.cancelTriggerSensor(listener, pickupSensor)
            }
        }
    }
}
