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

import android.os.Handler;

/**
 * HTTP authentication request that must be handled by the user interface.
 * WebView creates the object and hands it to the current {@link WebViewClient},
 * which must call either {@link #proceed(String, String)} or {@link #cancel()}.
 */
public class HttpAuthHandler extends Handler {

    /**
     * Package-private constructor needed for API compatibility.
     */
    HttpAuthHandler() {
    }

    /**
     * @return True if we can use user credentials on record
     * (ie, if we did not fail trying to use them last time)
     */
    public boolean useHttpAuthUsernamePassword() {
        return false;
    }

    /**
     * Cancel the authorization request.
     */
    public void cancel() {
    }

    /**
     * Proceed with the authorization with the given credentials.
     */
    public void proceed(String username, String password) {
    }

    /**
     * return true if the prompt dialog should be suppressed.
     * @hide
     */
    public boolean suppressDialog() {
        return false;
    }
}
