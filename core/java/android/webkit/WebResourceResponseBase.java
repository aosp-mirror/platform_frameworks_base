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

import java.io.InputStream;
import java.util.Map;

/**
 * Encapsulates a resource response received from the server.
 * This is an abstract class used by WebView callbacks.
 */
public abstract class WebResourceResponseBase {
    /**
     * Gets the resource response's MIME type.
     *
     * @return The resource response's MIME type
     */
    public abstract String getMimeType();

    /**
     * Gets the resource response's encoding.
     *
     * @return The resource response's encoding
     */
    public abstract String getEncoding();

    /**
     * Gets the resource response's status code.
     *
     * @return The resource response's status code.
     */
    public abstract int getStatusCode();

    /**
     * Gets the description of the resource response's status code.
     *
     * @return The description of the resource response's status code.
     */
    public abstract String getReasonPhrase();

    /**
     * Gets the headers for the resource response.
     *
     * @return The headers for the resource response.
     */
    public abstract Map<String, String> getResponseHeaders();

    /**
     * Gets the input stream that provides the resource response's data.
     *
     * @return The input stream that provides the resource response's data
     */
    public abstract InputStream getData();
}
