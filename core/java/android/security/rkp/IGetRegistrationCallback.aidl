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

import android.security.rkp.IRegistration;

/**
 * Callback interface for receiving a remote provisioning registration.
 * {@link IRegistration}.
 *
 * @hide
 */
oneway interface IGetRegistrationCallback {
    /**
     * Called in response to {@link IRemoteProvisioning.getRegistration}.
     *
     * @param registration an IRegistration that is used to fetch remotely
     * provisioned keys for the given IRemotelyProvisionedComponent.
     */
    void onSuccess(in IRegistration registration);

    /**
     * Called when the get registration request has been successfully cancelled.
     * @see IRemoteProvisioning.cancelGetRegistration
     */
    void onCancel();

    /**
     * Called when an error has occurred while trying to get a registration.
     *
     * @param error A description of what failed, suitable for logging.
     */
    void onError(String error);
}

