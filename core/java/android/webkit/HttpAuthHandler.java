/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.os.Handler;

/**
 * Represents a request for HTTP authentication. Instances of this class are
 * created by the WebView and passed to
 * {@link WebViewClient#onReceivedHttpAuthRequest}. The host application must
 * call either {@link #proceed} or {@link #cancel} to set the WebView's
 * response to the request.
 */
public class HttpAuthHandler extends Handler {

    /**
     * @hide Only for use by WebViewProvider implementations.
     */
    @SystemApi
    public HttpAuthHandler() {
    }

    /**
     * Gets whether the credentials stored for the current host (i.e. the host
     * for which {@link WebViewClient#onReceivedHttpAuthRequest} was called)
     * are suitable for use. Credentials are not suitable if they have
     * previously been rejected by the server for the current request.
     *
     * @return whether the credentials are suitable for use
     * @see WebView#getHttpAuthUsernamePassword
     */
    public boolean useHttpAuthUsernamePassword() {
        return false;
    }

    /**
     * Instructs the WebView to cancel the authentication request.
     */
    public void cancel() {
    }

    /**
     * Instructs the WebView to proceed with the authentication with the given
     * credentials. Credentials for use with this method can be retrieved from
     * the WebView's store using {@link WebView#getHttpAuthUsernamePassword}.
     */
    public void proceed(String username, String password) {
    }
}
