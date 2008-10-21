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

package android.media;

import android.media.IMediaScannerListener;

/**
 * {@hide}
 */
interface IMediaScannerService
{
    /**
     * Requests the media scanner to scan a file.
     * @param path the path to the file to be scanned.
     * @param mimeType  an optional mimeType for the file.
     * If mimeType is null, then the mimeType will be inferred from the file extension.
     * @param listener an optional IMediaScannerListener. 
     * If specified, the caller will be notified when scanning is complete via the listener.
     */
    void requestScanFile(String path, String mimeType, in IMediaScannerListener listener);

    /**
     * Older API, left in for backward compatibility.
     * Requests the media scanner to scan a file.
     * @param path the path to the file to be scanned.
     * @param mimeType  an optional mimeType for the file.
     * If mimeType is null, then the mimeType will be inferred from the file extension.
     */
    void scanFile(String path, String mimeType);
}
