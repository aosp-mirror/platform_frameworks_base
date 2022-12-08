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
     * @param error A description of what failed, suitable for logging.
     */
    void onError(String error);
}

