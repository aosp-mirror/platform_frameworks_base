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

package android.mtp;

/**
 * This class encapsulates information about an MTP device.
 * This corresponds to the DeviceInfo Dataset described in
 * section 5.1.1 of the MTP specification.
 */
public class MtpDeviceInfo {

    private String mManufacturer;
    private String mModel;
    private String mVersion;
    private String mSerialNumber;

    // only instantiated via JNI
    private MtpDeviceInfo() {
    }

    /**
     * Returns the manufacturer's name for the MTP device
     *
     * @return the manufacturer name
     */
    public final String getManufacturer() {
        return mManufacturer;
    }

    /**
     * Returns the model name for the MTP device
     *
     * @return the model name
     */
    public final String getModel() {
        return mModel;
    }

    /**
     * Returns the version string the MTP device
     *
     * @return the device version
     */
    public final String getVersion() {
        return mVersion;
    }

    /**
     * Returns the unique serial number for the MTP device
     *
     * @return the serial number
     */
    public final String getSerialNumber() {
        return mSerialNumber;
    }
}