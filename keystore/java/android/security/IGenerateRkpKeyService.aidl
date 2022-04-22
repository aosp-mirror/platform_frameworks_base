/**
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

package android.security;

/**
 * Interface to allow the framework to notify the RemoteProvisioner app when keys are empty. This
 * will be used if Keystore replies with an error code NO_KEYS_AVAILABLE in response to an
 * attestation request. The framework can then synchronously call generateKey() to get more
 * attestation keys generated and signed. Upon return, the caller can be certain an attestation key
 * is available.
 *
 * @hide
 */
interface IGenerateRkpKeyService {
    @JavaDerive(toString=true)
    @Backing(type="int")
    enum Status {
        /** No error(s) occurred */
        OK = 0,
        /** Unable to provision keys due to a lack of internet connectivity. */
        NO_NETWORK_CONNECTIVITY = 1,
        /** An error occurred while communicating with the RKP server. */
        NETWORK_COMMUNICATION_ERROR = 2,
        /** The given device was not registered with the RKP backend. */
        DEVICE_NOT_REGISTERED = 4,
        /** The RKP server returned an HTTP client error, indicating a misbehaving client. */
        HTTP_CLIENT_ERROR = 5,
        /** The RKP server returned an HTTP server error, indicating something went wrong on the server. */
        HTTP_SERVER_ERROR = 6,
        /** The RKP server returned an HTTP status that is unknown. This should never happen. */
        HTTP_UNKNOWN_ERROR = 7,
        /** An unexpected internal error occurred. This should never happen. */
        INTERNAL_ERROR = 8,
    }

    /**
     * Ping the provisioner service to let it know an app generated a key. This may or may not have
     * consumed a remotely provisioned attestation key, so the RemoteProvisioner app should check.
     */
    oneway void notifyKeyGenerated(in int securityLevel);

    /**
     * Ping the provisioner service to indicate there are no remaining attestation keys left.
     */
    Status generateKey(in int securityLevel);
}
