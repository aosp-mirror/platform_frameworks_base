/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.biometrics.BiometricSourceType
import android.os.Handler
import android.provider.Settings.System.SCREEN_BRIGHTNESS_FLOAT
import android.util.MathUtils
import android.view.View
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import com.android.internal.annotations.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SystemSettings
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.Float.max
import java.util.concurrent.TimeUnit

val DEFAULT_ANIMATION_DURATION = TimeUnit.SECONDS.toMillis(4)
val MAX_SCREEN_BRIGHTNESS = 100 // 0..100
val MAX_SCRIM_OPACTY = 50 // 0..100
val DEFAULT_USE_FACE_WALLPAPER = false

/**
 * This class is responsible for ramping up the display brightness (and white overlay) in order
 * to mitigate low light conditions when running face auth without an IR camera.
 */
@SysUISingleton
open class FaceAuthScreenBrightnessController(
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val resources: Resources,
    private val globalSettings: GlobalSettings,
    private val systemSettings: SystemSettings,
    private val mainHandler: Handler,
    private val dumpManager: DumpManager,
    private val enabled: Boolean
) : Dumpable {

    private var userDefinedBrightness: Float = 1f
    @VisibleForTesting
    var useFaceAuthWallpaper = globalSettings
            .getInt("sysui.use_face_auth_wallpaper", if (DEFAULT_USE_FACE_WALLPAPER) 1 else 0) == 1
    private val brightnessAnimationDuration = globalSettings
            .getLong("sysui.face_brightness_anim_duration", DEFAULT_ANIMATION_DURATION)
    private val maxScreenBrightness = globalSettings
            .getInt("sysui.face_max_brightness", MAX_SCREEN_BRIGHTNESS) / 100f
    private val maxScrimOpacity = globalSettings
            .getInt("sysui.face_max_scrim_opacity", MAX_SCRIM_OPACTY) / 100f
    private val keyguardUpdateCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onBiometricRunningStateChanged(
            running: Boolean,
            biometricSourceType: BiometricSourceType?
        ) {
            if (biometricSourceType != BiometricSourceType.FACE) {
                return
            }
            // TODO enable only when receiving a low-light error
            overridingBrightness = if (enabled) running else false
        }
    }
    private lateinit var whiteOverlay: View
    private var brightnessAnimator: ValueAnimator? = null
    private var overridingBrightness = false
    set(value) {
        if (field == value) {
            return
        }
        field = value
        brightnessAnimator?.cancel()

        if (!value) {
            notificationShadeWindowController.setFaceAuthDisplayBrightness(BRIGHTNESS_OVERRIDE_NONE)
            if (whiteOverlay.alpha > 0) {
                brightnessAnimator = createAnimator(whiteOverlay.alpha, 0f).apply {
                    duration = 200
                    addUpdateListener {
                        whiteOverlay.alpha = it.animatedValue as Float
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            whiteOverlay.visibility = View.INVISIBLE
                            brightnessAnimator = null
                        }
                    })
                    start()
                }
            }
            return
        }

        val targetBrightness = max(maxScreenBrightness, userDefinedBrightness)
        whiteOverlay.visibility = View.VISIBLE
        brightnessAnimator = createAnimator(0f, 1f).apply {
            duration = brightnessAnimationDuration
            addUpdateListener {
                val progress = it.animatedValue as Float
                val brightnessProgress = MathUtils.constrainedMap(
                        userDefinedBrightness, targetBrightness, 0f, 0.5f, progress)
                val scrimProgress = MathUtils.constrainedMap(
                        0f, maxScrimOpacity, 0.5f, 1f, progress)
                notificationShadeWindowController.setFaceAuthDisplayBrightness(brightnessProgress)
                whiteOverlay.alpha = scrimProgress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    brightnessAnimator = null
                }
            })
            start()
        }
    }

    @VisibleForTesting
    open fun createAnimator(start: Float, end: Float) = ValueAnimator.ofFloat(start, end)

    /**
     * Returns a bitmap that should be used by the lock screen as a wallpaper, if face auth requires
     * a secure wallpaper.
     */
    var faceAuthWallpaper: Bitmap? = null
    get() {
        val user = KeyguardUpdateMonitor.getCurrentUser()
        if (useFaceAuthWallpaper && keyguardUpdateMonitor.isFaceAuthEnabledForUser(user)) {
            val options = BitmapFactory.Options().apply {
                inScaled = false
            }
            return BitmapFactory.decodeResource(resources, R.drawable.face_auth_wallpaper, options)
        }
        return null
    }
    private set

    fun attach(overlayView: View) {
        whiteOverlay = overlayView
        whiteOverlay.focusable = FLAG_NOT_FOCUSABLE
        whiteOverlay.background = ColorDrawable(Color.WHITE)
        whiteOverlay.isEnabled = false
        whiteOverlay.alpha = 0f
        whiteOverlay.visibility = View.INVISIBLE

        dumpManager.registerDumpable(this.javaClass.name, this)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateCallback)
        systemSettings.registerContentObserver(SCREEN_BRIGHTNESS_FLOAT,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    userDefinedBrightness = systemSettings.getFloat(SCREEN_BRIGHTNESS_FLOAT)
                }
            })
        userDefinedBrightness = systemSettings.getFloat(SCREEN_BRIGHTNESS_FLOAT, 1f)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("overridingBrightness: $overridingBrightness")
            println("useFaceAuthWallpaper: $useFaceAuthWallpaper")
            println("brightnessAnimator: $brightnessAnimator")
            println("brightnessAnimationDuration: $brightnessAnimationDuration")
            println("maxScreenBrightness: $maxScreenBrightness")
            println("userDefinedBrightness: $userDefinedBrightness")
            println("enabled: $enabled")
        }
    }
}