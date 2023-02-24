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

import android.security.rkp.IGetKeyCallback;
import android.security.rkp.IStoreUpgradedKeyCallback;

/**
 * This interface is associated with the registration of an
 * IRemotelyProvisionedComponent. Each component has a unique set of keys
 * and certificates that are provisioned to the device for attestation. An
 * IRegistration binder is created by calling
 * {@link IRemoteProvisioning#getRegistration()}.
 *
 * This interface is used to query for available keys and certificates for the
 * registered component.
 *
 * @hide
 */
oneway interface IRegistration {
    /**
     * Fetch a remotely provisioned key for the given keyId. Keys are unique
     * per caller/keyId/registration tuple. This ensures that no two
     * applications are able to correlate keys to uniquely identify a
     * device/user. Callers receive their key via {@code callback}.
     *
     * If a key is available, this call immediately invokes {@code callback}.
     *
     * If no keys are immediately available, then this function contacts the
     * remote provisioning server to provision a key. After provisioning is
     * completed, the key is passed to {@code callback}.
     *
     * @param keyId This is a client-chosen key identifier, used to
     * differentiate between keys for varying client-specific use-cases. For
     * example, keystore2 passes the UID of the applications that call it as
     * the keyId value here, so that each of keystore2's clients gets a unique
     * key.
     * @param callback Receives the result of the call. A callback must only
     * be used with one {@code getKey} call at a time.
     */
    void getKey(int keyId, IGetKeyCallback callback);

    /**
     * Cancel an active request for a remotely provisioned key, as initiated via
     * {@link getKey}. Upon cancellation, {@code callback.onCancel} will be invoked.
     */
    void cancelGetKey(IGetKeyCallback callback);

    /**
     * Replace an obsolete key blob with an upgraded key blob.
     * In certain cases, such as security patch level upgrade, keys become "old".
     * In these cases, the component which supports operations with the remotely
     * provisioned key blobs must support upgrading the blobs to make them "new"
     * and usable on the updated system.
     *
     * For an example of a remotely provisioned component that has an upgrade
     * mechanism, see the documentation for IKeyMintDevice.upgradeKey.
     *
     * Once a key has been upgraded, the IRegistration where the key is stored
     * needs to be told about the new blob. After calling storeUpgradedKeyAsync,
     * getKey will return the new key blob instead of the old one.
     *
     * Note that this function does NOT extend the lifetime of key blobs. The
     * certificate for the key is unchanged, and the key will still expire at
     * the same time it would have if storeUpgradedKeyAsync had never been called.
     *
     * @param oldKeyBlob The old key blob to be replaced by {@code newKeyBlob}.
     * @param newKeyblob The new blob to replace {@code oldKeyBlob}.
     * @param callback Receives the result of the call. A callback must only
     * be used with one {@code storeUpgradedKeyAsync} call at a time.
     */
    void storeUpgradedKeyAsync(
            in byte[] oldKeyBlob, in byte[] newKeyBlob, IStoreUpgradedKeyCallback callback);
}
