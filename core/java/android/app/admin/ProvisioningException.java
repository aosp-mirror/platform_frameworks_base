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

package android.app.admin;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.util.AndroidException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Thrown to indicate a failure during {@link DevicePolicyManager#provisionFullyManagedDevice} and
 * {@link DevicePolicyManager#createAndProvisionManagedProfile}.
 *
 * @hide
 *
 */
@SystemApi
public class ProvisioningException extends AndroidException {

    /**
     * Service-specific error code for {@link DevicePolicyManager#provisionFullyManagedDevice} and
     * {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates a generic failure.
     */
    public static final int ERROR_UNKNOWN = 0;

    /**
     * Service-specific error code for {@link DevicePolicyManager#provisionFullyManagedDevice} and
     * {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates the call to {@link DevicePolicyManager#checkProvisioningPrecondition} returned an
     * error code.
     */
    public static final int ERROR_PRE_CONDITION_FAILED = 1;

    /**
     * Service-specific error code for {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates that the profile creation failed.
     */
    public static final int ERROR_PROFILE_CREATION_FAILED = 2;

    /**
     * Service-specific error code for {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates the call to {@link PackageManager#installExistingPackageAsUser} has failed.
     */
    public static final int ERROR_ADMIN_PACKAGE_INSTALLATION_FAILED = 3;

    /**
     * Service-specific error code for {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates that setting the profile owner failed.
     */
    public static final int ERROR_SETTING_PROFILE_OWNER_FAILED = 4;

    /**
     * Service-specific error code for {@link DevicePolicyManager#createAndProvisionManagedProfile}:
     * Indicates that starting the newly created profile has failed.
     */
    public static final int ERROR_STARTING_PROFILE_FAILED = 5;

    /**
     * Service-specific error code for {@link DevicePolicyManager#provisionFullyManagedDevice}:
     * Indicates that removing the non required apps have failed.
     */
    public static final int ERROR_REMOVE_NON_REQUIRED_APPS_FAILED = 6;

    /**
     * Service-specific error code for {@link DevicePolicyManager#provisionFullyManagedDevice}:
     * Indicates that setting the device owner failed.
     */
    public static final int ERROR_SET_DEVICE_OWNER_FAILED = 7;

    /**
     * Service-specific error codes for {@link DevicePolicyManager#createAndProvisionManagedProfile}
     * and {@link DevicePolicyManager#provisionFullyManagedDevice} indicating all the errors
     * during provisioning.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_UNKNOWN, ERROR_PRE_CONDITION_FAILED,
            ERROR_PROFILE_CREATION_FAILED,
            ERROR_ADMIN_PACKAGE_INSTALLATION_FAILED,
            ERROR_SETTING_PROFILE_OWNER_FAILED,
            ERROR_STARTING_PROFILE_FAILED,
            ERROR_REMOVE_NON_REQUIRED_APPS_FAILED,
            ERROR_SET_DEVICE_OWNER_FAILED
    })
    public @interface ProvisioningError {}

    private final @ProvisioningError int mProvisioningError;

    /**
     * Constructs a {@link ProvisioningException}.
     *
     * @param cause the cause
     * @param provisioningError the error code
     */
    public ProvisioningException(@NonNull Exception cause,
            @ProvisioningError int provisioningError) {
        this(cause, provisioningError, /* errorMessage= */ null);
    }

    /**
     * Constructs a {@link ProvisioningException}.
     *
     * @param cause the cause
     * @param provisioningError the error code
     * @param errorMessage a {@code String} error message that give a more specific
     *                     description of the exception; can be {@code null}
     */
    public ProvisioningException(@NonNull Exception cause,
            @ProvisioningError int provisioningError,
            @Nullable String errorMessage) {
        super(errorMessage, cause);
        mProvisioningError = provisioningError;
    }

    /**
     * Returns the provisioning error specified at construction time.
     */
    public @ProvisioningError int getProvisioningError() {
        return mProvisioningError;
    }
}
