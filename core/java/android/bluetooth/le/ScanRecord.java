/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.annotation.Nullable;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents a scan record from Bluetooth LE scan.
 */
public final class ScanRecord {

    private static final String TAG = "ScanRecord";

    // The following data type values are assigned by Bluetooth SIG.
    // For more details refer to Bluetooth 4.1 specification, Volume 3, Part C, Section 18.
    private static final int DATA_TYPE_FLAGS = 0x01;
    private static final int DATA_TYPE_SERVICE_UUIDS_16_BIT_PARTIAL = 0x02;
    private static final int DATA_TYPE_SERVICE_UUIDS_16_BIT_COMPLETE = 0x03;
    private static final int DATA_TYPE_SERVICE_UUIDS_32_BIT_PARTIAL = 0x04;
    private static final int DATA_TYPE_SERVICE_UUIDS_32_BIT_COMPLETE = 0x05;
    private static final int DATA_TYPE_SERVICE_UUIDS_128_BIT_PARTIAL = 0x06;
    private static final int DATA_TYPE_SERVICE_UUIDS_128_BIT_COMPLETE = 0x07;
    private static final int DATA_TYPE_LOCAL_NAME_SHORT = 0x08;
    private static final int DATA_TYPE_LOCAL_NAME_COMPLETE = 0x09;
    private static final int DATA_TYPE_TX_POWER_LEVEL = 0x0A;
    private static final int DATA_TYPE_SERVICE_DATA = 0x16;
    private static final int DATA_TYPE_MANUFACTURER_SPECIFIC_DATA = 0xFF;

    // Flags of the advertising data.
    private final int mAdvertiseFlags;

    @Nullable
    private final List<ParcelUuid> mServiceUuids;

    private final int mManufacturerId;
    @Nullable
    private final byte[] mManufacturerSpecificData;

    @Nullable
    private final ParcelUuid mServiceDataUuid;
    @Nullable
    private final byte[] mServiceData;

    // Transmission power level(in dB).
    private final int mTxPowerLevel;

    // Local name of the Bluetooth LE device.
    private final String mLocalName;

    /**
     * Returns the advertising flags indicating the discoverable mode and capability of the device.
     * Returns -1 if the flag field is not set.
     */
    public int getAdvertiseFlags() {
        return mAdvertiseFlags;
    }

    /**
     * Returns a list of service uuids within the advertisement that are used to identify the
     * bluetooth gatt services.
     */
    public List<ParcelUuid> getServiceUuids() {
        return mServiceUuids;
    }

    /**
     * Returns the manufacturer identifier, which is a non-negative number assigned by Bluetooth
     * SIG.
     */
    public int getManufacturerId() {
        return mManufacturerId;
    }

    /**
     * Returns the manufacturer specific data which is the content of manufacturer specific data
     * field. The first 2 bytes of the data contain the company id.
     */
    public byte[] getManufacturerSpecificData() {
        return mManufacturerSpecificData;
    }

    /**
     * Returns a 16 bit uuid of the service that the service data is associated with.
     */
    public ParcelUuid getServiceDataUuid() {
        return mServiceDataUuid;
    }

    /**
     * Returns service data. The first two bytes should be a 16 bit service uuid associated with the
     * service data.
     */
    public byte[] getServiceData() {
        return mServiceData;
    }

    /**
     * Returns the transmission power level of the packet in dBm. Returns {@link Integer#MIN_VALUE}
     * if the field is not set. This value can be used to calculate the path loss of a received
     * packet using the following equation:
     * <p>
     * <code>pathloss = txPowerLevel - rssi</code>
     */
    public int getTxPowerLevel() {
        return mTxPowerLevel;
    }

    /**
     * Returns the local name of the BLE device. The is a UTF-8 encoded string.
     */
    @Nullable
    public String getLocalName() {
        return mLocalName;
    }

    private ScanRecord(List<ParcelUuid> serviceUuids,
            ParcelUuid serviceDataUuid, byte[] serviceData,
            int manufacturerId,
            byte[] manufacturerSpecificData, int advertiseFlags, int txPowerLevel,
            String localName) {
        mServiceUuids = serviceUuids;
        mManufacturerId = manufacturerId;
        mManufacturerSpecificData = manufacturerSpecificData;
        mServiceDataUuid = serviceDataUuid;
        mServiceData = serviceData;
        mLocalName = localName;
        mAdvertiseFlags = advertiseFlags;
        mTxPowerLevel = txPowerLevel;
    }

    @Override
    public String toString() {
        return "ScanRecord [mAdvertiseFlags=" + mAdvertiseFlags + ", mServiceUuids=" + mServiceUuids
                + ", mManufacturerId=" + mManufacturerId + ", mManufacturerSpecificData="
                + Arrays.toString(mManufacturerSpecificData) + ", mServiceDataUuid="
                + mServiceDataUuid + ", mServiceData=" + Arrays.toString(mServiceData)
                + ", mTxPowerLevel=" + mTxPowerLevel + ", mLocalName=" + mLocalName + "]";
    }

    /**
     * Parse scan record bytes to {@link ScanRecord}.
     * <p>
     * The format is defined in Bluetooth 4.1 specification, Volume 3, Part C, Section 11 and 18.
     * <p>
     * All numerical multi-byte entities and values shall use little-endian <strong>byte</strong>
     * order.
     *
     * @param scanRecord The scan record of Bluetooth LE advertisement and/or scan response.
     */
    public static ScanRecord parseFromBytes(byte[] scanRecord) {
        if (scanRecord == null) {
            return null;
        }

        int currentPos = 0;
        int advertiseFlag = -1;
        List<ParcelUuid> serviceUuids = new ArrayList<ParcelUuid>();
        String localName = null;
        int txPowerLevel = Integer.MIN_VALUE;
        ParcelUuid serviceDataUuid = null;
        byte[] serviceData = null;
        int manufacturerId = -1;
        byte[] manufacturerSpecificData = null;

        try {
            while (currentPos < scanRecord.length) {
                // length is unsigned int.
                int length = scanRecord[currentPos++] & 0xFF;
                if (length == 0) {
                    break;
                }
                // Note the length includes the length of the field type itself.
                int dataLength = length - 1;
                // fieldType is unsigned int.
                int fieldType = scanRecord[currentPos++] & 0xFF;
                switch (fieldType) {
                    case DATA_TYPE_FLAGS:
                        advertiseFlag = scanRecord[currentPos] & 0xFF;
                        break;
                    case DATA_TYPE_SERVICE_UUIDS_16_BIT_PARTIAL:
                    case DATA_TYPE_SERVICE_UUIDS_16_BIT_COMPLETE:
                        parseServiceUuid(scanRecord, currentPos,
                                dataLength, BluetoothUuid.UUID_BYTES_16_BIT, serviceUuids);
                        break;
                    case DATA_TYPE_SERVICE_UUIDS_32_BIT_PARTIAL:
                    case DATA_TYPE_SERVICE_UUIDS_32_BIT_COMPLETE:
                        parseServiceUuid(scanRecord, currentPos, dataLength,
                                BluetoothUuid.UUID_BYTES_32_BIT, serviceUuids);
                        break;
                    case DATA_TYPE_SERVICE_UUIDS_128_BIT_PARTIAL:
                    case DATA_TYPE_SERVICE_UUIDS_128_BIT_COMPLETE:
                        parseServiceUuid(scanRecord, currentPos, dataLength,
                                BluetoothUuid.UUID_BYTES_128_BIT, serviceUuids);
                        break;
                    case DATA_TYPE_LOCAL_NAME_SHORT:
                    case DATA_TYPE_LOCAL_NAME_COMPLETE:
                        localName = new String(
                                extractBytes(scanRecord, currentPos, dataLength));
                        break;
                    case DATA_TYPE_TX_POWER_LEVEL:
                        txPowerLevel = scanRecord[currentPos];
                        break;
                    case DATA_TYPE_SERVICE_DATA:
                        serviceData = extractBytes(scanRecord, currentPos, dataLength);
                        // The first two bytes of the service data are service data uuid.
                        int serviceUuidLength = BluetoothUuid.UUID_BYTES_16_BIT;
                        byte[] serviceDataUuidBytes = extractBytes(scanRecord, currentPos,
                                serviceUuidLength);
                        serviceDataUuid = BluetoothUuid.parseUuidFrom(serviceDataUuidBytes);
                        break;
                    case DATA_TYPE_MANUFACTURER_SPECIFIC_DATA:
                        manufacturerSpecificData = extractBytes(scanRecord, currentPos,
                                dataLength);
                        // The first two bytes of the manufacturer specific data are
                        // manufacturer ids in little endian.
                        manufacturerId = ((manufacturerSpecificData[1] & 0xFF) << 8) +
                                (manufacturerSpecificData[0] & 0xFF);
                        break;
                    default:
                        // Just ignore, we don't handle such data type.
                        break;
                }
                currentPos += dataLength;
            }

            if (serviceUuids.isEmpty()) {
                serviceUuids = null;
            }
            return new ScanRecord(serviceUuids, serviceDataUuid, serviceData,
                    manufacturerId, manufacturerSpecificData, advertiseFlag, txPowerLevel,
                    localName);
        } catch (IndexOutOfBoundsException e) {
            Log.e(TAG, "unable to parse scan record: " + Arrays.toString(scanRecord));
            return null;
        }
    }

    // Parse service uuids.
    private static int parseServiceUuid(byte[] scanRecord, int currentPos, int dataLength,
            int uuidLength, List<ParcelUuid> serviceUuids) {
        while (dataLength > 0) {
            byte[] uuidBytes = extractBytes(scanRecord, currentPos,
                    uuidLength);
            serviceUuids.add(BluetoothUuid.parseUuidFrom(uuidBytes));
            dataLength -= uuidLength;
            currentPos += uuidLength;
        }
        return currentPos;
    }

    // Helper method to extract bytes from byte array.
    private static byte[] extractBytes(byte[] scanRecord, int start, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(scanRecord, start, bytes, 0, length);
        return bytes;
    }
}
