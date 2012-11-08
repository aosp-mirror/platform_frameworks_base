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
 * An entity class that wraps converted data, conversion status, and the
 * offset for appending the header and body signature to the converted data.
 * An instance of this class may be created two ways by the drm framework:
 * a) a call to {@link DrmManagerClient#convertData DrmManagerClient.convertData()} and
 * b) a call to {@link DrmManagerClient#closeConvertSession DrmManagerClient.closeConvertSession()}.
 * An valid offset value is provided only from a success call to
 * {@link DrmManagerClient#closeConvertSession DrmManagerClient.closeConvertSession()}.
 *
 */
public class DrmConvertedStatus {
    // The following status code constants must be in sync with
    // DrmConvertedStatus.cpp. Please also update isValidStatusCode()
    // when more status code constants are added.
    /**
     * Indicate the conversion status is successful.
     */
    public static final int STATUS_OK = 1;
    /**
     * Indicate a failed conversion status due to input data.
     */
    public static final int STATUS_INPUTDATA_ERROR = 2;
    /**
     * Indicate a general failed conversion status.
     */
    public static final int STATUS_ERROR = 3;

    /**
     * Status code for the conversion. Must be one of the defined status
     * constants above.
     */
    public final int statusCode;
    /**
     * Converted data. It is optional and thus can be null.
     */
    public final byte[] convertedData;
    /**
     * Offset value for the body and header signature.
     */
    public final int offset;

    /**
     * Creates a <code>DrmConvertedStatus</code> object with the specified parameters.
     *
     * @param statusCode Conversion status. Must be one of the status code constants
     * defined above.
     * @param convertedData Converted data. It can be null.
     * @param offset Offset value for appending the header and body signature.
     */
    public DrmConvertedStatus(int statusCode, byte[] convertedData, int offset) {
        if (!isValidStatusCode(statusCode)) {
            throw new IllegalArgumentException("Unsupported status code: " + statusCode);
        }

        this.statusCode = statusCode;
        this.convertedData = convertedData;
        this.offset = offset;
    }

    private boolean isValidStatusCode(int statusCode) {
        return statusCode == STATUS_OK ||
               statusCode == STATUS_INPUTDATA_ERROR ||
               statusCode == STATUS_ERROR;
    }
}

