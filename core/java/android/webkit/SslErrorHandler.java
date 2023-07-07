/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Represents a request for handling an SSL error.
 *
 * <p>A {@link WebView} creates an instance of this class. The instance is
 * passed to {@link WebViewClient#onReceivedSslError(WebView, SslErrorHandler,
 * SslError)}.
 *
 * <p>The host application must call {@link #cancel()} or, contrary to secure
 * web communication standards, {@link #proceed()} to provide the web view's
 * response to the request.
 */
public class SslErrorHandler extends Handler {

    /**
     * @hide Only for use by WebViewProvider implementations.
     */
    @SystemApi
    public SslErrorHandler() {}

    /**
     * Instructs the {@code WebView} that encountered the SSL certificate error
     * to ignore the error and continue communicating with the server.
     *
     * <p class="warning"><b>Warning:</b> When an SSL error occurs, the host
     * application should always call {@link #cancel()} rather than
     * {@code proceed()} because an invalid SSL certificate means the connection
     * is not secure.
     *
     * @see WebViewClient#onReceivedSslError(WebView, SslErrorHandler,
     * SslError)
     */
    public void proceed() {}

    /**
     * Instructs the {@code WebView} that encountered the SSL certificate error
     * to terminate communication with the server. Cancels the current server
     * request and all pending requests for the {@code WebView}.
     *
     * <p>The host application must call this method to prevent a resource from
     * loading when an SSL certificate is invalid.
     *
     * @see WebViewClient#onReceivedSslError(WebView, SslErrorHandler,
     * SslError)
     */
    public void cancel() {}
}
