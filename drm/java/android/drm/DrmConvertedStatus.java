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
 * This is an entity class which wraps the status of the conversion, the converted
 * data/checksum data and the offset. Offset is going to be used in the case of close
 * session where the agent will inform where the header and body signature should be added
 *
 * As a result of {@link DrmManagerClient#convertData(int, byte [])} and
 * {@link DrmManagerClient#closeConvertSession(int)} an instance of DrmConvertedStatus
 * would be returned.
 *
 */
public class DrmConvertedStatus {
    // Should be in sync with DrmConvertedStatus.cpp
    public static final int STATUS_OK = 1;
    public static final int STATUS_INPUTDATA_ERROR = 2;
    public static final int STATUS_ERROR = 3;

    public final int statusCode;
    public final byte[] convertedData;
    public final int offset;

    /**
     * constructor to create DrmConvertedStatus object with given parameters
     *
     * @param _statusCode Status of the conversion
     * @param _convertedData Converted data/checksum data
     * @param _offset Offset value
     */
    public DrmConvertedStatus(int _statusCode, byte[] _convertedData, int _offset) {
        statusCode = _statusCode;
        convertedData = _convertedData;
        offset = _offset;
    }
}

