/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.drm;

/**
 * An entity class that wraps the result of communication between a device and an online DRM
 * server. Specifically, when the {@link DrmManagerClient#processDrmInfo processDrmInfo()} method
 * is called, an instance of <code>DrmInfoStatus</code> is returned.
 *<p>
 * This class contains the {@link ProcessedData} object, which can be used to instantiate a
 * {@link DrmRights} object during license acquisition.
 *
 */
public class DrmInfoStatus {
    // Should be in sync with DrmInfoStatus.cpp
    public static final int STATUS_OK = 1;
    public static final int STATUS_ERROR = 2;

    /**
     * The status of the communication.
     */
    public final int statusCode;
    /**
     * The type of DRM information processed.
     */
    public final int infoType;
    /**
     * The MIME type of the content.
     */
    public final String mimeType;
    /**
     * The processed data.
     */
    public final ProcessedData data;

    /**
     * Creates a <code>DrmInfoStatus</code> object with the specified parameters.
     *
     * @param _statusCode The status of the communication.
     * @param _infoType The type of the DRM information processed.
     * @param _data The processed data.
     * @param _mimeType The MIME type.
     */
    public DrmInfoStatus(int _statusCode, int _infoType, ProcessedData _data, String _mimeType) {
        statusCode = _statusCode;
        infoType = _infoType;
        data = _data;
        mimeType = _mimeType;
    }
}

