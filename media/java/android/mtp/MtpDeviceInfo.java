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

import android.annotation.Nullable;

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
    private int[] mOperationsSupported;
    private int[] mEventsSupported;

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

    /**
     * Returns operation code supported by the device.
     *
     * @return supported operation code. Can be null if device does not provide the property.
     * @see MtpConstants#OPERATION_GET_DEVICE_INFO
     * @see MtpConstants#OPERATION_OPEN_SESSION
     * @see MtpConstants#OPERATION_CLOSE_SESSION
     * @see MtpConstants#OPERATION_GET_STORAGE_I_DS
     * @see MtpConstants#OPERATION_GET_STORAGE_INFO
     * @see MtpConstants#OPERATION_GET_NUM_OBJECTS
     * @see MtpConstants#OPERATION_GET_OBJECT_HANDLES
     * @see MtpConstants#OPERATION_GET_OBJECT_INFO
     * @see MtpConstants#OPERATION_GET_OBJECT
     * @see MtpConstants#OPERATION_GET_THUMB
     * @see MtpConstants#OPERATION_DELETE_OBJECT
     * @see MtpConstants#OPERATION_SEND_OBJECT_INFO
     * @see MtpConstants#OPERATION_SEND_OBJECT
     * @see MtpConstants#OPERATION_INITIATE_CAPTURE
     * @see MtpConstants#OPERATION_FORMAT_STORE
     * @see MtpConstants#OPERATION_RESET_DEVICE
     * @see MtpConstants#OPERATION_SELF_TEST
     * @see MtpConstants#OPERATION_SET_OBJECT_PROTECTION
     * @see MtpConstants#OPERATION_POWER_DOWN
     * @see MtpConstants#OPERATION_GET_DEVICE_PROP_DESC
     * @see MtpConstants#OPERATION_GET_DEVICE_PROP_VALUE
     * @see MtpConstants#OPERATION_SET_DEVICE_PROP_VALUE
     * @see MtpConstants#OPERATION_RESET_DEVICE_PROP_VALUE
     * @see MtpConstants#OPERATION_TERMINATE_OPEN_CAPTURE
     * @see MtpConstants#OPERATION_MOVE_OBJECT
     * @see MtpConstants#OPERATION_COPY_OBJECT
     * @see MtpConstants#OPERATION_GET_PARTIAL_OBJECT
     * @see MtpConstants#OPERATION_INITIATE_OPEN_CAPTURE
     * @see MtpConstants#OPERATION_GET_OBJECT_PROPS_SUPPORTED
     * @see MtpConstants#OPERATION_GET_OBJECT_PROP_DESC
     * @see MtpConstants#OPERATION_GET_OBJECT_PROP_VALUE
     * @see MtpConstants#OPERATION_SET_OBJECT_PROP_VALUE
     * @see MtpConstants#OPERATION_GET_OBJECT_REFERENCES
     * @see MtpConstants#OPERATION_SET_OBJECT_REFERENCES
     * @see MtpConstants#OPERATION_SKIP
     */
    public final @Nullable int[] getOperationsSupported() {
        return mOperationsSupported;
    }

    /**
     * Returns event code supported by the device.
     *
     * @return supported event code. Can be null if device does not provide the property.
     * @see MtpConstants#EVENT_UNDEFINED
     * @see MtpConstants#EVENT_CANCEL_TRANSACTION
     * @see MtpConstants#EVENT_OBJECT_ADDED
     * @see MtpConstants#EVENT_OBJECT_REMOVED
     * @see MtpConstants#EVENT_STORE_ADDED
     * @see MtpConstants#EVENT_STORE_REMOVED
     * @see MtpConstants#EVENT_DEVICE_PROP_CHANGED
     * @see MtpConstants#EVENT_OBJECT_INFO_CHANGED
     * @see MtpConstants#EVENT_DEVICE_INFO_CHANGED
     * @see MtpConstants#EVENT_REQUEST_OBJECT_TRANSFER
     * @see MtpConstants#EVENT_STORE_FULL
     * @see MtpConstants#EVENT_DEVICE_RESET
     * @see MtpConstants#EVENT_STORAGE_INFO_CHANGED
     * @see MtpConstants#EVENT_CAPTURE_COMPLETE
     * @see MtpConstants#EVENT_UNREPORTED_STATUS
     * @see MtpConstants#EVENT_OBJECT_PROP_CHANGED
     * @see MtpConstants#EVENT_OBJECT_PROP_DESC_CHANGED
     * @see MtpConstants#EVENT_OBJECT_REFERENCES_CHANGED
     */
    public final @Nullable int[] getEventsSupported() {
        return mEventsSupported;
    }
}
