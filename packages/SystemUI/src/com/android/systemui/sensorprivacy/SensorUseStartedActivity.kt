/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.sensorprivacy

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.DialogInterface.BUTTON_NEGATIVE
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.Intent
import android.content.Intent.EXTRA_PACKAGE_NAME
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.EXTRA_ALL_SENSORS
import android.hardware.SensorPrivacyManager.EXTRA_SENSOR
import android.hardware.SensorPrivacyManager.Sources.DIALOG
import android.os.Bundle
import android.os.Handler
import android.window.OnBackInvokedDispatcher
import androidx.annotation.OpenForTesting
import com.android.internal.util.FrameworkStatsLog.PRIVACY_TOGGLE_DIALOG_INTERACTION
import com.android.internal.util.FrameworkStatsLog.PRIVACY_TOGGLE_DIALOG_INTERACTION__ACTION__CANCEL
import com.android.internal.util.FrameworkStatsLog.PRIVACY_TOGGLE_DIALOG_INTERACTION__ACTION__ENABLE
import com.android.internal.util.FrameworkStatsLog.write
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Dialog to be shown on top of apps that are attempting to use a sensor (e.g. microphone) which is
 * currently in "sensor privacy mode", aka. muted.
 *
 * <p>The dialog is started for the user the app is running for which might be a secondary users.
 */
@OpenForTesting
open class SensorUseStartedActivity @Inject constructor(
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardDismissUtil: KeyguardDismissUtil,
    @Background private val bgHandler: Handler
) : Activity(), DialogInterface.OnClickListener, DialogInterface.OnDismissListener {

    companion object {
        private val LOG_TAG = SensorUseStartedActivity::class.java.simpleName

        private const val SUPPRESS_REMINDERS_REMOVAL_DELAY_MILLIS = 2000L
        private const val UNLOCK_DELAY_MILLIS = 200L

        internal const val CAMERA = SensorPrivacyManager.Sensors.CAMERA
        internal const val MICROPHONE = SensorPrivacyManager.Sensors.MICROPHONE
        internal const val ALL_SENSORS = Integer.MAX_VALUE
    }

    private var sensor = -1
    private lateinit var sensorUsePackageName: String
    private var unsuppressImmediately = false

    private var sensorPrivacyListener: IndividualSensorPrivacyController.Callback? = null

    private var mDialog: AlertDialog? = null
    private val mBackCallback = this::onBackInvoked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)

        setFinishOnTouchOutside(false)

        setResult(RESULT_CANCELED)

        sensorUsePackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return

        if (intent.getBooleanExtra(EXTRA_ALL_SENSORS, false)) {
            sensor = ALL_SENSORS
            val callback = IndividualSensorPrivacyController.Callback { _, _ ->
                if (!sensorPrivacyController.isSensorBlocked(MICROPHONE) &&
                        !sensorPrivacyController.isSensorBlocked(CAMERA)) {
                    finish()
                }
            }
            sensorPrivacyListener = callback
            sensorPrivacyController.addCallback(callback)
            if (!sensorPrivacyController.isSensorBlocked(MICROPHONE) &&
                    !sensorPrivacyController.isSensorBlocked(CAMERA)) {
                finish()
                return
            }
        } else {
            sensor = intent.getIntExtra(EXTRA_SENSOR, -1).also {
                if (it == -1) {
                    finish()
                    return
                }
            }
            val callback = IndividualSensorPrivacyController.Callback {
                whichSensor: Int, isBlocked: Boolean ->
                if (whichSensor == sensor && !isBlocked) {
                    finish()
                }
            }
            sensorPrivacyListener = callback
            sensorPrivacyController.addCallback(callback)

            if (!sensorPrivacyController.isSensorBlocked(sensor)) {
                finish()
                return
            }
        }

        mDialog = SensorUseDialog(this, sensor, this, this)
        mDialog!!.show()

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback)
    }

    override fun onStart() {
        super.onStart()

        setSuppressed(true)
        unsuppressImmediately = false
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            BUTTON_POSITIVE -> {
                if (sensorPrivacyController.requiresAuthentication() &&
                        keyguardStateController.isMethodSecure &&
                        keyguardStateController.isShowing) {
                    keyguardDismissUtil.executeWhenUnlocked({
                        bgHandler.postDelayed({
                            disableSensorPrivacy()
                            write(PRIVACY_TOGGLE_DIALOG_INTERACTION,
                                    PRIVACY_TOGGLE_DIALOG_INTERACTION__ACTION__ENABLE,
                                    sensorUsePackageName)
                        }, UNLOCK_DELAY_MILLIS)

                        false
                    }, false, true)
                } else {
                    disableSensorPrivacy()
                    write(PRIVACY_TOGGLE_DIALOG_INTERACTION,
                            PRIVACY_TOGGLE_DIALOG_INTERACTION__ACTION__ENABLE,
                            sensorUsePackageName)
                }
            }
            BUTTON_NEGATIVE -> {
                unsuppressImmediately = false
                write(PRIVACY_TOGGLE_DIALOG_INTERACTION,
                        PRIVACY_TOGGLE_DIALOG_INTERACTION__ACTION__CANCEL,
                        sensorUsePackageName)
            }
        }

        finish()
    }

    override fun onStop() {
        super.onStop()

        if (unsuppressImmediately) {
            setSuppressed(false)
        } else {
            bgHandler.postDelayed({
                setSuppressed(false)
            }, SUPPRESS_REMINDERS_REMOVAL_DELAY_MILLIS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mDialog?.dismiss()
        sensorPrivacyListener?.also { sensorPrivacyController.removeCallback(it) }
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(mBackCallback)
    }

    override fun onBackPressed() {
        onBackInvoked()
    }

    fun onBackInvoked() {
        // do not allow backing out
    }

    override fun onNewIntent(intent: Intent?) {
        setIntent(intent)
        recreate()
    }

    private fun disableSensorPrivacy() {
        if (sensor == ALL_SENSORS) {
            sensorPrivacyController.setSensorBlocked(DIALOG, MICROPHONE, false)
            sensorPrivacyController.setSensorBlocked(DIALOG, CAMERA, false)
        } else {
            sensorPrivacyController.setSensorBlocked(DIALOG, sensor, false)
        }
        unsuppressImmediately = true
        setResult(RESULT_OK)
    }

    private fun setSuppressed(suppressed: Boolean) {
        if (sensor == ALL_SENSORS) {
            sensorPrivacyController
                    .suppressSensorPrivacyReminders(MICROPHONE, suppressed)
            sensorPrivacyController
                    .suppressSensorPrivacyReminders(CAMERA, suppressed)
        } else {
            sensorPrivacyController
                    .suppressSensorPrivacyReminders(sensor, suppressed)
        }
    }

    override fun onDismiss(dialog: DialogInterface?) {
        if (!isChangingConfigurations) {
            finish()
        }
    }
}
