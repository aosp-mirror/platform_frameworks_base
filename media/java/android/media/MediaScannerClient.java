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

/**
 * {@hide}
 */
public interface MediaScannerClient
{    
    public void scanFile(String path, long lastModified, long fileSize,
            boolean isDirectory, boolean noMedia);

    /**
     * Called by native code to return metadata extracted from media files.
     */
    public void handleStringTag(String name, String value);

    /**
     * Called by native code to return mime type extracted from DRM content.
     */
    public void setMimeType(String mimeType);
}
