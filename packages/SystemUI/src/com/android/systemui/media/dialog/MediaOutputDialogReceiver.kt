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

package com.android.systemui.media.dialog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.Log
import com.android.settingslib.media.MediaOutputConstants
import javax.inject.Inject

private const val TAG = "MediaOutputDlgReceiver"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * BroadcastReceiver for handling media output intent
 */
class MediaOutputDialogReceiver @Inject constructor(
    private val mediaOutputDialogFactory: MediaOutputDialogFactory,
    private val mediaOutputBroadcastDialogFactory: MediaOutputBroadcastDialogFactory
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            TextUtils.equals(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG, intent.action) -> {
                val packageName: String? =
                    intent.getStringExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME)
                launchMediaOutputDialogIfPossible(packageName)
            }
            TextUtils.equals(
                MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG,
                intent.action) -> {
                val packageName: String? =
                    intent.getStringExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME)
                launchMediaOutputBroadcastDialogIfPossible(packageName)
            }
        }
    }

    private fun launchMediaOutputDialogIfPossible(packageName: String?) {
        if (!packageName.isNullOrEmpty()) {
            mediaOutputDialogFactory.create(packageName, false)
        } else if (DEBUG) {
            Log.e(TAG, "Unable to launch media output dialog. Package name is empty.")
        }
    }

    private fun launchMediaOutputBroadcastDialogIfPossible(packageName: String?) {
        if (!packageName.isNullOrEmpty()) {
            mediaOutputBroadcastDialogFactory.create(packageName, false)
        } else if (DEBUG) {
            Log.e(TAG, "Unable to launch media output broadcast dialog. Package name is empty.")
        }
    }
}
