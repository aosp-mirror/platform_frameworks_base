/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers;

import android.app.Instrumentation;

import com.android.server.wm.flicker.StandardAppHelper;

public abstract class FlickerAppHelper extends StandardAppHelper {

    static int sFindTimeout = 10000;
    static String sFlickerPackage = "com.android.server.wm.flicker.testapp";

    public FlickerAppHelper(Instrumentation instr, String launcherName) {
        super(instr, sFlickerPackage, launcherName);
    }
}
