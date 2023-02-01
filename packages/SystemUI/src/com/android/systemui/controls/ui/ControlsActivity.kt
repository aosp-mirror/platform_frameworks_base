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
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type
import androidx.activity.ComponentActivity
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.management.ControlsAnimations
import javax.inject.Inject

/**
 * Displays Device Controls inside an activity. This activity is available to be displayed over the
 * lockscreen if the user has allowed it via
 * [android.provider.Settings.Secure.LOCKSCREEN_SHOW_CONTROLS]. This activity will be
 * destroyed on SCREEN_OFF events, due to issues with occluded activities over lockscreen as well as
 * user expectations for the activity to not continue running.
 */
class ControlsActivity @Inject constructor(
    private val uiController: ControlsUiController,
    private val broadcastDispatcher: BroadcastDispatcher
) : ComponentActivity() {

    private lateinit var parent: ViewGroup
    private lateinit var broadcastReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.controls_fullscreen)

        getLifecycle().addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById<ViewGroup>(R.id.control_detail_root),
                window,
                intent
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

    override fun onStart() {
        super.onStart()

        parent = requireViewById<ViewGroup>(R.id.global_actions_controls)
        parent.alpha = 0f
        uiController.show(parent, { finish() }, this)

        ControlsAnimations.enterAnimation(parent).start()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onStop() {
        super.onStop()

        uiController.hide()
    }

    override fun onDestroy() {
        super.onDestroy()

        broadcastDispatcher.unregisterReceiver(broadcastReceiver)
    }

    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.getAction()
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    finish()
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        broadcastDispatcher.registerReceiver(broadcastReceiver, filter)
    }
}
