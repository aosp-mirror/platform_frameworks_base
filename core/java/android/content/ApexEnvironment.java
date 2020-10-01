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

package android.content;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Environment;
import android.os.UserHandle;

import java.io.File;
import java.util.Objects;

/**
 * Provides information about the environment for a particular APEX.
 *
 * @hide
 */
@SystemApi
@TestApi
public class ApexEnvironment {

    private static final String APEX_DATA = "apexdata";

    /**
     * Returns an ApexEnvironment instance for the APEX with the provided {@code apexModuleName}.
     *
     * <p>To preserve the safety and integrity of APEX modules, you must only obtain the
     * ApexEnvironment for your specific APEX, and you <em>must never</em> attempt to obtain an
     * ApexEnvironment for another APEX.  Any coordination between APEXs must be performed through
     * well-defined interfaces; attempting to directly read or write raw files belonging to another
     * APEX will violate the hermetic storage requirements placed upon each module.
     */
    @NonNull
    public static ApexEnvironment getApexEnvironment(@NonNull String apexModuleName) {
        Objects.requireNonNull(apexModuleName, "apexModuleName cannot be null");
        //TODO(b/141148175): Check that apexModuleName is an actual APEX name
        return new ApexEnvironment(apexModuleName);
    }

    private final String mApexModuleName;

    private ApexEnvironment(String apexModuleName) {
        mApexModuleName = apexModuleName;
    }

    /**
     * Returns the data directory for the APEX in device-encrypted, non-user-specific storage.
     *
     * <p>This directory is automatically created by the system for installed APEXes, and its
     * contents will be rolled back if the APEX is rolled back.
     */
    @NonNull
    public File getDeviceProtectedDataDir() {
        return Environment.buildPath(
                Environment.getDataMiscDirectory(), APEX_DATA, mApexModuleName);
    }

    /**
     * Returns the data directory for the APEX in device-encrypted, user-specific storage for the
     * specified {@code user}.
     *
     * <p>This directory is automatically created by the system for each user and for each installed
     * APEX, and its contents will be rolled back if the APEX is rolled back.
     */
    @NonNull
    public File getDeviceProtectedDataDirForUser(@NonNull UserHandle user) {
        return Environment.buildPath(
                Environment.getDataMiscDeDirectory(user.getIdentifier()), APEX_DATA,
                mApexModuleName);
    }

    /**
     * Returns the data directory for the APEX in credential-encrypted, user-specific storage for
     * the specified {@code user}.
     *
     * <p>This directory is automatically created by the system for each user and for each installed
     * APEX, and its contents will be rolled back if the APEX is rolled back.
     */
    @NonNull
    public File getCredentialProtectedDataDirForUser(@NonNull UserHandle user) {
        return Environment.buildPath(
                Environment.getDataMiscCeDirectory(user.getIdentifier()), APEX_DATA,
                mApexModuleName);
    }
}
