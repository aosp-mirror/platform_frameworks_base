/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import android.content.ComponentName;

public class ActivityOptions {
    public static final String EXTRA_STARVE_UI_THREAD = "StarveUiThread";
    public static final ComponentName SEAMLESS_ACTIVITY_COMPONENT_NAME =
            new ComponentName("com.android.server.wm.flicker.testapp",
                    "com.android.server.wm.flicker.testapp.SeamlessRotationActivity");
}
