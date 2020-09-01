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
 * An entity class that wraps the result of communication between a device
 * and an online DRM server. Specifically, when the
 * {@link DrmManagerClient#processDrmInfo DrmManagerClient.processDrmInfo()}
 * method is called, an instance of <code>DrmInfoStatus</code> is returned.
 *<p>
 * This class contains the {@link ProcessedData} object, which can be used
 * to instantiate a {@link DrmRights} object during license acquisition.
 *
 * @deprecated Please use {@link android.media.MediaDrm}
 */
@Deprecated
public class DrmInfoStatus {
    // The following status code constants must be in sync with DrmInfoStatus.cpp
    // Please update isValidStatusCode() if more status codes are added.
    /**
     * Indicate successful communication.
     */
    public static final int STATUS_OK = 1;

    /**
     * Indicate failed communication.
     */
    public static final int STATUS_ERROR = 2;

    /**
     * The status of the communication. Must be one of the defined status
     * constants above.
     */
    public final int statusCode;
    /**
     * The type of DRM information processed. Must be one of the valid type
     * constants defined in {@link DrmInfoRequest}.
     */
    public final int infoType;
    /**
     * The MIME type of the content. Must not be null or an empty string.
     */
    public final String mimeType;
    /**
     * The processed data. It is optional and thus could be null. When it
     * is null, it indicates that a particular call to
     * {@link DrmManagerClient#processDrmInfo DrmManagerClient.processDrmInfo()}
     * does not return any additional useful information except for the status code.
     */
    public final ProcessedData data;

    /**
     * Creates a <code>DrmInfoStatus</code> object with the specified parameters.
     *
     * @param statusCode The status of the communication. Must be one of the defined
     * status constants above.
     * @param infoType The type of the DRM information processed. Must be a valid
     * type for {@link DrmInfoRequest}.
     * @param data The processed data.
     * @param mimeType The MIME type.
     */
    public DrmInfoStatus(int statusCode, int infoType, ProcessedData data, String mimeType) {
        if (!DrmInfoRequest.isValidType(infoType)) {
            throw new IllegalArgumentException("infoType: " + infoType);
        }

        if (!isValidStatusCode(statusCode)) {
            throw new IllegalArgumentException("Unsupported status code: " + statusCode);
        }

        if (mimeType == null || mimeType == "") {
            throw new IllegalArgumentException("mimeType is null or an empty string");
        }

        this.statusCode = statusCode;
        this.infoType = infoType;
        this.data = data;
        this.mimeType = mimeType;
    }

    private boolean isValidStatusCode(int statusCode) {
        return statusCode == STATUS_OK || statusCode == STATUS_ERROR;
    }
}

