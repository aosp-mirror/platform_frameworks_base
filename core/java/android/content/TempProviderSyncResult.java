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

package android.content;

/**
 * Used to hold data returned from a given phase of a TempProviderSync.
 * @hide
 */
public class TempProviderSyncResult {
    /**
     * An interface to a temporary content provider that contains
     * the result of updates that were sent to the server. This
     * provider must be merged into the permanent content provider.
     * This may be null, which indicates that there is nothing to
     * merge back into the content provider.
     */
    public SyncableContentProvider tempContentProvider;

    public TempProviderSyncResult() {
        tempContentProvider = null;
    }
}
