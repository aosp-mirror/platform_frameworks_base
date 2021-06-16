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
package com.android.systemui.globalactions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

private const val TAG = "GlobalActionsInfo"

/** Maximum number of times to show change info message  */
private const val MAX_VIEW_COUNT = 3

/** Maximum number of buttons allowed in landscape before this panel does not fit */
private const val MAX_BUTTONS_LANDSCAPE = 4

private const val PREFERENCE = "global_actions_info_prefs"
private const val KEY_VIEW_COUNT = "view_count"

class GlobalActionsInfoProvider @Inject constructor(
    private val context: Context,
    private val walletClient: QuickAccessWalletClient,
    private val controlsController: ControlsController,
    private val activityStarter: ActivityStarter
) {

    private var pendingIntent: PendingIntent

    init {
        val url = context.resources.getString(R.string.global_actions_change_url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setData(Uri.parse(url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun addPanel(context: Context, parent: ViewGroup, nActions: Int, dismissParent: Runnable) {
        // This panel does not fit on landscape with two rows of buttons showing,
        // so skip adding the panel (and incrementing view counT) in that case
        val isLandscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape && nActions > MAX_BUTTONS_LANDSCAPE) {
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.global_actions_change_panel,
                parent, false)

        val walletTitle = walletClient.serviceLabel ?: context.getString(R.string.wallet_title)
        val message = view.findViewById<TextView>(R.id.global_actions_change_message)
        message?.setText(context.getString(R.string.global_actions_change_description, walletTitle))

        val button = view.findViewById<ImageView>(R.id.global_actions_change_button)
        button.setOnClickListener { _ ->
            dismissParent.run()
            activityStarter.postStartActivityDismissingKeyguard(pendingIntent)
        }
        parent.addView(view, 0) // Add to top
        incrementViewCount()
    }

    fun shouldShowMessage(): Boolean {
        // This is only relevant for some devices
        val isEligible = context.resources.getBoolean(
                R.bool.global_actions_show_change_info)
        if (!isEligible) {
            return false
        }

        val sharedPrefs = context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE)

        // Only show to users who previously had these items set up
        val viewCount = if (sharedPrefs.contains(KEY_VIEW_COUNT) || hadContent()) {
            sharedPrefs.getInt(KEY_VIEW_COUNT, 0)
        } else {
            -1
        }

        // Limit number of times this is displayed
        return viewCount > -1 && viewCount < MAX_VIEW_COUNT
    }

    private fun hadContent(): Boolean {
        // Check whether user would have seen content in the power menu that has now moved
        val hadControls = controlsController.getFavorites().size > 0
        val hadCards = walletClient.isWalletFeatureAvailable
        Log.d(TAG, "Previously had controls $hadControls, cards $hadCards")
        return hadControls || hadCards
    }

    private fun incrementViewCount() {
        val sharedPrefs = context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE)
        val count = sharedPrefs.getInt(KEY_VIEW_COUNT, 0)
        sharedPrefs.edit().putInt(KEY_VIEW_COUNT, count + 1).apply()
    }
}