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

package android.bluetooth;

import android.annotation.Nullable;
import android.bluetooth.BluetoothLeAdvertiseScanData.AdvertisementData;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents Bluetooth LE advertise and scan response data. This could be either the advertisement
 * data to be advertised, or the scan record obtained from BLE scans.
 * <p>
 * The exact bluetooth advertising and scan response data fields and types are defined in Bluetooth
 * 4.0 specification, Volume 3, Part C, Section 11 and 18, as well as Supplement to the Bluetooth
 * Core Specification Version 4. Currently the following fields are allowed to be set:
 * <li>Service UUIDs which identify the bluetooth gatt services running on the device.
 * <li>Tx power level which is the transmission power level.
 * <li>Service data which is the data associated with a service.
 * <li>Manufacturer specific data which is the data associated with a particular manufacturer.
 *
 * @see BluetoothLeAdvertiser
 */
public final class BluetoothLeAdvertiseScanData {
    private static final String TAG = "BluetoothLeAdvertiseScanData";

    /**
     * Bluetooth LE Advertising Data type, the data will be placed in AdvData field of advertising
     * packet.
     */
    public static final int ADVERTISING_DATA = 0;
    /**
     * Bluetooth LE scan response data, the data will be placed in ScanRspData field of advertising
     * packet.
     * <p>
     * TODO: unhide when stack supports setting scan response data.
     *
     * @hide
     */
    public static final int SCAN_RESPONSE_DATA = 1;
    /**
     * Scan record parsed from Bluetooth LE scans. The content can contain a concatenation of
     * advertising data and scan response data.
     */
    public static final int PARSED_SCAN_RECORD = 2;

    /**
     * Base data type which contains the common fields for {@link AdvertisementData} and
     * {@link ScanRecord}.
     */
    public abstract static class AdvertiseBaseData {

        private final int mDataType;

        @Nullable
        private final List<ParcelUuid> mServiceUuids;

        private final int mManufacturerId;
        @Nullable
        private final byte[] mManufacturerSpecificData;

        @Nullable
        private final ParcelUuid mServiceDataUuid;
        @Nullable
        private final byte[] mServiceData;

        private AdvertiseBaseData(int dataType,
                List<ParcelUuid> serviceUuids,
                ParcelUuid serviceDataUuid, byte[] serviceData,
                int manufacturerId,
                byte[] manufacturerSpecificData) {
            mDataType = dataType;
            mServiceUuids = serviceUuids;
            mManufacturerId = manufacturerId;
            mManufacturerSpecificData = manufacturerSpecificData;
            mServiceDataUuid = serviceDataUuid;
            mServiceData = serviceData;
        }

        /**
         * Returns the type of data, indicating whether the data is advertising data, scan response
         * data or scan record.
         */
        public int getDataType() {
            return mDataType;
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
         * Returns service data. The first two bytes should be a 16 bit service uuid associated with
         * the service data.
         */
        public byte[] getServiceData() {
            return mServiceData;
        }

        @Override
        public String toString() {
            return "AdvertiseBaseData [mDataType=" + mDataType + ", mServiceUuids=" + mServiceUuids
                    + ", mManufacturerId=" + mManufacturerId + ", mManufacturerSpecificData="
                    + Arrays.toString(mManufacturerSpecificData) + ", mServiceDataUuid="
                    + mServiceDataUuid + ", mServiceData=" + Arrays.toString(mServiceData) + "]";
        }
    }

    /**
     * Advertisement data packet for Bluetooth LE advertising. This represents the data to be
     * broadcasted in Bluetooth LE advertising.
     * <p>
     * Use {@link AdvertisementData.Builder} to create an instance of {@link AdvertisementData} to
     * be advertised.
     *
     * @see BluetoothLeAdvertiser
     */
    public static final class AdvertisementData extends AdvertiseBaseData implements Parcelable {

        private boolean mIncludeTxPowerLevel;

        /**
         * Whether the transmission power level will be included in the advertisement packet.
         */
        public boolean getIncludeTxPowerLevel() {
            return mIncludeTxPowerLevel;
        }

        /**
         * Returns a {@link Builder} to build {@link AdvertisementData}.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(getDataType());
            List<ParcelUuid> uuids = getServiceUuids();
            if (uuids == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(uuids.size());
                dest.writeList(uuids);
            }

            dest.writeInt(getManufacturerId());
            byte[] manufacturerData = getManufacturerSpecificData();
            if (manufacturerData == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(manufacturerData.length);
                dest.writeByteArray(manufacturerData);
            }

            ParcelUuid serviceDataUuid = getServiceDataUuid();
            if (serviceDataUuid == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeParcelable(serviceDataUuid, flags);
                byte[] serviceData = getServiceData();
                if (serviceData == null) {
                    dest.writeInt(0);
                } else {
                    dest.writeInt(serviceData.length);
                    dest.writeByteArray(serviceData);
                }
            }
            dest.writeByte((byte) (getIncludeTxPowerLevel() ? 1 : 0));
        }

        private AdvertisementData(int dataType,
                List<ParcelUuid> serviceUuids,
                ParcelUuid serviceDataUuid, byte[] serviceData,
                int manufacturerId,
                byte[] manufacturerSpecificData, boolean mIncludeTxPowerLevel) {
            super(dataType, serviceUuids, serviceDataUuid, serviceData, manufacturerId,
                    manufacturerSpecificData);
            this.mIncludeTxPowerLevel = mIncludeTxPowerLevel;
        }

        public static final Parcelable.Creator<AdvertisementData> CREATOR =
                new Creator<AdvertisementData>() {
                @Override
                    public AdvertisementData[] newArray(int size) {
                        return new AdvertisementData[size];
                    }

                @Override
                    public AdvertisementData createFromParcel(Parcel in) {
                        Builder builder = newBuilder();
                        int dataType = in.readInt();
                        builder.dataType(dataType);
                        if (in.readInt() > 0) {
                            List<ParcelUuid> uuids = new ArrayList<ParcelUuid>();
                            in.readList(uuids, ParcelUuid.class.getClassLoader());
                            builder.serviceUuids(uuids);
                        }
                        int manufacturerId = in.readInt();
                        int manufacturerDataLength = in.readInt();
                        if (manufacturerDataLength > 0) {
                            byte[] manufacturerData = new byte[manufacturerDataLength];
                            in.readByteArray(manufacturerData);
                            builder.manufacturerData(manufacturerId, manufacturerData);
                        }
                        if (in.readInt() == 1) {
                            ParcelUuid serviceDataUuid = in.readParcelable(
                                    ParcelUuid.class.getClassLoader());
                            int serviceDataLength = in.readInt();
                            if (serviceDataLength > 0) {
                                byte[] serviceData = new byte[serviceDataLength];
                                in.readByteArray(serviceData);
                                builder.serviceData(serviceDataUuid, serviceData);
                            }
                        }
                        builder.includeTxPowerLevel(in.readByte() == 1);
                        return builder.build();
                    }
                };

        /**
         * Builder for {@link BluetoothLeAdvertiseScanData.AdvertisementData}. Use
         * {@link AdvertisementData#newBuilder()} to get an instance of the Builder.
         */
        public static final class Builder {
            private static final int MAX_ADVERTISING_DATA_BYTES = 31;
            // Each fields need one byte for field length and another byte for field type.
            private static final int OVERHEAD_BYTES_PER_FIELD = 2;
            // Flags field will be set by system.
            private static final int FLAGS_FIELD_BYTES = 3;

            private int mDataType;
            @Nullable
            private List<ParcelUuid> mServiceUuids;
            private boolean mIncludeTxPowerLevel;
            private int mManufacturerId;
            @Nullable
            private byte[] mManufacturerSpecificData;
            @Nullable
            private ParcelUuid mServiceDataUuid;
            @Nullable
            private byte[] mServiceData;

            /**
             * Set data type.
             *
             * @param dataType Data type, could only be
             *            {@link BluetoothLeAdvertiseScanData#ADVERTISING_DATA}
             * @throws IllegalArgumentException If the {@code dataType} is invalid.
             */
            public Builder dataType(int dataType) {
                if (mDataType != ADVERTISING_DATA && mDataType != SCAN_RESPONSE_DATA) {
                    throw new IllegalArgumentException("invalid data type - " + dataType);
                }
                mDataType = dataType;
                return this;
            }

            /**
             * Set the service uuids. Note the corresponding bluetooth Gatt services need to be
             * already added on the device before start BLE advertising.
             *
             * @param serviceUuids Service uuids to be advertised, could be 16-bit, 32-bit or
             *            128-bit uuids.
             * @throws IllegalArgumentException If the {@code serviceUuids} are null.
             */
            public Builder serviceUuids(List<ParcelUuid> serviceUuids) {
                if (serviceUuids == null) {
                    throw new IllegalArgumentException("serivceUuids are null");
                }
                mServiceUuids = serviceUuids;
                return this;
            }

            /**
             * Add service data to advertisement.
             *
             * @param serviceDataUuid A 16 bit uuid of the service data
             * @param serviceData Service data - the first two bytes of the service data are the
             *            service data uuid.
             * @throws IllegalArgumentException If the {@code serviceDataUuid} or
             *             {@code serviceData} is empty.
             */
            public Builder serviceData(ParcelUuid serviceDataUuid, byte[] serviceData) {
                if (serviceDataUuid == null || serviceData == null) {
                    throw new IllegalArgumentException(
                            "serviceDataUuid or serviceDataUuid is null");
                }
                mServiceDataUuid = serviceDataUuid;
                mServiceData = serviceData;
                return this;
            }

            /**
             * Set manufacturer id and data. See <a
             * href="https://www.bluetooth.org/en-us/specification/assigned-numbers/company-identifiers">assigned
             * manufacturer identifies</a> for the existing company identifiers.
             *
             * @param manufacturerId Manufacturer id assigned by Bluetooth SIG.
             * @param manufacturerSpecificData Manufacturer specific data - the first two bytes of
             *            the manufacturer specific data are the manufacturer id.
             * @throws IllegalArgumentException If the {@code manufacturerId} is negative or
             *             {@code manufacturerSpecificData} is null.
             */
            public Builder manufacturerData(int manufacturerId, byte[] manufacturerSpecificData) {
                if (manufacturerId < 0) {
                    throw new IllegalArgumentException(
                            "invalid manufacturerId - " + manufacturerId);
                }
                if (manufacturerSpecificData == null) {
                    throw new IllegalArgumentException("manufacturerSpecificData is null");
                }
                mManufacturerId = manufacturerId;
                mManufacturerSpecificData = manufacturerSpecificData;
                return this;
            }

            /**
             * Whether the transmission power level should be included in the advertising packet.
             */
            public Builder includeTxPowerLevel(boolean includeTxPowerLevel) {
                mIncludeTxPowerLevel = includeTxPowerLevel;
                return this;
            }

            /**
             * Build the {@link BluetoothLeAdvertiseScanData}.
             *
             * @throws IllegalArgumentException If the data size is larger than 31 bytes.
             */
            public AdvertisementData build() {
                if (totalBytes() > MAX_ADVERTISING_DATA_BYTES) {
                    throw new IllegalArgumentException(
                            "advertisement data size is larger than 31 bytes");
                }
                return new AdvertisementData(mDataType,
                        mServiceUuids,
                        mServiceDataUuid,
                        mServiceData, mManufacturerId, mManufacturerSpecificData,
                        mIncludeTxPowerLevel);
            }

            // Compute the size of the advertisement data.
            private int totalBytes() {
                int size = FLAGS_FIELD_BYTES; // flags field is always set.
                if (mServiceUuids != null) {
                    int num16BitUuids = 0;
                    int num32BitUuids = 0;
                    int num128BitUuids = 0;
                    for (ParcelUuid uuid : mServiceUuids) {
                        if (BluetoothUuid.is16BitUuid(uuid)) {
                            ++num16BitUuids;
                        } else if (BluetoothUuid.is32BitUuid(uuid)) {
                            ++num32BitUuids;
                        } else {
                            ++num128BitUuids;
                        }
                    }
                    // 16 bit service uuids are grouped into one field when doing advertising.
                    if (num16BitUuids != 0) {
                        size += OVERHEAD_BYTES_PER_FIELD +
                                num16BitUuids * BluetoothUuid.UUID_BYTES_16_BIT;
                    }
                    // 32 bit service uuids are grouped into one field when doing advertising.
                    if (num32BitUuids != 0) {
                        size += OVERHEAD_BYTES_PER_FIELD +
                                num32BitUuids * BluetoothUuid.UUID_BYTES_32_BIT;
                    }
                    // 128 bit service uuids are grouped into one field when doing advertising.
                    if (num128BitUuids != 0) {
                        size += OVERHEAD_BYTES_PER_FIELD +
                                num128BitUuids * BluetoothUuid.UUID_BYTES_128_BIT;
                    }
                }
                if (mServiceData != null) {
                    size += OVERHEAD_BYTES_PER_FIELD + mServiceData.length;
                }
                if (mManufacturerSpecificData != null) {
                    size += OVERHEAD_BYTES_PER_FIELD + mManufacturerSpecificData.length;
                }
                if (mIncludeTxPowerLevel) {
                    size += OVERHEAD_BYTES_PER_FIELD + 1; // tx power level value is one byte.
                }
                return size;
            }
        }

    }

    /**
     * Represents a scan record from Bluetooth LE scan.
     */
    public static final class ScanRecord extends AdvertiseBaseData {
        // Flags of the advertising data.
        private final int mAdvertiseFlags;

        // Transmission power level(in dB).
        private final int mTxPowerLevel;

        // Local name of the Bluetooth LE device.
        private final String mLocalName;

        /**
         * Returns the advertising flags indicating the discoverable mode and capability of the
         * device. Returns -1 if the flag field is not set.
         */
        public int getAdvertiseFlags() {
            return mAdvertiseFlags;
        }

        /**
         * Returns the transmission power level of the packet in dBm. Returns
         * {@link Integer#MIN_VALUE} if the field is not set. This value can be used to calculate
         * the path loss of a received packet using the following equation:
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

        ScanRecord(int dataType,
                List<ParcelUuid> serviceUuids,
                ParcelUuid serviceDataUuid, byte[] serviceData,
                int manufacturerId,
                byte[] manufacturerSpecificData, int advertiseFlags, int txPowerLevel,
                String localName) {
            super(dataType, serviceUuids, serviceDataUuid, serviceData, manufacturerId,
                    manufacturerSpecificData);
            mLocalName = localName;
            mAdvertiseFlags = advertiseFlags;
            mTxPowerLevel = txPowerLevel;
        }

        /**
         * Get a {@link Parser} to parse the scan record byte array into {@link ScanRecord}.
         */
        public static Parser getParser() {
            return new Parser();
        }

        /**
         * A parser class used to parse a Bluetooth LE scan record to
         * {@link BluetoothLeAdvertiseScanData}. Note not all field types would be parsed.
         */
        public static final class Parser {
            private static final String PARSER_TAG = "BluetoothLeAdvertiseDataParser";

            // The following data type values are assigned by Bluetooth SIG.
            // For more details refer to Bluetooth 4.0 specification, Volume 3, Part C, Section 18.
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

            // Helper method to extract bytes from byte array.
            private static byte[] extractBytes(byte[] scanRecord, int start, int length) {
                byte[] bytes = new byte[length];
                System.arraycopy(scanRecord, start, bytes, 0, length);
                return bytes;
            }

            /**
             * Parse scan record to {@link BluetoothLeAdvertiseScanData.ScanRecord}.
             * <p>
             * The format is defined in Bluetooth 4.0 specification, Volume 3, Part C, Section 11
             * and 18.
             * <p>
             * All numerical multi-byte entities and values shall use little-endian
             * <strong>byte</strong> order.
             *
             * @param scanRecord The scan record of Bluetooth LE advertisement and/or scan response.
             */
            public ScanRecord parseFromScanRecord(byte[] scanRecord) {
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
                    return new ScanRecord(PARSED_SCAN_RECORD,
                            serviceUuids, serviceDataUuid, serviceData,
                            manufacturerId, manufacturerSpecificData, advertiseFlag, txPowerLevel,
                            localName);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(PARSER_TAG,
                            "unable to parse scan record: " + Arrays.toString(scanRecord));
                    return null;
                }
            }

            // Parse service uuids.
            private int parseServiceUuid(byte[] scanRecord, int currentPos, int dataLength,
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
        }
    }

}
