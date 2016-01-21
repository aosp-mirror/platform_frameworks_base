/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.net.Uri;

import java.security.KeyPair;
import java.security.spec.AlgorithmParameterSpec;

/**
 * Enables the token binding procotol, and provides access to the keys. See
 * https://tools.ietf.org/html/draft-ietf-tokbind-protocol-03
 *
 * All methods are required to be called on the UI thread where WebView is
 * attached to the View hierarchy.
 * @hide
 */
@SystemApi
public abstract class TokenBindingService {

    public static final String KEY_ALGORITHM_RSA2048_PKCS_1_5 = "RSA2048_PKCS_1.5";
    public static final String KEY_ALGORITHM_RSA2048_PSS = "RSA2048PSS";
    public static final String KEY_ALGORITHM_ECDSAP256 = "ECDSAP256";

    /**
     * Returns the default TokenBinding service instance. At present there is
     * only one token binding service instance for all WebView instances,
     * however this restriction may be relaxed in the future.
     *
     * @return The default TokenBindingService instance.
     */
    public static TokenBindingService getInstance() {
        return WebViewFactory.getProvider().getTokenBindingService();
    }

    /**
     * Enables the token binding protocol. The token binding protocol
     * has to be enabled before creating any WebViews.
     *
     * @throws IllegalStateException if a WebView was already created.
     */
    public abstract void enableTokenBinding();

    /**
     * Retrieves the key pair for a given origin from the internal
     * TokenBinding key store asynchronously.
     * Will create a key pair if one does not exist.
     *
     * @param origin The origin for the server.
     * @param algorithm The algorithm for generating the token binding key.
     * @param callback The callback that will be called when key is available.
     *        Cannot be null.
     */
    public abstract void getKey(Uri origin,
                                String algorithm,
                                ValueCallback<KeyPair> callback);
    /**
     * Deletes specified key (for use when associated cookie is cleared).
     *
     * @param origin The origin of the server.
     * @param callback The callback that will be called when key is deleted. The
     *        callback parameter (Boolean) will indicate if operation is
     *        successful or if failed. The callback can be null.
     */
    public abstract void deleteKey(Uri origin,
                                   ValueCallback<Boolean> callback);

     /**
      * Deletes all the keys (for use when cookies are cleared).
      *
      * @param callback The callback that will be called when keys are deleted.
      *        The callback parameter (Boolean) will indicate if operation is
      *        successful or if failed. The callback can be null.
      */
    public abstract void deleteAllKeys(ValueCallback<Boolean> callback);
}
