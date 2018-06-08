/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.timezone;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Callback interface for receiving information about an async time zone operation.
 * The methods will be called on your application's main thread.
 *
 * @hide
 */
public abstract class Callback {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SUCCESS", "ERROR_" }, value = {
            SUCCESS,
            ERROR_UNKNOWN_FAILURE,
            ERROR_INSTALL_BAD_DISTRO_STRUCTURE,
            ERROR_INSTALL_BAD_DISTRO_FORMAT_VERSION,
            ERROR_INSTALL_RULES_TOO_OLD,
            ERROR_INSTALL_VALIDATION_ERROR
    })
    public @interface AsyncResultCode {}

    /**
     * Indicates that an operation succeeded.
     */
    public static final int SUCCESS = 0;

    /**
     * Indicates an install / uninstall did not fully succeed for an unknown reason.
     */
    public static final int ERROR_UNKNOWN_FAILURE = 1;

    /**
     * Indicates an install failed because of a structural issue with the provided distro,
     * e.g. it wasn't in the right format or the contents were structured incorrectly.
     */
    public static final int ERROR_INSTALL_BAD_DISTRO_STRUCTURE = 2;

    /**
     * Indicates an install failed because of a versioning issue with the provided distro,
     * e.g. it was created for a different version of Android.
     */
    public static final int ERROR_INSTALL_BAD_DISTRO_FORMAT_VERSION = 3;

    /**
     * Indicates an install failed because the rules provided are too old for the device,
     * e.g. the Android device shipped with a newer rules version.
     */
    public static final int ERROR_INSTALL_RULES_TOO_OLD = 4;

    /**
     * Indicates an install failed because the distro contents failed validation.
     */
    public static final int ERROR_INSTALL_VALIDATION_ERROR = 5;

    /**
     * Reports the result of an async time zone operation.
     */
    public abstract void onFinished(@AsyncResultCode int status);
}
