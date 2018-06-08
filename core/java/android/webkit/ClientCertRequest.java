/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.webkit;

import android.annotation.Nullable;

import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * ClientCertRequest: The user receives an instance of this class as
 * a parameter of {@link WebViewClient#onReceivedClientCertRequest}.
 * The request includes the parameters to choose the client certificate,
 * such as the host name and the port number requesting the cert, the acceptable
 * key types and the principals.
 *
 * The user should call one of the class methods to indicate how to deal
 * with the client certificate request. All methods should be called on
 * UI thread.
 *
 * WebView caches the {@link #proceed} and {@link #cancel} responses in memory
 * and uses them to handle future client certificate requests for the same
 * host/port pair. The user can clear the cached data using
 * {@link WebView#clearClientCertPreferences}.
 *
 */
public abstract class ClientCertRequest {

    public ClientCertRequest() { }

    /**
     * Returns the acceptable types of asymmetric keys.
     */
    @Nullable
    public abstract String[] getKeyTypes();

    /**
     * Returns the acceptable certificate issuers for the certificate
     *            matching the private key.
     */
    @Nullable
    public abstract Principal[] getPrincipals();

    /**
     * Returns the host name of the server requesting the certificate.
     */
    public abstract String getHost();

    /**
     * Returns the port number of the server requesting the certificate.
     */
    public abstract int getPort();

    /**
     * Proceed with the specified private key and client certificate chain.
     * Remember the user's positive choice and use it for future requests.
     */
    public abstract void proceed(PrivateKey privateKey, X509Certificate[] chain);

    /**
     * Ignore the request for now. Do not remember user's choice.
     */
    public abstract void ignore();

    /**
     * Cancel this request. Remember the user's choice and use it for
     * future requests.
     */
    public abstract void cancel();
}
