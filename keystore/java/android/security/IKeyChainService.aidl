/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.pm.ParceledListSlice;

/**
 * Caller is required to ensure that {@link KeyStore#unlock
 * KeyStore.unlock} was successful.
 *
 * @hide
 */
interface IKeyChainService {
    // APIs used by KeyChain
    String requestPrivateKey(String alias);
    byte[] getCertificate(String alias);

    // APIs used by CertInstaller
    void installCaCertificate(in byte[] caCertificate);

    // APIs used by DevicePolicyManager
    boolean installKeyPair(in byte[] privateKey, in byte[] userCert, String alias);

    // APIs used by Settings
    boolean deleteCaCertificate(String alias);
    boolean reset();
    ParceledListSlice getUserCaAliases();
    ParceledListSlice getSystemCaAliases();
    boolean containsCaAlias(String alias);
    byte[] getEncodedCaCertificate(String alias, boolean includeDeletedSystem);
    List<String> getCaCertificateChainAliases(String rootAlias, boolean includeDeletedSystem);

    // APIs used by KeyChainActivity
    void setGrant(int uid, String alias, boolean value);
    boolean hasGrant(int uid, String alias);
}
