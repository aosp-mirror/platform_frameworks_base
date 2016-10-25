/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

/**
* Static helper methods and constants to decode the ParcelUuid of remote devices.
*  @hide
*/
public final class BluetoothUuid {

    /* See Bluetooth Assigned Numbers document - SDP section, to get the values of UUIDs
     * for the various services.
     *
     * The following 128 bit values are calculated as:
     *  uuid * 2^96 + BASE_UUID
     */
    public static final ParcelUuid AudioSink =
            ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AudioSource =
            ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AdvAudioDist =
            ParcelUuid.fromString("0000110D-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HSP =
            ParcelUuid.fromString("00001108-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid HSP_AG =
            ParcelUuid.fromString("00001112-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid Handsfree =
            ParcelUuid.fromString("0000111E-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid Handsfree_AG =
            ParcelUuid.fromString("0000111F-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AvrcpController =
            ParcelUuid.fromString("0000110E-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid AvrcpTarget =
            ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid ObexObjectPush =
            ParcelUuid.fromString("00001105-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid Hid =
            ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid Hogp =
            ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");
    public static final ParcelUuid PANU =
            ParcelUuid.fromString("00001115-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid NAP =
            ParcelUuid.fromString("00001116-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid BNEP =
            ParcelUuid.fromString("0000000f-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid PBAP_PCE =
            ParcelUuid.fromString("0000112e-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid PBAP_PSE =
            ParcelUuid.fromString("0000112f-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MAP =
            ParcelUuid.fromString("00001134-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MNS =
            ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");
    public static final ParcelUuid MAS =
            ParcelUuid.fromString("00001132-0000-1000-8000-00805F9B34FB");
  public static final ParcelUuid SAP =
            ParcelUuid.fromString("0000112D-0000-1000-8000-00805F9B34FB");
			
    public static final ParcelUuid BASE_UUID =
            ParcelUuid.fromString("00000000-0000-1000-8000-00805F9B34FB");

    /** Length of bytes for 16 bit UUID */
    public static final int UUID_BYTES_16_BIT = 2;
    /** Length of bytes for 32 bit UUID */
    public static final int UUID_BYTES_32_BIT = 4;
    /** Length of bytes for 128 bit UUID */
    public static final int UUID_BYTES_128_BIT = 16;

    public static final ParcelUuid[] RESERVED_UUIDS = {
        AudioSink, AudioSource, AdvAudioDist, HSP, Handsfree, AvrcpController, AvrcpTarget,
        ObexObjectPush, PANU, NAP, MAP, MNS, MAS, SAP};

    public static boolean isAudioSource(ParcelUuid uuid) {
        return uuid.equals(AudioSource);
    }

    public static boolean isAudioSink(ParcelUuid uuid) {
        return uuid.equals(AudioSink);
    }

    public static boolean isAdvAudioDist(ParcelUuid uuid) {
        return uuid.equals(AdvAudioDist);
    }

    public static boolean isHandsfree(ParcelUuid uuid) {
        return uuid.equals(Handsfree);
    }

    public static boolean isHeadset(ParcelUuid uuid) {
        return uuid.equals(HSP);
    }

    public static boolean isAvrcpController(ParcelUuid uuid) {
        return uuid.equals(AvrcpController);
    }

    public static boolean isAvrcpTarget(ParcelUuid uuid) {
        return uuid.equals(AvrcpTarget);
    }

    public static boolean isInputDevice(ParcelUuid uuid) {
        return uuid.equals(Hid);
    }

    public static boolean isPanu(ParcelUuid uuid) {
        return uuid.equals(PANU);
    }

    public static boolean isNap(ParcelUuid uuid) {
        return uuid.equals(NAP);
    }

    public static boolean isBnep(ParcelUuid uuid) {
        return uuid.equals(BNEP);
    }
    public static boolean isMap(ParcelUuid uuid) {
        return uuid.equals(MAP);
    }
    public static boolean isMns(ParcelUuid uuid) {
        return uuid.equals(MNS);
    }
    public static boolean isMas(ParcelUuid uuid) {
        return uuid.equals(MAS);
    }
    public static boolean isSap(ParcelUuid uuid) {
        return uuid.equals(SAP);
    }

    /**
     * Returns true if ParcelUuid is present in uuidArray
     *
     * @param uuidArray - Array of ParcelUuids
     * @param uuid
     */
    public static boolean isUuidPresent(ParcelUuid[] uuidArray, ParcelUuid uuid) {
        if ((uuidArray == null || uuidArray.length == 0) && uuid == null)
            return true;

        if (uuidArray == null)
            return false;

        for (ParcelUuid element: uuidArray) {
            if (element.equals(uuid)) return true;
        }
        return false;
    }

    /**
     * Returns true if there any common ParcelUuids in uuidA and uuidB.
     *
     * @param uuidA - List of ParcelUuids
     * @param uuidB - List of ParcelUuids
     *
     */
    public static boolean containsAnyUuid(ParcelUuid[] uuidA, ParcelUuid[] uuidB) {
        if (uuidA == null && uuidB == null) return true;

        if (uuidA == null) {
            return uuidB.length == 0 ? true : false;
        }

        if (uuidB == null) {
            return uuidA.length == 0 ? true : false;
        }

        HashSet<ParcelUuid> uuidSet = new HashSet<ParcelUuid> (Arrays.asList(uuidA));
        for (ParcelUuid uuid: uuidB) {
            if (uuidSet.contains(uuid)) return true;
        }
        return false;
    }

    /**
     * Returns true if all the ParcelUuids in ParcelUuidB are present in
     * ParcelUuidA
     *
     * @param uuidA - Array of ParcelUuidsA
     * @param uuidB - Array of ParcelUuidsB
     *
     */
    public static boolean containsAllUuids(ParcelUuid[] uuidA, ParcelUuid[] uuidB) {
        if (uuidA == null && uuidB == null) return true;

        if (uuidA == null) {
            return uuidB.length == 0 ? true : false;
        }

        if (uuidB == null) return true;

        HashSet<ParcelUuid> uuidSet = new HashSet<ParcelUuid> (Arrays.asList(uuidA));
        for (ParcelUuid uuid: uuidB) {
            if (!uuidSet.contains(uuid)) return false;
        }
        return true;
    }

    /**
     * Extract the Service Identifier or the actual uuid from the Parcel Uuid.
     * For example, if 0000110B-0000-1000-8000-00805F9B34FB is the parcel Uuid,
     * this function will return 110B
     * @param parcelUuid
     * @return the service identifier.
     */
    public static int getServiceIdentifierFromParcelUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        long value = (uuid.getMostSignificantBits() & 0x0000FFFF00000000L) >>> 32;
        return (int)value;
    }

    /**
     * Parse UUID from bytes. The {@code uuidBytes} can represent a 16-bit, 32-bit or 128-bit UUID,
     * but the returned UUID is always in 128-bit format.
     * Note UUID is little endian in Bluetooth.
     *
     * @param uuidBytes Byte representation of uuid.
     * @return {@link ParcelUuid} parsed from bytes.
     * @throws IllegalArgumentException If the {@code uuidBytes} cannot be parsed.
     */
    public static ParcelUuid parseUuidFrom(byte[] uuidBytes) {
        if (uuidBytes == null) {
            throw new IllegalArgumentException("uuidBytes cannot be null");
        }
        int length = uuidBytes.length;
        if (length != UUID_BYTES_16_BIT && length != UUID_BYTES_32_BIT &&
                length != UUID_BYTES_128_BIT) {
            throw new IllegalArgumentException("uuidBytes length invalid - " + length);
        }

        // Construct a 128 bit UUID.
        if (length == UUID_BYTES_128_BIT) {
            ByteBuffer buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.LITTLE_ENDIAN);
            long msb = buf.getLong(8);
            long lsb = buf.getLong(0);
            return new ParcelUuid(new UUID(msb, lsb));
        }

        // For 16 bit and 32 bit UUID we need to convert them to 128 bit value.
        // 128_bit_value = uuid * 2^96 + BASE_UUID
        long shortUuid;
        if (length == UUID_BYTES_16_BIT) {
            shortUuid = uuidBytes[0] & 0xFF;
            shortUuid += (uuidBytes[1] & 0xFF) << 8;
        } else {
            shortUuid = uuidBytes[0] & 0xFF ;
            shortUuid += (uuidBytes[1] & 0xFF) << 8;
            shortUuid += (uuidBytes[2] & 0xFF) << 16;
            shortUuid += (uuidBytes[3] & 0xFF) << 24;
        }
        long msb = BASE_UUID.getUuid().getMostSignificantBits() + (shortUuid << 32);
        long lsb = BASE_UUID.getUuid().getLeastSignificantBits();
        return new ParcelUuid(new UUID(msb, lsb));
    }

    /**
     * Parse UUID to bytes. The returned value is shortest representation, a 16-bit, 32-bit or 128-bit UUID,
     * Note returned value is little endian (Bluetooth).
     *
     * @param uuid uuid to parse.
     * @return shortest representation of {@code uuid} as bytes.
     * @throws IllegalArgumentException If the {@code uuid} is null.
     */
    public static byte[] uuidToBytes(ParcelUuid uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid cannot be null");
        }

        if (is16BitUuid(uuid)) {
            byte[] uuidBytes = new byte[UUID_BYTES_16_BIT];
            int uuidVal = getServiceIdentifierFromParcelUuid(uuid);
            uuidBytes[0] = (byte)(uuidVal & 0xFF);
            uuidBytes[1] = (byte)((uuidVal & 0xFF00) >> 8);
            return uuidBytes;
        }

        if (is32BitUuid(uuid)) {
            byte[] uuidBytes = new byte[UUID_BYTES_32_BIT];
            int uuidVal = getServiceIdentifierFromParcelUuid(uuid);
            uuidBytes[0] = (byte)(uuidVal & 0xFF);
            uuidBytes[1] = (byte)((uuidVal & 0xFF00) >> 8);
            uuidBytes[2] = (byte)((uuidVal & 0xFF0000) >> 16);
            uuidBytes[3] = (byte)((uuidVal & 0xFF000000) >> 24);
            return uuidBytes;
        }

        // Construct a 128 bit UUID.
        long msb = uuid.getUuid().getMostSignificantBits();
        long lsb = uuid.getUuid().getLeastSignificantBits();

        byte[] uuidBytes = new byte[UUID_BYTES_128_BIT];
        ByteBuffer buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(8, msb);
        buf.putLong(0, lsb);
        return uuidBytes;
    }

    /**
     * Check whether the given parcelUuid can be converted to 16 bit bluetooth uuid.
     *
     * @param parcelUuid
     * @return true if the parcelUuid can be converted to 16 bit uuid, false otherwise.
     */
    public static boolean is16BitUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        if (uuid.getLeastSignificantBits() != BASE_UUID.getUuid().getLeastSignificantBits()) {
            return false;
        }
        return ((uuid.getMostSignificantBits() & 0xFFFF0000FFFFFFFFL) == 0x1000L);
    }


    /**
     * Check whether the given parcelUuid can be converted to 32 bit bluetooth uuid.
     *
     * @param parcelUuid
     * @return true if the parcelUuid can be converted to 32 bit uuid, false otherwise.
     */
    public static boolean is32BitUuid(ParcelUuid parcelUuid) {
        UUID uuid = parcelUuid.getUuid();
        if (uuid.getLeastSignificantBits() != BASE_UUID.getUuid().getLeastSignificantBits()) {
            return false;
        }
        if (is16BitUuid(parcelUuid)) {
            return false;
        }
        return ((uuid.getMostSignificantBits() & 0xFFFFFFFFL) == 0x1000L);
    }
}
