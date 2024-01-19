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

package com.android.server.grammaticalinflection;

import android.annotation.Nullable;
import android.content.res.Configuration;

/**
 * System-server internal interface to the {@link android.app.GrammaticalInflectionManager}.
 *
 * @hide Only for use within the system server.
 */
public abstract class GrammaticalInflectionManagerInternal {
    /**
     * Returns the app-gender to be backed up as a data-blob.
     */
    public abstract @Nullable byte[] getBackupPayload(int userId);

    /**
     * Restores the app-gender that were previously backed up.
     *
     * <p>This method will parse the input data blob and restore the gender for apps which are
     * present on the device. It will stage the gender data for the apps which are not installed
     * at the time this is called, to be referenced later when the app is installed.
     */
    public abstract void stageAndApplyRestoredPayload(byte[] payload, int userId);

    /**
     * Get the current system grammatical gender of privileged application.
     *
     * @return the value of grammatical gender
     *
     * @see Configuration#getGrammaticalGender
     */
    public abstract @Configuration.GrammaticalGender int getSystemGrammaticalGender(int userId);

    /**
     * Retrieve the system grammatical gender.
     *
     * @return the value of grammatical gender
     *
     */
    public abstract @Configuration.GrammaticalGender int retrieveSystemGrammaticalGender(
            Configuration configuration);

    /**
     * Whether the package can get the system grammatical gender or not.
     */
    public abstract boolean canGetSystemGrammaticalGender(int uid, String packageName);
}

