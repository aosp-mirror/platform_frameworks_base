/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bouncer.util

import android.app.ActivityManager
import android.content.res.Resources
import com.android.systemui.res.R
import java.io.File

private const val ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key"

/**
 * In general, we enable unlocking the insecure keyguard with the menu key. However, there are some
 * cases where we wish to disable it, notably when the menu button placement or technology is prone
 * to false positives.
 *
 * @return true if the menu key should be enabled
 */
fun Resources.shouldEnableMenuKey(): Boolean {
    val configDisabled = getBoolean(R.bool.config_disableMenuKeyInLockScreen)
    val isTestHarness = ActivityManager.isRunningInTestHarness()
    val fileOverride = File(ENABLE_MENU_KEY_FILE).exists()
    return !configDisabled || isTestHarness || fileOverride
}
