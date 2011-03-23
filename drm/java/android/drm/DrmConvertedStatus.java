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
 * offset for appending the header and body signature to the converted data. An instance of this
 * class is returned by the {@link DrmManagerClient#convertData convertData()} and
 * {@link DrmManagerClient#closeConvertSession closeConvertSession()} methods. The offset is provided only when a
 * conversion session is closed by calling {@link DrmManagerClient#closeConvertSession closeConvertSession()}.
 *
 */
public class DrmConvertedStatus {
    // Should be in sync with DrmConvertedStatus.cpp
    public static final int STATUS_OK = 1;
    public static final int STATUS_INPUTDATA_ERROR = 2;
    public static final int STATUS_ERROR = 3;

    /** Status code for the conversion.*/
    public final int statusCode;
    /** Converted data.*/
    public final byte[] convertedData;
    /** Offset value for the body and header signature.*/
    public final int offset;

    /**
     * Creates a <code>DrmConvertedStatus</code> object with the specified parameters.
     *
     * @param _statusCode Conversion status.
     * @param _convertedData Converted data.
     * @param _offset Offset value for appending the header and body signature.
     */
    public DrmConvertedStatus(int _statusCode, byte[] _convertedData, int _offset) {
        statusCode = _statusCode;
        convertedData = _convertedData;
        offset = _offset;
    }
}

