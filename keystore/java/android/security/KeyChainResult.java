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

import android.content.Intent;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * The KeyChainResult is the complex result value from {@link
 * KeyChain#get}. The caller should first inspect {@link #getIntent}
 * to determine if the user needs to grant the application access to
 * the protected contents. If {@code getIntent} returns null, access
 * has been granted and the methods {@link #getPrivateKey} and {@link
 * #getCertificate} can be used to access the credentials.
 *
 * @hide
 */
public final class KeyChainResult {

    private final Intent intent;
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    KeyChainResult(Intent intent) {
        this(intent, null, null);
    }

    KeyChainResult(PrivateKey privateKey, X509Certificate certificate) {
        this(null, privateKey, certificate);
    }

    private KeyChainResult(Intent intent, PrivateKey privateKey, X509Certificate certificate) {
        this.intent = intent;
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public Intent getIntent() {
        return intent;
    }

    public PrivateKey getPrivateKey() {
        checkIntent();
        return privateKey;
    }

    public X509Certificate getCertificate() {
        checkIntent();
        return certificate;
    }

    private void checkIntent() {
        if (intent != null) {
            throw new IllegalStateException("non-null Intent, check getIntent()");
        }
    }

}
