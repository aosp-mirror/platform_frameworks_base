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

package com.android.wm.shell.flicker

import android.content.ComponentName

const val IME_WINDOW_NAME = "InputMethod"
const val PIP_WINDOW_NAME = "PipMenuActivity"

// Test App
const val TEST_APP_PACKAGE_NAME = "com.android.wm.shell.flicker.testapp"
// Test App > Pip Activity
val TEST_APP_PIP_ACTIVITY_COMPONENT_NAME: ComponentName = ComponentName.createRelative(
        TEST_APP_PACKAGE_NAME, ".PipActivity")
const val TEST_APP_PIP_ACTIVITY_LABEL = "PipApp"
const val TEST_APP_PIP_ACTIVITY_WINDOW_NAME = "PipActivity"
// Test App > Ime Activity
val TEST_APP_IME_ACTIVITY_COMPONENT_NAME: ComponentName = ComponentName.createRelative(
        TEST_APP_PACKAGE_NAME, ".ImeActivity")
const val TEST_APP_IME_ACTIVITY_LABEL = "ImeApp"

const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"