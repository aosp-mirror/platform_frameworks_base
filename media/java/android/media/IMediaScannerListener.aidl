/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import android.net.Uri;

/**
 * {@hide}
 */
oneway interface IMediaScannerListener
{
    /**
     * Called when a IMediaScannerService.scanFile() call has completed.
     * @param path the path to the file that has been scanned.
     * @param uri the Uri for the file if the scanning operation succeeded 
     * and the file was added to the media database, or null if scanning failed. 
     */
    void scanCompleted(String path, in Uri uri);
}
