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
 * limitations under the License.
 */
package com.android.systemui.biometrics

import android.content.Context
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import com.android.systemui.R

private const val TAG = "AuthBiometricFingerprintView"

/** Fingerprint only view for BiometricPrompt.  */
open class AuthBiometricFingerprintView(
    context: Context,
    attrs: AttributeSet? = null
) : AuthBiometricView(context, attrs) {
    /** If this view is for a UDFPS sensor.  */
    var isUdfps = false
        private set

    private var udfpsAdapter: UdfpsDialogMeasureAdapter? = null

    /** Set the [sensorProps] of this sensor so the view can be customized prior to layout. */
    fun setSensorProperties(sensorProps: FingerprintSensorPropertiesInternal) {
        isUdfps = sensorProps.isAnyUdfpsType
        udfpsAdapter = if (isUdfps) UdfpsDialogMeasureAdapter(this, sensorProps) else null
    }

    override fun onMeasureInternal(width: Int, height: Int): AuthDialog.LayoutParams {
        val layoutParams = super.onMeasureInternal(width, height)
        return udfpsAdapter?.onMeasureInternal(width, height, layoutParams) ?: layoutParams
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val adapter = udfpsAdapter
        if (adapter != null) {
            // Move the UDFPS icon and indicator text if necessary. This probably only needs to happen
            // for devices where the UDFPS sensor is too low.
            // TODO(b/201510778): Update this logic to support cases where the sensor or text overlap
            //  the button bar area.
            val bottomSpacerHeight = adapter.bottomSpacerHeight
            Log.w(TAG, "bottomSpacerHeight: $bottomSpacerHeight")
            if (bottomSpacerHeight < 0) {
                val iconFrame = findViewById<FrameLayout>(R.id.biometric_icon_frame)!!
                iconFrame.translationY = -bottomSpacerHeight.toFloat()
                val indicator = findViewById<TextView>(R.id.indicator)!!
                indicator.translationY = -bottomSpacerHeight.toFloat()
            }
        }
    }

    override fun getDelayAfterAuthenticatedDurationMs() = 0

    override fun getStateForAfterError() = STATE_AUTHENTICATING

    override fun handleResetAfterError() = showTouchSensorString()

    override fun handleResetAfterHelp() = showTouchSensorString()

    override fun supportsSmallDialog() = false

    override fun createIconController(): AuthIconController =
        AuthBiometricFingerprintIconController(mContext, mIconView)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showTouchSensorString()
    }

    private fun showTouchSensorString() {
        mIndicatorView.setText(R.string.fingerprint_dialog_touch_sensor)
        mIndicatorView.setTextColor(mTextColorHint)
    }
}
