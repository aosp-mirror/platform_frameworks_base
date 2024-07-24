/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.telephony.ui.activity

import android.app.ActivityOptions
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManager
import com.android.internal.app.AlertActivity
import com.android.systemui.res.R
import javax.inject.Inject

/** Dialog shown to the user to switch to managed profile for making a call using work SIM. */
class SwitchToManagedProfileForCallActivity
@Inject
constructor(
    private val telecomManager: TelecomManager?,
) : AlertActivity(), DialogInterface.OnClickListener {
    private lateinit var phoneNumber: Uri
    private lateinit var positiveActionIntent: Intent
    private var managedProfileUserId = UserHandle.USER_NULL

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(
            WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
        )
        super.onCreate(savedInstanceState)
        phoneNumber = intent.data ?: Uri.EMPTY
        managedProfileUserId =
            intent.getIntExtra(
                "android.telecom.extra.MANAGED_PROFILE_USER_ID",
                UserHandle.USER_NULL
            )

        mAlertParams.apply {
            mTitle = getString(R.string.call_from_work_profile_title)
            mMessage = getString(R.string.call_from_work_profile_text)
            mNegativeButtonText = getString(R.string.call_from_work_profile_close)
            mPositiveButtonListener = this@SwitchToManagedProfileForCallActivity
            mNegativeButtonListener = this@SwitchToManagedProfileForCallActivity
        }

        // A dialer app may not be available in the managed profile. We try to handle that
        // gracefully by redirecting the user to the app market to install a suitable app.
        val defaultDialerPackageName: String? =
            telecomManager?.getDefaultDialerPackage(UserHandle.of(managedProfileUserId))

        val (intent, positiveButtonText) =
            defaultDialerPackageName?.let {
                Intent(
                    Intent.ACTION_CALL,
                    phoneNumber,
                ) to R.string.call_from_work_profile_action
            }
                ?: Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(APP_STORE_DIALER_QUERY),
                ) to R.string.install_dialer_on_work_profile_action

        positiveActionIntent = intent
        mAlertParams.apply { mPositiveButtonText = getString(positiveButtonText) }

        setupAlert()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        if (which == BUTTON_POSITIVE) {
            switchToManagedProfile()
        }
        finish()
    }

    private fun switchToManagedProfile() {
        try {
            applicationContext.startActivityAsUser(
                positiveActionIntent,
                ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle(),
                UserHandle.of(managedProfileUserId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity", e)
        }
    }

    companion object {
        private const val TAG = "SwitchToManagedProfileForCallActivity"
        private const val APP_STORE_DIALER_QUERY = "market://search?q=dialer"
    }
}
