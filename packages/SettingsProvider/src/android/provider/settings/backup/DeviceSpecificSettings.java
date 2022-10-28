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

package android.provider.settings.backup;

import android.provider.Settings;

/** Device specific settings list */
public class DeviceSpecificSettings {
    /**
     * The settings values which should only be restored if the target device is the
     * same as the source device
     *
     * NOTE: Settings are backed up and restored in the order they appear
     *       in this array. If you have one setting depending on another,
     *       make sure that they are ordered appropriately.
     *
     * @hide
     */
    public static final String[] DEVICE_SPECIFIC_SETTINGS_TO_BACKUP = {
            Settings.Secure.DISPLAY_DENSITY_FORCED,
            Settings.Secure.DEVICE_STATE_ROTATION_LOCK
    };
}
