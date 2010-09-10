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
 * This is an entity class which wraps the result of communication between device
 * and online DRM server.
 *
 * As a result of {@link DrmManagerClient#processDrmInfo(DrmInfo)} an instance of DrmInfoStatus
 * would be returned. This class holds {@link ProcessedData}, which could be used to instantiate
 * {@link DrmRights#DrmRights(ProcessedData, String)} in license acquisition.
 *
 */
public class DrmInfoStatus {
    // Should be in sync with DrmInfoStatus.cpp
    public static final int STATUS_OK = 1;
    public static final int STATUS_ERROR = 2;

    public final int statusCode;
    public final String mimeType;
    public final ProcessedData data;

    /**
     * constructor to create DrmInfoStatus object with given parameters
     *
     * @param _statusCode Status of the communication
     * @param _data The processed data
     * @param _mimeType MIME type
     */
    public DrmInfoStatus(int _statusCode, ProcessedData _data, String _mimeType) {
        statusCode = _statusCode;
        data = _data;
        mimeType = _mimeType;
    }
}

