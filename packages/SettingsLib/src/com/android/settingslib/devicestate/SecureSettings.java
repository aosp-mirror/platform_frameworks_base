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

package com.android.settingslib.devicestate;

import android.database.ContentObserver;

/** Minimal wrapper interface around {@link android.provider.Settings.Secure} for easier testing. */
interface SecureSettings {

    void putStringForUser(String name, String value, int userHandle);

    String getStringForUser(String name, int userHandle);

    void registerContentObserver(String name, boolean notifyForDescendants,
            ContentObserver settingsObserver, int userHandle);
}
