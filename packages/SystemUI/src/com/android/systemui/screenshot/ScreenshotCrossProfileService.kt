/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * If a screenshot is saved to the work profile, any intents that grant access to the screenshot
 * must come from a service running as the work profile user. This service is meant to be started as
 * the desired user and just startActivity for the given intent.
 */
class ScreenshotCrossProfileService : Service() {

    private val mBinder: IBinder =
        object : ICrossProfileService.Stub() {
            override fun launchIntent(intent: Intent, bundle: Bundle) {
                startActivity(intent, bundle)
            }
        }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: $intent")
        return mBinder
    }

    companion object {
        const val TAG = "ScreenshotProxyService"
    }
}
