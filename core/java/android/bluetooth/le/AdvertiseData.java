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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Advertise data packet container for Bluetooth LE advertising. This represents the data to be
 * advertised as well as the scan response data for active scans.
 * <p>
 * Use {@link AdvertiseData.Builder} to create an instance of {@link AdvertiseData} to be
 * advertised.
 *
 * @see BluetoothLeAdvertiser
 * @see ScanRecord
 */
public final class AdvertiseData implements Parcelable {

    @Nullable
    private final List<ParcelUuid> mServiceUuids;

    @NonNull
    private final List<ParcelUuid> mServiceSolicitationUuids;

    private final SparseArray<byte[]> mManufacturerSpecificData;
    private final Map<ParcelUuid, byte[]> mServiceData;
    private final boolean mIncludeTxPowerLevel;
    private final boolean mIncludeDeviceName;

    private AdvertiseData(List<ParcelUuid> serviceUuids,
            List<ParcelUuid> serviceSolicitationUuids,
            SparseArray<byte[]> manufacturerData,
            Map<ParcelUuid, byte[]> serviceData,
            boolean includeTxPowerLevel,
            boolean includeDeviceName) {
        mServiceUuids = serviceUuids;
        mServiceSolicitationUuids = serviceSolicitationUuids;
        mManufacturerSpecificData = manufacturerData;
        mServiceData = serviceData;
        mIncludeTxPowerLevel = includeTxPowerLevel;
        mIncludeDeviceName = includeDeviceName;
    }

    /**
     * Returns a list of service UUIDs within the advertisement that are used to identify the
     * Bluetooth GATT services.
     */
    public List<ParcelUuid> getServiceUuids() {
        return mServiceUuids;
    }

    /**
     * Returns a list of service solicitation UUIDs within the advertisement that we invite to connect.
     */
    @NonNull
    public List<ParcelUuid> getServiceSolicitationUuids() {
        return mServiceSolicitationUuids;
    }

    /**
     * Returns an array of manufacturer Id and the corresponding manufacturer specific data. The
     * manufacturer id is a non-negative number assigned by Bluetooth SIG.
     */
    public SparseArray<byte[]> getManufacturerSpecificData() {
        return mManufacturerSpecificData;
    }

    /**
     * Returns a map of 16-bit UUID and its corresponding service data.
     */
    public Map<ParcelUuid, byte[]> getServiceData() {
        return mServiceData;
    }

    /**
     * Whether the transmission power level will be included in the advertisement packet.
     */
    public boolean getIncludeTxPowerLevel() {
        return mIncludeTxPowerLevel;
    }

    /**
     * Whether the device name will be included in the advertisement packet.
     */
    public boolean getIncludeDeviceName() {
        return mIncludeDeviceName;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mServiceUuids, mServiceSolicitationUuids, mManufacturerSpecificData,
                mServiceData, mIncludeDeviceName, mIncludeTxPowerLevel);
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        AdvertiseData other = (AdvertiseData) obj;
        return Objects.equals(mServiceUuids, other.mServiceUuids)
                && Objects.equals(mServiceSolicitationUuids, other.mServiceSolicitationUuids)
                && BluetoothLeUtils.equals(mManufacturerSpecificData,
                    other.mManufacturerSpecificData)
                && BluetoothLeUtils.equals(mServiceData, other.mServiceData)
                && mIncludeDeviceName == other.mIncludeDeviceName
                && mIncludeTxPowerLevel == other.mIncludeTxPowerLevel;
    }

    @Override
    public String toString() {
        return "AdvertiseData [mServiceUuids=" + mServiceUuids + ", mServiceSolicitationUuids="
                + mServiceSolicitationUuids + ", mManufacturerSpecificData="
                + BluetoothLeUtils.toString(mManufacturerSpecificData) + ", mServiceData="
                + BluetoothLeUtils.toString(mServiceData)
                + ", mIncludeTxPowerLevel=" + mIncludeTxPowerLevel + ", mIncludeDeviceName="
                + mIncludeDeviceName + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedArray(mServiceUuids.toArray(new ParcelUuid[mServiceUuids.size()]), flags);
        dest.writeTypedArray(mServiceSolicitationUuids.toArray(
                new ParcelUuid[mServiceSolicitationUuids.size()]), flags);

        // mManufacturerSpecificData could not be null.
        dest.writeInt(mManufacturerSpecificData.size());
        for (int i = 0; i < mManufacturerSpecificData.size(); ++i) {
            dest.writeInt(mManufacturerSpecificData.keyAt(i));
            dest.writeByteArray(mManufacturerSpecificData.valueAt(i));
        }
        dest.writeInt(mServiceData.size());
        for (ParcelUuid uuid : mServiceData.keySet()) {
            dest.writeTypedObject(uuid, flags);
            dest.writeByteArray(mServiceData.get(uuid));
        }
        dest.writeByte((byte) (getIncludeTxPowerLevel() ? 1 : 0));
        dest.writeByte((byte) (getIncludeDeviceName() ? 1 : 0));
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AdvertiseData> CREATOR =
            new Creator<AdvertiseData>() {
                @Override
                public AdvertiseData[] newArray(int size) {
                    return new AdvertiseData[size];
                }

                @Override
                public AdvertiseData createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    ArrayList<ParcelUuid> uuids = in.createTypedArrayList(ParcelUuid.CREATOR);
                    for (ParcelUuid uuid : uuids) {
                        builder.addServiceUuid(uuid);
                    }

                    ArrayList<ParcelUuid> solicitationUuids = in.createTypedArrayList(ParcelUuid.CREATOR);
                    for (ParcelUuid uuid : solicitationUuids) {
                        builder.addServiceSolicitationUuid(uuid);
                    }

                    int manufacturerSize = in.readInt();
                    for (int i = 0; i < manufacturerSize; ++i) {
                        int manufacturerId = in.readInt();
                        byte[] manufacturerData = in.createByteArray();
                        builder.addManufacturerData(manufacturerId, manufacturerData);
                    }
                    int serviceDataSize = in.readInt();
                    for (int i = 0; i < serviceDataSize; ++i) {
                        ParcelUuid serviceDataUuid = in.readTypedObject(ParcelUuid.CREATOR);
                        byte[] serviceData = in.createByteArray();
                        builder.addServiceData(serviceDataUuid, serviceData);
                    }
                    builder.setIncludeTxPowerLevel(in.readByte() == 1);
                    builder.setIncludeDeviceName(in.readByte() == 1);
                    return builder.build();
                }
            };

    /**
     * Builder for {@link AdvertiseData}.
     */
    public static final class Builder {
        @Nullable
        private List<ParcelUuid> mServiceUuids = new ArrayList<ParcelUuid>();
        @NonNull
        private List<ParcelUuid> mServiceSolicitationUuids = new ArrayList<ParcelUuid>();
        private SparseArray<byte[]> mManufacturerSpecificData = new SparseArray<byte[]>();
        private Map<ParcelUuid, byte[]> mServiceData = new ArrayMap<ParcelUuid, byte[]>();
        private boolean mIncludeTxPowerLevel;
        private boolean mIncludeDeviceName;

        /**
         * Add a service UUID to advertise data.
         *
         * @param serviceUuid A service UUID to be advertised.
         * @throws IllegalArgumentException If the {@code serviceUuid} is null.
         */
        public Builder addServiceUuid(ParcelUuid serviceUuid) {
            if (serviceUuid == null) {
                throw new IllegalArgumentException("serviceUuid is null");
            }
            mServiceUuids.add(serviceUuid);
            return this;
        }

        /**
         * Add a service solicitation UUID to advertise data.
         *
         * @param serviceSolicitationUuid A service solicitation UUID to be advertised.
         * @throws IllegalArgumentException If the {@code serviceSolicitationUuid} is null.
         */
        @NonNull
        public Builder addServiceSolicitationUuid(@NonNull ParcelUuid serviceSolicitationUuid) {
            if (serviceSolicitationUuid == null) {
                throw new IllegalArgumentException("serviceSolicitationUuid is null");
            }
            mServiceSolicitationUuids.add(serviceSolicitationUuid);
            return this;
        }
        /**
         * Add service data to advertise data.
         *
         * @param serviceDataUuid 16-bit UUID of the service the data is associated with
         * @param serviceData Service data
         * @throws IllegalArgumentException If the {@code serviceDataUuid} or {@code serviceData} is
         * empty.
         */
        public Builder addServiceData(ParcelUuid serviceDataUuid, byte[] serviceData) {
            if (serviceDataUuid == null || serviceData == null) {
                throw new IllegalArgumentException(
                        "serviceDataUuid or serviceDataUuid is null");
            }
            mServiceData.put(serviceDataUuid, serviceData);
            return this;
        }

        /**
         * Add manufacturer specific data.
         * <p>
         * Please refer to the Bluetooth Assigned Numbers document provided by the <a
         * href="https://www.bluetooth.org">Bluetooth SIG</a> for a list of existing company
         * identifiers.
         *
         * @param manufacturerId Manufacturer ID assigned by Bluetooth SIG.
         * @param manufacturerSpecificData Manufacturer specific data
         * @throws IllegalArgumentException If the {@code manufacturerId} is negative or {@code
         * manufacturerSpecificData} is null.
         */
        public Builder addManufacturerData(int manufacturerId, byte[] manufacturerSpecificData) {
            if (manufacturerId < 0) {
                throw new IllegalArgumentException(
                        "invalid manufacturerId - " + manufacturerId);
            }
            if (manufacturerSpecificData == null) {
                throw new IllegalArgumentException("manufacturerSpecificData is null");
            }
            mManufacturerSpecificData.put(manufacturerId, manufacturerSpecificData);
            return this;
        }

        /**
         * Whether the transmission power level should be included in the advertise packet. Tx power
         * level field takes 3 bytes in advertise packet.
         */
        public Builder setIncludeTxPowerLevel(boolean includeTxPowerLevel) {
            mIncludeTxPowerLevel = includeTxPowerLevel;
            return this;
        }

        /**
         * Set whether the device name should be included in advertise packet.
         */
        public Builder setIncludeDeviceName(boolean includeDeviceName) {
            mIncludeDeviceName = includeDeviceName;
            return this;
        }

        /**
         * Build the {@link AdvertiseData}.
         */
        public AdvertiseData build() {
            return new AdvertiseData(mServiceUuids, mServiceSolicitationUuids,
                    mManufacturerSpecificData, mServiceData, mIncludeTxPowerLevel,
                    mIncludeDeviceName);
        }
    }
}
