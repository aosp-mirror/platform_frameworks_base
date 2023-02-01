/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locales;

import android.annotation.Nullable;

/**
 * System-server internal interface to the {@link LocaleManagerService}.
 *
 * @hide Only for use within the system server.
 */
public abstract class LocaleManagerInternal {
    /**
     * Returns the app-specific locales to be backed up as a data-blob.
     */
    public abstract @Nullable byte[] getBackupPayload(int userId);

    /**
     * Restores the app-locales that were previously backed up.
     *
     * <p>This method will parse the input data blob and restore the locales for apps which are
     * present on the device. It will stage the locale data for the apps which are not installed
     * at the time this is called, to be referenced later when the app is installed.
     */
    public abstract void stageAndApplyRestoredPayload(byte[] payload, int userId);
}
