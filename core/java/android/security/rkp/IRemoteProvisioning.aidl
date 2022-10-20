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
import android.security.rkp.IGetRegistrationCallback;

/**
 * {@link IRemoteProvisioning} is the interface provided to use the remote key
 * provisioning functionality from the Remote Key Provisioning Daemon (RKPD).
 * This would be the first service that RKPD clients would interact with. The
 * intent is for the clients to get the {@link IRegistration} object from this
 * interface and use it for actual remote provisioning work.
 *
 * @hide
 */
oneway interface IRemoteProvisioning {
    /**
     * Takes a remotely provisioned component service name and gets a
     * registration bound to that service and the caller's UID.
     *
     * @param irpcName The name of the {@code IRemotelyProvisionedComponent}
     * for which remotely provisioned keys should be managed.
     * @param callback Receives the result of the call. A callback must only
     * be used with one {@code getRegistration} call at a time.
     *
     * Notes:
     * - This function will attempt to get the service named by irpcName. This
     *   implies that a lazy/dynamic aidl service will be instantiated, and this
     *   function blocks until the service is up. Upon return, any binder tokens
     *   are dropped, allowing the lazy/dynamic service to shutdown.
     * - The created registration object is unique per caller. If two different
     *   UIDs call getRegistration with the same irpcName, they will receive
     *   different registrations. This prevents two different applications from
     *   being able to see the same keys.
     * - This function is idempotent per calling UID. Additional calls to
     *   getRegistration with the same parameters, from the same caller, will have
     *   no side effects.
     * - A callback may only be associated with one getRegistration call at a time.
     *   If the callback is used multiple times, this API will return an error.
     *
     * @see IRegistration#getKey()
     * @see IRemotelyProvisionedComponent
     *
     */
    void getRegistration(String irpcName, IGetRegistrationCallback callback);

    /**
     * Cancel any active {@link getRegistration} call associated with the given
     * callback. If no getRegistration call is currently active, this function is
     * a noop.
     */
    void cancelGetRegistration(IGetRegistrationCallback callback);
}
