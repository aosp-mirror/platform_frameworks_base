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

import android.Manifest
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
import android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.SensorPropertiesInternal
import android.os.UserManager
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.biometrics.shared.model.PromptKind

object Utils {
    /** Base set of layout flags for fingerprint overlay widgets. */
    const val FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS =
        (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

    @JvmStatic
    fun dpToPixels(context: Context, dp: Float): Float {
        val density = context.resources.displayMetrics.densityDpi.toFloat()
        return dp * (density / DisplayMetrics.DENSITY_DEFAULT)
    }

    /**
     * Note: Talkback 14.0 has new rate-limitation design to reduce frequency of
     * TYPE_WINDOW_CONTENT_CHANGED events to once every 30 seconds. (context: b/281765653#comment18)
     * Using {@link View#announceForAccessibility} instead as workaround when sending events
     * exceeding this frequency is required.
     */
    @JvmStatic
    fun notifyAccessibilityContentChanged(am: AccessibilityManager, view: ViewGroup) {
        if (!am.isEnabled) {
            return
        }
        val event = AccessibilityEvent.obtain()
        event.eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        view.sendAccessibilityEventUnchecked(event)
        view.notifySubtreeAccessibilityStateChanged(
            view,
            view,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        )
    }

    @JvmStatic
    fun isDeviceCredentialAllowed(promptInfo: PromptInfo): Boolean =
        (promptInfo.authenticators and Authenticators.DEVICE_CREDENTIAL) != 0

    @JvmStatic
    fun isBiometricAllowed(promptInfo: PromptInfo): Boolean =
        (promptInfo.authenticators and Authenticators.BIOMETRIC_WEAK) != 0

    @JvmStatic
    fun getCredentialType(utils: LockPatternUtils, userId: Int): PromptKind =
        when (utils.getKeyguardStoredPasswordQuality(userId)) {
            PASSWORD_QUALITY_SOMETHING -> PromptKind.Pattern
            PASSWORD_QUALITY_NUMERIC,
            PASSWORD_QUALITY_NUMERIC_COMPLEX -> PromptKind.Pin
            PASSWORD_QUALITY_ALPHABETIC,
            PASSWORD_QUALITY_ALPHANUMERIC,
            PASSWORD_QUALITY_COMPLEX,
            PASSWORD_QUALITY_MANAGED -> PromptKind.Password
            else -> PromptKind.Password
        }

    @JvmStatic
    fun isManagedProfile(context: Context, userId: Int): Boolean =
        context.getSystemService(UserManager::class.java)?.isManagedProfile(userId) ?: false

    @JvmStatic
    fun <T : SensorPropertiesInternal> findFirstSensorProperties(
        properties: List<T>?,
        sensorIds: IntArray
    ): T? = properties?.firstOrNull { sensorIds.contains(it.sensorId) }

    @JvmStatic
    fun isSystem(context: Context, clientPackage: String?): Boolean {
        val hasPermission =
            (context.checkCallingOrSelfPermission(Manifest.permission.USE_BIOMETRIC_INTERNAL) ==
                PackageManager.PERMISSION_GRANTED)
        return hasPermission && "android" == clientPackage
    }

    @JvmStatic
    fun getNavbarInsets(context: Context): Insets {
        val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
        val windowMetrics: WindowMetrics? = windowManager?.maximumWindowMetrics
        return windowMetrics?.windowInsets?.getInsets(WindowInsets.Type.navigationBars())
            ?: Insets.NONE
    }

    /** Converts `drawable` to a [Bitmap]. */
    @JvmStatic
    fun Drawable?.toBitmap(): Bitmap? {
        if (this == null) {
            return null
        }
        if (this is BitmapDrawable) {
            return bitmap
        }
        val bitmap: Bitmap =
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                // Single color bitmap will be created of 1x1 pixel
            } else {
                Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            }
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}
