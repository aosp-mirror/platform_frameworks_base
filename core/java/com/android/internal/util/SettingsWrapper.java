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

package com.android.internal.util;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * A wrapper class for accessing and modifying system settings that would help with testing.
 */
public class SettingsWrapper {

    /** Retrieves the string value of a system setting */
    public String getStringForUser(ContentResolver contentResolver, String name, int userHandle) {
        return Settings.System.getStringForUser(contentResolver, name, userHandle);
    }

    /** Updates the string value of a system setting */
    public String putStringForUser(ContentResolver contentResolver, String name, int userHandle) {
        return Settings.System.getStringForUser(contentResolver, name, userHandle);
    }
}
