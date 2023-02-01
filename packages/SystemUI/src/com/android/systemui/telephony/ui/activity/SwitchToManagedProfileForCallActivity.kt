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
import android.util.Log
import android.view.WindowManager
import com.android.internal.app.AlertActivity
import com.android.systemui.R

/** Dialog shown to the user to switch to managed profile for making a call using work SIM. */
class SwitchToManagedProfileForCallActivity : AlertActivity(), DialogInterface.OnClickListener {
    private lateinit var phoneNumber: Uri
    private var managedProfileUserId = UserHandle.USER_NULL

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(
            WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
        )
        super.onCreate(savedInstanceState)

        phoneNumber = intent.getData()
        managedProfileUserId =
            intent.getIntExtra(
                "android.telecom.extra.MANAGED_PROFILE_USER_ID",
                UserHandle.USER_NULL
            )

        mAlertParams.apply {
            mTitle = getString(R.string.call_from_work_profile_title)
            mMessage = getString(R.string.call_from_work_profile_text)
            mPositiveButtonText = getString(R.string.call_from_work_profile_action)
            mNegativeButtonText = getString(R.string.call_from_work_profile_close)
            mPositiveButtonListener = this@SwitchToManagedProfileForCallActivity
            mNegativeButtonListener = this@SwitchToManagedProfileForCallActivity
        }
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
                Intent(Intent.ACTION_DIAL, phoneNumber),
                ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle(),
                UserHandle.of(managedProfileUserId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity", e)
        }
    }

    companion object {
        private const val TAG = "SwitchToManagedProfileForCallActivity"
    }
}
