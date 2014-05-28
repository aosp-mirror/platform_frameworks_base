/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

/**
 * Various helper methods to simulate older versions of platform.
 */
public class Config {

    public static boolean showOnScreenNavBar(int platformVersion) {
        // return true if ICS or later.
        return platformVersion >= 14 || platformVersion == 0;
    }

    public static int getStatusBarColor(int platformVersion) {
        // return white for froyo and earlier; black otherwise.
        return platformVersion >= 9 || platformVersion == 0 ? 0xFF000000 : 0xFFFFFFFF;
    }
}
