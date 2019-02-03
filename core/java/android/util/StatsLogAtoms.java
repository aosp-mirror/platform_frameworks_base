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

package android.util;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;

/**
 * Exposed stats logs atom ids.
 *
 * @hide
 */
@SystemApi
public class StatsLogAtoms {
    private StatsLogAtoms() {
    }

    /**
     * Information about a permission grant request
     *
     * Usage: {@code StatsLog.write(PERMISSION_GRANT_REQUEST_RESULT_REPORTED, long request_id,
     * int requesting_uid, String requesting_package_name, String permission_name,
     * boolean is_implicit, @PermissionGrantRequestResultReported_Result int result)}
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED;

    @Retention(SOURCE)
    @IntDef(prefix = "PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__",
            value = {PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_USER_FIXED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_POLICY_FIXED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_DENIED})
    public @interface PermissionGrantRequestResultReported_Result {}

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission request was ignored
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission request was ignored because it was user fixed
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_USER_FIXED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_USER_FIXED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission request was ignored because it was policy fixed
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_POLICY_FIXED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_POLICY_FIXED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission was granted by user action
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission was automatically granted
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission was denied by user action
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission was denied with prejudice by the user
     */
    public static final int
            PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE =
            StatsLogInternal
                    .PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE;

    /**
     * Possible value of {@link PermissionGrantRequestResultReported_Result}:
     * permission was automatically denied
     */
    public static final int PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_DENIED =
            StatsLogInternal.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_DENIED;
}
