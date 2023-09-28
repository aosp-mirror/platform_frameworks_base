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

package com.android.credentialmanager.activity

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A custom StartIntentSenderForResult contract implementation that attaches an [ActivityOptions]
 * that opts in for background activity launch.
 */
class StartBalIntentSenderForResultContract :
    ActivityResultContract<IntentSenderRequest, ActivityResult>() {
    override fun createIntent(context: Context, input: IntentSenderRequest): Intent {
        val activityOptionBundle =
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            ).toBundle()
        return Intent(
            ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST
        ).putExtra(
            ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
            activityOptionBundle
        ).putExtra(
            ActivityResultContracts.StartIntentSenderForResult.EXTRA_INTENT_SENDER_REQUEST,
            input
        )
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?
    ): ActivityResult = ActivityResult(resultCode, intent)
}