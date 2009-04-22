/*
 * Copyright (C) 2009 The Android Open Source Project
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

/**
 * Functionality for manipulating the webstorage databases.
 */
public final class WebStorage {

    /**
     * Encapsulates a callback function to be executed when a new quota is made
     * available. We primarily want this to allow us to call back the sleeping
     * WebCore thread from outside the WebViewCore class (as the native call
     * is private). It is imperative that this the setDatabaseQuota method is
     * executed once a decision to either allow or deny new quota is made,
     * otherwise the WebCore thread will remain asleep.
     */
    public interface QuotaUpdater {
        public void updateQuota(long newQuota);
    };
}
