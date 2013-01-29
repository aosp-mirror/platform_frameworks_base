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

import android.os.Handler;

/**
 * Represents a request for handling an SSL error. Instances of this class are
 * created by the WebView and passed to
 * {@link WebViewClient#onReceivedSslError}. The host application must call
 * either {@link #proceed} or {@link #cancel} to set the WebView's response
 * to the request.
 */
public class SslErrorHandler extends Handler {

    /**
     * @hide Only for use by WebViewProvider implementations.
     */
    public SslErrorHandler() {}

    /**
     * Proceed with the SSL certificate.
     */
    public void proceed() {}

    /**
     * Cancel this request and all pending requests for the WebView that had
     * the error.
     */
    public void cancel() {}
}
