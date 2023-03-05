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

package android.security.rkp;

import android.security.rkp.RemotelyProvisionedKey;

/**
 * Callback interface for receiving remotely provisioned keys from a
 * {@link IRegistration}.
 *
 * @hide
 */
oneway interface IGetKeyCallback {
    enum ErrorCode {
        /**
         * An unexpected error occurred and there's no standard way to describe it. See the
         * corresponding error string for more information.
         */
        ERROR_UNKNOWN = 1,

        /**
         * Device will not receive remotely provisioned keys because it's running vulnerable
         * code. The device needs to be updated to a fixed build to recover.
         */
        ERROR_REQUIRES_SECURITY_PATCH = 2,

        /**
         * Indicates that the attestation key pool has been exhausted, and the remote key
         * provisioning server cannot currently be reached. Clients should wait for the
         * device to have connectivity, then retry.
         */
        ERROR_PENDING_INTERNET_CONNECTIVITY = 3,

        /**
         * Indicates that this device will never be able to provision attestation keys using
         * the remote provsisioning server. This may be due to multiple causes, such as the
         * device is not registered with the remote provisioning backend or the device has
         * been permanently revoked. Clients who receive this error should not attempt to
         * retry key creation.
         */
        ERROR_PERMANENT = 5,
    }

    /**
     * Called in response to {@link IRegistration.getKey}, indicating
     * a remotely-provisioned key is available.
     *
     * @param key The key that was received from the remote provisioning service.
     */
    void onSuccess(in RemotelyProvisionedKey key);

    /**
     * Called when the key request has been successfully cancelled.
     * @see IRegistration.cancelGetKey
     */
    void onCancel();

    /**
     * Called when an error has occurred while trying to get a remotely provisioned key.
     *
     * @param error allows code to handle certain errors, if desired
     * @param description human-readable explanation of what failed, suitable for logging.
     */
    void onError(ErrorCode error, String description);
}

