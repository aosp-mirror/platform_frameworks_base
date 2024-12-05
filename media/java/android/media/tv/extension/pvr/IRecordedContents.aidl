/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.pvr;

import android.media.tv.extension.pvr.IDeleteRecordedContentsCallback;
import android.media.tv.extension.pvr.IGetInfoRecordedContentsCallback;


/**
 * @hide
 */
interface IRecordedContents {
    // Delete recorded contents by URIs
    // using callback to notify the result or any errors during the deletion process.
    void deleteRecordedContents(in String[] contentUri,
        in IDeleteRecordedContentsCallback callback);
    // Get the channel lock status for recorded content identified by the URI provided in sync way.
    int getRecordedContentsLockInfoSync(String contentUri);
    // Get the channel lock status for recorded content identified by the URI provided in async way.
    void getRecordedContentsLockInfoAsync(String contentUri,
        in IGetInfoRecordedContentsCallback callback);
}
