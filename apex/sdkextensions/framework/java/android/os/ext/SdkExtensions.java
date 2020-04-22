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

package android.os.ext;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Build.VERSION_CODES;
import android.os.SystemProperties;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Methods for interacting with the extension SDK.
 *
 * This class provides information about the extension SDK version present
 * on this device. Use the {@link #getExtensionVersion(int) getExtension} to
 * query for the extension version for the given SDK version.

 * @hide
 */
@SystemApi
public class SdkExtensions {

    private static final int R_EXTENSION_INT;
    static {
        R_EXTENSION_INT = SystemProperties.getInt("build.version.extensions.r", 0);
    }

    /**
     * Values suitable as parameters for {@link #getExtensionVersion(int)}.
     * @hide
     */
    @IntDef(value = { VERSION_CODES.R })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SdkVersion {}

    private SdkExtensions() { }

    /**
     * Return the version of the extension to the given SDK.
     *
     * @param sdk the SDK version to get the extension version of.
     * @see SdkVersion
     * @throws IllegalArgumentException if sdk is not an sdk version with extensions
     */
    public static int getExtensionVersion(@SdkVersion int sdk) {
        if (sdk < VERSION_CODES.R) {
            throw new IllegalArgumentException(String.valueOf(sdk) + " does not have extensions");
        }

        if (sdk == VERSION_CODES.R) {
            return R_EXTENSION_INT;
        }
        return 0;
    }

}
