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

import android.hardware.Sensor
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.Assert
import com.android.systemui.util.sensors.AsyncSensorManager
import java.io.FileDescriptor
import java.io.PrintWriter

class KeyguardLiftController constructor(
    private val statusBarStateController: StatusBarStateController,
    private val asyncSensorManager: AsyncSensorManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    dumpManager: DumpManager
) : StatusBarStateController.StateListener, Dumpable, KeyguardUpdateMonitorCallback() {

    private val pickupSensor = asyncSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE)
    private var isListening = false
    private var bouncerVisible = false

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        statusBarStateController.addCallback(this)
        keyguardUpdateMonitor.registerCallback(this)
        updateListeningState()
    }

    private val listener: TriggerEventListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Assert.isMainThread()
            // Not listening anymore since trigger events unregister themselves
            isListening = false
            updateListeningState()
            keyguardUpdateMonitor.requestFaceAuth(true)
        }
    }

    override fun onDozingChanged(isDozing: Boolean) {
        updateListeningState()
    }

    override fun onKeyguardBouncerChanged(bouncer: Boolean) {
        bouncerVisible = bouncer
        updateListeningState()
    }

    override fun onKeyguardVisibilityChanged(showing: Boolean) {
        updateListeningState()
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
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

        val userId = KeyguardUpdateMonitor.getCurrentUser()
        val isFaceEnabled = keyguardUpdateMonitor.isFaceAuthEnabledForUser(userId)
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
