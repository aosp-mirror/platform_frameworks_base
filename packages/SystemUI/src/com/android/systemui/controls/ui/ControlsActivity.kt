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

package com.android.systemui.controls.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.RemoteException
import android.service.dreams.IDreamManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.android.systemui.res.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.management.ControlsAnimations
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Displays Device Controls inside an activity. This activity is available to be displayed over the
 * lockscreen if the user has allowed it via
 * [android.provider.Settings.Secure.LOCKSCREEN_SHOW_CONTROLS]. This activity will be
 * destroyed on SCREEN_OFF events, due to issues with occluded activities over lockscreen as well as
 * user expectations for the activity to not continue running.
 */
// Open for testing
open class ControlsActivity @Inject constructor(
    private val uiController: ControlsUiController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val dreamManager: IDreamManager,
    private val featureFlags: FeatureFlags,
    private val controlsSettingsDialogManager: ControlsSettingsDialogManager,
    private val keyguardStateController: KeyguardStateController
) : ComponentActivity() {

    private val lastConfiguration = Configuration()

    private lateinit var parent: ViewGroup
    private lateinit var broadcastReceiver: BroadcastReceiver
    private var mExitToDream: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastConfiguration.setTo(resources.configuration)
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

        setContentView(R.layout.controls_fullscreen)

        lifecycle.addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById(R.id.control_detail_root),
                window,
                intent,
                false
            )
        )

        requireViewById<ViewGroup>(R.id.control_detail_root).apply {
            setOnApplyWindowInsetsListener {
                v: View, insets: WindowInsets ->
                    v.apply {
                        val l = getPaddingLeft()
                        val t = getPaddingTop()
                        val r = getPaddingRight()
                        setPadding(l, t, r, insets.getInsets(Type.systemBars()).bottom)
                    }

                WindowInsets.CONSUMED
            }
        }

        initBroadcastReceiver()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val interestingFlags = ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
        if (lastConfiguration.diff(newConfig) and interestingFlags != 0 ) {
            uiController.onSizeChange()
        }
        lastConfiguration.setTo(newConfig)
    }

    override fun onStart() {
        super.onStart()

        parent = requireViewById(R.id.control_detail_root)
        parent.alpha = 0f
        if (!keyguardStateController.isUnlocked) {
            controlsSettingsDialogManager.maybeShowDialog(this) {
                uiController.show(parent, { finishOrReturnToDream() }, this)
            }
        } else {
            uiController.show(parent, { finishOrReturnToDream() }, this)
        }

        ControlsAnimations.enterAnimation(parent).start()
    }

    override fun onResume() {
        super.onResume()
        mExitToDream = intent.getBooleanExtra(ControlsUiController.EXIT_TO_DREAM, false)
    }

    fun finishOrReturnToDream() {
        if (mExitToDream) {
            try {
                mExitToDream = false
                dreamManager.dream()
                return
            } catch (e: RemoteException) {
                // Fall through
            }
        }
        finish()
    }

    override fun onBackPressed() {
        finishOrReturnToDream()
    }

    override fun onStop() {
        super.onStop()
        mExitToDream = false

        // parent is set in onStart, so the field is initialized when we get here
        uiController.hide(parent)
        controlsSettingsDialogManager.closeDialog()
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver()
    }

    protected open fun unregisterReceiver() {
        broadcastDispatcher.unregisterReceiver(broadcastReceiver)
    }

    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.getAction()
                if (action == Intent.ACTION_SCREEN_OFF ||
                    action == Intent.ACTION_DREAMING_STARTED) {
                    finish()
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_DREAMING_STARTED)
        broadcastDispatcher.registerReceiver(broadcastReceiver, filter)
    }
}
