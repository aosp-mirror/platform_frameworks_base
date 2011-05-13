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

import android.os.Bundle;

/**
 * Caller is required to ensure that {@link KeyStore#unlock
 * KeyStore.unlock} was successful.
 *
 * @hide
 */
interface IKeyChainService {
    // APIs used by KeyChain
    byte[] getPrivate(String alias, String authToken);
    byte[] getCertificate(String alias, String authToken);
    byte[] getCaCertificate(String alias, String authToken);
    String findIssuer(in Bundle cert);

    // APIs used by CertInstaller
    void installCaCertificate(in byte[] caCertificate);

    // APIs used by Settings
    boolean reset();
}
