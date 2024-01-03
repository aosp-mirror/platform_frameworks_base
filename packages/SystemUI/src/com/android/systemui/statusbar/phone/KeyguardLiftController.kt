/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.android.keyguard.ActiveUnlockConfig
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.Assert
import com.android.systemui.util.sensors.AsyncSensorManager
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Triggers face auth on lift when the device is showing the lock screen. Only initialized
 * if face auth is supported on the device. Not to be confused with the lift to wake gesture
 * which is handled by {@link com.android.server.policy.PhoneWindowManager}.
 */
@SysUISingleton
class KeyguardLiftController @Inject constructor(
        private val context: Context,
        private val statusBarStateController: StatusBarStateController,
        private val asyncSensorManager: AsyncSensorManager,
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
        private val dumpManager: DumpManager,
        private val selectedUserInteractor: SelectedUserInteractor,
) : Dumpable, CoreStartable {

    private val pickupSensor = asyncSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE)
    private var isListening = false
    private var bouncerVisible = false

    override fun start() {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            init()
        }
    }

    private fun init() {
        dumpManager.registerDumpable(this)
        statusBarStateController.addCallback(statusBarStateListener)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        updateListeningState()
    }

    private val listener: TriggerEventListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Assert.isMainThread()
            // Not listening anymore since trigger events unregister themselves
            isListening = false
            updateListeningState()
            deviceEntryFaceAuthInteractor.onDeviceLifted()
            keyguardUpdateMonitor.requestActiveUnlock(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE,
                "KeyguardLiftController")
        }
    }

    private val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onKeyguardBouncerFullyShowingChanged(bouncer: Boolean) {
            bouncerVisible = bouncer
            updateListeningState()
        }

        override fun onKeyguardVisibilityChanged(visible: Boolean) {
            updateListeningState()
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) {
            updateListeningState()
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("KeyguardLiftController:")
        pw.println("  pickupSensor: $pickupSensor")
        pw.println("  isListening: $isListening")
        pw.println("  bouncerVisible: $bouncerVisible")
    }

    private fun updateListeningState() {
        if (pickupSensor == null) {
            return
        }
        val onKeyguard = keyguardUpdateMonitor.isKeyguardVisible &&
                !statusBarStateController.isDozing

        val isFaceEnabled = deviceEntryFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()
        val shouldListen = (onKeyguard || bouncerVisible) && isFaceEnabled
        if (shouldListen != isListening) {
            isListening = shouldListen

            if (shouldListen) {
                asyncSensorManager.requestTriggerSensor(listener, pickupSensor)
            } else {
                asyncSensorManager.cancelTriggerSensor(listener, pickupSensor)
            }
        }
    }
}
