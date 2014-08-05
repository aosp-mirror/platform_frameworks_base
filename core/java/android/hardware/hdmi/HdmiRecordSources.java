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

package android.hardware.hdmi;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.util.Log;

/**
 * Container for record source used for one touch record.
 * Use one of helper method by source type.
 * <ul>
 * <li>Own source: {@link #ofOwnSource()}
 * <li>Digital service(channel id): {@link #ofDigitalChannelId(int, DigitalChannelData)}
 * <li>Digital service(ARIB): {@link #ofArib(int, AribData)}
 * <li>Digital service(ATSC): {@link #ofAtsc(int, AtscData)}
 * <li>Digital service(DVB): {@link #ofDvb(int, DvbData)}
 * <li>Analogue: {@link #ofAnalogue(int, int, int)}
 * <li>External plug: {@link #ofExternalPlug(int)}
 * <li>External physical address: {@link #ofExternalPhysicalAddress(int)}.
 * <ul>
 *
 * @hide
 */
@SystemApi
public final class HdmiRecordSources {
    private static final String TAG = "HdmiRecordSources";

    /** Record source type for "Own Source". */
    private static final int RECORD_SOURCE_TYPE_OWN_SOURCE = 1;
    /** Record source type for "Digital Service". */
    private static final int RECORD_SOURCE_TYPE_DIGITAL_SERVICE = 2;
    /** Record source type for "Analogue Service". */
    private static final int RECORD_SOURCE_TYPE_ANALOGUE_SERVICE = 3;
    /** Record source type for "Exteranl Plug". */
    private static final int RECORD_SOURCE_TYPE_EXTERNAL_PLUG = 4;
    /** Record source type for "External Physical Address". */
    private static final int RECORD_SOURCE_TYPE_EXTERNAL_PHYSICAL_ADDRESS = 5;

    private HdmiRecordSources() {}

    /**
     * Base class for each record source.
     */
    static abstract class RecordSource {
        protected final int mSourceType;
        protected final int mExtraDataSize;

        protected RecordSource(int sourceType, int extraDataSize) {
            mSourceType = sourceType;
            mExtraDataSize = extraDataSize;
        }

        abstract int extraParamToByteArray(byte[] data, int index);

        final int getDataSize(boolean includeType)  {
            return includeType ? mExtraDataSize + 1 : mExtraDataSize;
        }

        final int toByteArray(boolean includeType, byte[] data, int index) {
            if (includeType) {
                // 1 to 8 bytes (depends on source).
                // {[Record Source Type]} |
                // {[Record Source Type] [Digital Service Identification]} |
                // {[Record Source Type] [Analogue Broadcast Type] [Analogue Frequency]
                // [Broadcast System]} |
                // {[Record Source Type] [External Plug]} |
                // {[Record Source Type] [External Physical Address]}
                // The first byte is used for record source type.
                data[index++] = (byte) mSourceType;
            }
            extraParamToByteArray(data, index);
            return getDataSize(includeType);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ---- Own source -----------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    /**
     * Create {@link OwnSource} of own source.
     */
    public static OwnSource ofOwnSource() {
        return new OwnSource();
    }

    /**
     * @hide
     */
    @SystemApi
    public static final class OwnSource extends RecordSource {
        private static final int EXTRA_DATA_SIZE = 0;

        private OwnSource() {
            super(RECORD_SOURCE_TYPE_OWN_SOURCE, EXTRA_DATA_SIZE);
        }

        @Override
        int extraParamToByteArray(byte[] data, int index) {
            return 0;
        }
    }


    // ---------------------------------------------------------------------------------------------
    // ---- Digital service data -------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    /**
     * Digital broadcast general types
     */
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ARIB = 0x0;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ATSC = 0x1;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_DVB = 0x2;

    /**
     * Digital broadcast specific types
     */
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ARIB_BS = 0x8;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ARIB_CS = 0x9;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ARIB_T = 0xA;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ATSC_CABLE = 0x10;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ATSC_SATELLITE = 0x11;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_ATSC_TERRESTRIAL = 0x12;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_DVB_C = 0x18;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_DVB_S = 0x19;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_DVB_S2 = 0x1A;
    /** @hide */
    public static final int DIGITAL_BROADCAST_TYPE_DVB_T = 0x1B;

    /** Channel number formats. */
    private static final int CHANNEL_NUMBER_FORMAT_1_PART = 0x01;
    private static final int CHANNEL_NUMBER_FORMAT_2_PART = 0x02;

    /**
     * Interface for digital source identification.
     */
    private interface DigitalServiceIdentification {
        int toByteArray(byte[] data, int index);
    }

    /**
     * Digital service identification for ARIB.
     * <p>
     * It consists of the following fields
     * <ul>
     * <li>transport stream id: 2bytes
     * <li>service id: 2bytes
     * <li>original network id: 2bytes
     * </ul>
     * @hide
     */
    public static final class AribData implements DigitalServiceIdentification {
        /** The transport_stream_ID of the transport stream carrying the required service */
        private final int mTransportStreamId;
        /** The service_ID of the required service */
        private final int mServiceId;
        /**
         * The original_network_ID of the network carrying the transport stream for the required
         * service
         */
        private final int mOriginalNetworkId;

        public AribData(int transportStreamId, int serviceId, int originalNetworkId) {
            mTransportStreamId = transportStreamId;
            mServiceId = serviceId;
            mOriginalNetworkId = originalNetworkId;
        }

        @Override
        public int toByteArray(byte[] data, int index) {
            return threeFieldsToSixBytes(mTransportStreamId, mServiceId, mOriginalNetworkId, data,
                    index);
        }
    }

    /**
     * Digital service identification for ATSC.
     * <p>
     * It consists of the following fields
     * <ul>
     * <li>transport stream id: 2bytes
     * <li>program number: 2bytes
     * <li>reserved: 2bytes
     * </ul>
     * @hide
     */
    public static final class AtscData implements DigitalServiceIdentification {
        /** The transport_stream_ID of the transport stream carrying the required service */
        private final int mTransportStreamId;
        /** The Program_number of the required service */
        private final int mProgramNumber;

        public AtscData(int transportStreamId, int programNumber) {
            mTransportStreamId = transportStreamId;
            mProgramNumber = programNumber;
        }

        @Override
        public int toByteArray(byte[] data, int index) {
            return threeFieldsToSixBytes(mTransportStreamId, mProgramNumber, 0, data, index);
        }
    }

    /**
     * Digital service identification for DVB.
     * <p>
     * It consists of the following fields
     * <ul>
     * <li>transport stream id: 2bytes
     * <li>service id: 2bytes
     * <li>original network id: 2bytes
     * </ul>
     * @hide
     */
    public static final class DvbData implements DigitalServiceIdentification {
        /** The transport_stream_ID of the transport stream carrying the required service */
        private final int mTransportStreamId;
        /** The service_ID of the required service */
        private final int mServiceId;
        /**
         * The original_network_ID of the network carrying the transport stream for the required
         * service
         */
        private final int mOriginalNetworkId;

        public DvbData(int transportStreamId, int serviceId, int originalNetworkId) {
            mTransportStreamId = transportStreamId;
            mServiceId = serviceId;
            mOriginalNetworkId = originalNetworkId;
        }

        @Override
        public int toByteArray(byte[] data, int index) {
            return threeFieldsToSixBytes(mTransportStreamId, mServiceId, mOriginalNetworkId, data,
                    index);
        }
    }

    /**
     * Identifies a 1-part Logical or Virtual Channel Number or a 2-part Major and Minor channel
     * combination.
     */
    private static final class ChannelIdentifier {
        /** Identifies Channel Format */
        private final int mChannelNumberFormat;
        /**
         * Major Channel Number (if Channel Number Format is 2-part). If format is
         * CHANNEL_NUMBER_FORMAT_1_PART, this will be ignored(0).
         */
        private final int mMajorChannelNumber;
        /**
         * 1-part Channel Number, or a Minor Channel Number (if Channel Number Format is 2-part).
         */
        private final int mMinorChannelNumber;

        private ChannelIdentifier(int format, int majorNumber, int minorNumer) {
            mChannelNumberFormat = format;
            mMajorChannelNumber = majorNumber;
            mMinorChannelNumber = minorNumer;
        }

        private int toByteArray(byte[] data, int index) {
            // The first 6 bits for format, the 10 bits for major number.
            data[index] = (byte) (((mChannelNumberFormat << 2) | (mMajorChannelNumber >>> 8) & 0x3));
            data[index + 1] = (byte) (mMajorChannelNumber & 0xFF);
            // Minor number uses the next 16 bits.
            shortToByteArray((short) mMinorChannelNumber, data, index + 2);
            return 4;
        }
    }

    /**
     * Digital channel id.
     * <p>
     * It consists of the following fields
     * <ul>
     * <li>channel number format: 6bits
     * <li>major number: 10bits
     * <li>minor number: 16bits
     * <li>reserved: 2bytes
     * </ul>
     * @hide
     */
    public static final class DigitalChannelData implements DigitalServiceIdentification {
        /** Identifies the logical or virtual channel number of a service. */
        private ChannelIdentifier mChannelIdentifier;

        public static DigitalChannelData ofTwoNumbers(int majorNumber, int minorNumber) {
            return new DigitalChannelData(
                    new ChannelIdentifier(CHANNEL_NUMBER_FORMAT_2_PART, majorNumber, minorNumber));
        }

        public static DigitalChannelData ofOneNumber(int number) {
            return new DigitalChannelData(
                    new ChannelIdentifier(CHANNEL_NUMBER_FORMAT_1_PART, 0, number));
        }

        private DigitalChannelData(ChannelIdentifier id) {
            mChannelIdentifier = id;
        }

        @Override
        public int toByteArray(byte[] data, int index) {
            mChannelIdentifier.toByteArray(data, index);
            // The last 2 bytes is reserved for future use.
            data[index + 4] = 0;
            data[index + 5] = 0;
            return 6;
        }
    }

    /**
     * Create {@link DigitalServiceSource} with channel type.
     *
     * @param broadcastSystem digital broadcast system. It should be one of
     *            <ul>
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_BS}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_CS}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_T}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_CABLE}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_SATELLITE}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_TERRESTRIAL}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_C}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_S}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_S2}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_T}
     *            </ul>
     * @hide
     */
    public static DigitalServiceSource ofDigitalChannelId(int broadcastSystem,
            DigitalChannelData data) {
        if (data == null) {
            throw new IllegalArgumentException("data should not be null.");
        }
        switch (broadcastSystem) {
            case DIGITAL_BROADCAST_TYPE_ARIB:
            case DIGITAL_BROADCAST_TYPE_ATSC:
            case DIGITAL_BROADCAST_TYPE_DVB:
            case DIGITAL_BROADCAST_TYPE_ARIB_BS:
            case DIGITAL_BROADCAST_TYPE_ARIB_CS:
            case DIGITAL_BROADCAST_TYPE_ARIB_T:
            case DIGITAL_BROADCAST_TYPE_ATSC_CABLE:
            case DIGITAL_BROADCAST_TYPE_ATSC_SATELLITE:
            case DIGITAL_BROADCAST_TYPE_ATSC_TERRESTRIAL:
            case DIGITAL_BROADCAST_TYPE_DVB_C:
            case DIGITAL_BROADCAST_TYPE_DVB_S:
            case DIGITAL_BROADCAST_TYPE_DVB_S2:
            case DIGITAL_BROADCAST_TYPE_DVB_T:
                return new DigitalServiceSource(
                        DigitalServiceSource.DIGITAL_SERVICE_IDENTIFIED_BY_CHANNEL,
                        broadcastSystem,
                        data);
            default:
                Log.w(TAG, "Invalid broadcast type:" + broadcastSystem);
                throw new IllegalArgumentException(
                        "Invalid broadcast system value:" + broadcastSystem);
        }
    }

    /**
     * Create {@link DigitalServiceSource} of ARIB type.
     *
     * @param aribType ARIB type. It should be one of
     *            <ul>
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_BS}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_CS}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ARIB_T}
     *            </ul>
     * @hide
     */
    @Nullable
    public static DigitalServiceSource ofArib(int aribType, AribData data) {
        if (data == null) {
            throw new IllegalArgumentException("data should not be null.");
        }
        switch (aribType) {
            case DIGITAL_BROADCAST_TYPE_ARIB:
            case DIGITAL_BROADCAST_TYPE_ARIB_BS:
            case DIGITAL_BROADCAST_TYPE_ARIB_CS:
            case DIGITAL_BROADCAST_TYPE_ARIB_T:
                return new DigitalServiceSource(
                        DigitalServiceSource.DIGITAL_SERVICE_IDENTIFIED_BY_DIGITAL_ID,
                        aribType, data);
            default:
                Log.w(TAG, "Invalid ARIB type:" + aribType);
                throw new IllegalArgumentException("type should not be null.");
        }
    }

    /**
     * Create {@link DigitalServiceSource} of ATSC type.
     *
     * @param atscType ATSC type. It should be one of
     *            <ul>
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_CABLE}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_SATELLITE}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_ATSC_TERRESTRIAL}
     *            </ul>
     * @hide
     */
    @Nullable
    public static DigitalServiceSource ofAtsc(int atscType, AtscData data) {
        if (data == null) {
            throw new IllegalArgumentException("data should not be null.");
        }
        switch (atscType) {
            case DIGITAL_BROADCAST_TYPE_ATSC:
            case DIGITAL_BROADCAST_TYPE_ATSC_CABLE:
            case DIGITAL_BROADCAST_TYPE_ATSC_SATELLITE:
            case DIGITAL_BROADCAST_TYPE_ATSC_TERRESTRIAL:
                return new DigitalServiceSource(
                        DigitalServiceSource.DIGITAL_SERVICE_IDENTIFIED_BY_DIGITAL_ID,
                        atscType, data);
            default:
                Log.w(TAG, "Invalid ATSC type:" + atscType);
                throw new IllegalArgumentException("Invalid ATSC type:" + atscType);
        }
    }

    /**
     * Create {@link DigitalServiceSource} of ATSC type.
     *
     * @param dvbType DVB type. It should be one of
     *            <ul>
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_C}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_S}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_S2}
     *            <li>{@link #DIGITAL_BROADCAST_TYPE_DVB_T}
     *            </ul>
     * @hide
     */
    @Nullable
    public static DigitalServiceSource ofDvb(int dvbType, DvbData data) {
        if (data == null) {
            throw new IllegalArgumentException("data should not be null.");
        }
        switch (dvbType) {
            case DIGITAL_BROADCAST_TYPE_DVB:
            case DIGITAL_BROADCAST_TYPE_DVB_C:
            case DIGITAL_BROADCAST_TYPE_DVB_S:
            case DIGITAL_BROADCAST_TYPE_DVB_S2:
            case DIGITAL_BROADCAST_TYPE_DVB_T:
                return new DigitalServiceSource(
                        DigitalServiceSource.DIGITAL_SERVICE_IDENTIFIED_BY_DIGITAL_ID,
                        dvbType, data);
            default:
                Log.w(TAG, "Invalid DVB type:" + dvbType);
                throw new IllegalArgumentException("Invalid DVB type:" + dvbType);
        }
    }

    /**
     * Record source container for "Digital Service".
     * <ul>
     * <li>[Record Source Type] - 1 byte
     * <li>[Digital Identification] - 7 bytes
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class DigitalServiceSource extends RecordSource {
        /** Indicates that a service is identified by digital service IDs. */
        private static final int DIGITAL_SERVICE_IDENTIFIED_BY_DIGITAL_ID = 0;
        /** Indicates that a service is identified by a logical or virtual channel number. */
        private static final int DIGITAL_SERVICE_IDENTIFIED_BY_CHANNEL = 1;

        static final int EXTRA_DATA_SIZE = 7;

        /**
         * Type of identification. It should be one of DIGITAL_SERVICE_IDENTIFIED_BY_DIGITAL_ID and
         * DIGITAL_SERVICE_IDENTIFIED_BY_CHANNEL
         */
        private final int mIdentificationMethod;
        /**
         * Indicates the Digital Broadcast System of required service. This is present irrespective
         * of the state of [Service Identification Method].
         */
        private final int mBroadcastSystem;

        /**
         * Extra parameter for digital service identification.
         */
        private final DigitalServiceIdentification mIdentification;

        private DigitalServiceSource(int identificatinoMethod, int broadcastSystem,
                DigitalServiceIdentification identification) {
            super(RECORD_SOURCE_TYPE_DIGITAL_SERVICE, EXTRA_DATA_SIZE);
            mIdentificationMethod = identificatinoMethod;
            mBroadcastSystem = broadcastSystem;
            mIdentification = identification;
        }

        @Override
        int extraParamToByteArray(byte[] data, int index) {
            data[index] = (byte) ((mIdentificationMethod << 7) | (mBroadcastSystem & 0x7F));
            mIdentification.toByteArray(data, index + 1);
            return EXTRA_DATA_SIZE;

        }
    }


    // ---------------------------------------------------------------------------------------------
    // ---- Analogue service data ------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    /**
     * Analogue broadcast types.
     */
    /** @hide */
    public static final int ANALOGUE_BROADCAST_TYPE_CABLE = 0x0;
    /** @hide */
    public static final int ANALOGUE_BROADCAST_TYPE_SATELLITE = 0x1;
    /** @hide */
    public static final int ANALOGUE_BROADCAST_TYPE_TERRESTRIAL = 0x2;

    /**
     * Broadcast system values.
     */
    /** @hide */
    public static final int BROADCAST_SYSTEM_PAL_BG = 0;
    /** @hide */
    public static final int BROADCAST_SYSTEM_SECAM_LP = 1;
    /** @hide */
    public static final int BROADCAST_SYSTEM_PAL_M = 2;
    /** @hide */
    public static final int BROADCAST_SYSTEM_NTSC_M = 3;
    /** @hide */
    public static final int BROADCAST_SYSTEM_PAL_I = 4;
    /** @hide */
    public static final int BROADCAST_SYSTEM_SECAM_DK = 5;
    /** @hide */
    public static final int BROADCAST_SYSTEM_SECAM_BG = 6;
    /** @hide */
    public static final int BROADCAST_SYSTEM_SECAM_L = 7;
    /** @hide */
    public static final int BROADCAST_SYSTEM_PAL_DK = 8;
    /** @hide */
    public static final int BROADCAST_SYSTEM_PAL_OTHER_SYSTEM = 31;

    /**
     * Create {@link AnalogueServiceSource} of analogue service.
     *
     * @param broadcastType
     * @param frequency
     * @param broadcastSystem
     * @hide
     */
    @Nullable
    public static AnalogueServiceSource ofAnalogue(int broadcastType, int frequency,
            int broadcastSystem){
        if (broadcastType < ANALOGUE_BROADCAST_TYPE_CABLE
                || broadcastType > ANALOGUE_BROADCAST_TYPE_TERRESTRIAL) {
            Log.w(TAG, "Invalid Broadcast type:" + broadcastType);
            throw new IllegalArgumentException("Invalid Broadcast type:" + broadcastType);
        }
        if (frequency < 0 || frequency > 0xFFFF) {
            Log.w(TAG, "Invalid frequency value[0x0000-0xFFFF]:" + frequency);
            throw new IllegalArgumentException(
                    "Invalid frequency value[0x0000-0xFFFF]:" + frequency);
        }
        if (broadcastSystem < BROADCAST_SYSTEM_PAL_BG
                || broadcastSystem > BROADCAST_SYSTEM_PAL_OTHER_SYSTEM) {

            Log.w(TAG, "Invalid Broadcast system:" + broadcastSystem);
            throw new IllegalArgumentException(
                    "Invalid Broadcast system:" + broadcastSystem);
        }

        return new AnalogueServiceSource(broadcastType, frequency, broadcastSystem);
    }

    /**
     * Record source for analogue service data. It consists of
     * <ul>
     * <li>[Record Source Type] - 1 byte
     * <li>[Analogue Broadcast Type] - 1 byte
     * <li>[Analogue Frequency] - 2 bytes
     * <li>[Broadcast System] - 1 byte
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class AnalogueServiceSource extends RecordSource {
        static final int EXTRA_DATA_SIZE = 4;

        /** Indicates the Analogue broadcast type. */
        private final int mBroadcastType;
        /** Used to specify the frequency used by an analogue tuner. 0x0000<N<0xFFFF. */
        private final int mFrequency;
        /**
         * This specifies information about the color system, the sound carrier and the
         * IF-frequency.
         */
        private final int mBroadcastSystem;

        private AnalogueServiceSource(int broadcastType, int frequency, int broadcastSystem) {
            super(RECORD_SOURCE_TYPE_ANALOGUE_SERVICE, EXTRA_DATA_SIZE);
            mBroadcastType = broadcastType;
            mFrequency = frequency;
            mBroadcastSystem = broadcastSystem;
        }

        @Override
        protected int extraParamToByteArray(byte[] data, int index) {
            // [Analogue Broadcast Type] - 1 byte
            data[index] = (byte) mBroadcastType;
            // [Analogue Frequency] - 2 bytes
            shortToByteArray((short) mFrequency, data, index + 1);
            // [Broadcast System] - 1 byte
            data[index + 3] = (byte) mBroadcastSystem;
            return EXTRA_DATA_SIZE;
        }
    }


    // ---------------------------------------------------------------------------------------------
    // ---- External plug data ---------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    /**
     * Create {@link ExternalPlugData} of external plug type.
     *
     * @param plugNumber plug number. It should be in range of [1, 255]
     * @hide
     */
    public static ExternalPlugData ofExternalPlug(int plugNumber) {
        if (plugNumber < 1 || plugNumber > 255) {
            Log.w(TAG, "Invalid plug number[1-255]" + plugNumber);
            throw new IllegalArgumentException("Invalid plug number[1-255]" + plugNumber);
        }
        return new ExternalPlugData(plugNumber);
    }

    /**
     * Record source for external plug (external non-HDMI device connect) type.
     * <ul>
     * <li>[Record Source Type] - 1 byte
     * <li>[External Plug] - 1 byte
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class ExternalPlugData extends RecordSource {
        static final int EXTRA_DATA_SIZE = 1;

        /** External Plug number on the Recording Device. */
        private final int mPlugNumber;

        private ExternalPlugData(int plugNumber) {
            super(RECORD_SOURCE_TYPE_EXTERNAL_PLUG, EXTRA_DATA_SIZE);
            mPlugNumber = plugNumber;
        }

        @Override
        int extraParamToByteArray(byte[] data, int index) {
            data[index] = (byte) mPlugNumber;
            return EXTRA_DATA_SIZE;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ---- External physical address --------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    /**
     * Create {@link ExternalPhysicalAddress} of external physical address.
     *
     * @param physicalAddress
     * @hide
     */
    public static ExternalPhysicalAddress ofExternalPhysicalAddress(int physicalAddress) {
        if ((physicalAddress & ~0xFFFF) != 0) {
            Log.w(TAG, "Invalid physical address:" + physicalAddress);
            throw new IllegalArgumentException("Invalid physical address:" + physicalAddress);
        }

        return new ExternalPhysicalAddress(physicalAddress);
    }

    /**
     * Record source for external physical address.
     * <ul>
     * <li>[Record Source Type] - 1 byte
     * <li>[Physical address] - 2 byte
     * </ul>
     * @hide
     */
    @SystemApi
    public static final class ExternalPhysicalAddress extends RecordSource {
        static final int EXTRA_DATA_SIZE = 2;

        private final int mPhysicalAddress;

        private ExternalPhysicalAddress(int physicalAddress) {
            super(RECORD_SOURCE_TYPE_EXTERNAL_PHYSICAL_ADDRESS, EXTRA_DATA_SIZE);
            mPhysicalAddress = physicalAddress;
        }

        @Override
        int extraParamToByteArray(byte[] data, int index) {
            shortToByteArray((short) mPhysicalAddress, data, index);
            return EXTRA_DATA_SIZE;
        }
    }


    // ---------------------------------------------------------------------------------------------
    // ------- Helper methods ----------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
    private static int threeFieldsToSixBytes(int first, int second, int third, byte[] data,
            int index) {
        shortToByteArray((short) first, data, index);
        shortToByteArray((short) second, data, index + 2);
        shortToByteArray((short) third, data, index + 4);
        return 6;
    }

    private static int shortToByteArray(short value, byte[] byteArray, int index) {
        byteArray[index] = (byte) ((value >>> 8) & 0xFF);
        byteArray[index + 1] = (byte) (value & 0xFF);
        return 2;
    }

    /**
     * Check the byte array of record source.
     * @hide
     */
    @SystemApi
    public static boolean checkRecordSource(byte[] recordSource) {
        int recordSourceType = recordSource[0];
        int extraDataSize = recordSource.length - 1;
        switch (recordSourceType) {
            case RECORD_SOURCE_TYPE_OWN_SOURCE:
                return extraDataSize == OwnSource.EXTRA_DATA_SIZE;
            case RECORD_SOURCE_TYPE_DIGITAL_SERVICE:
                return extraDataSize == DigitalServiceSource.EXTRA_DATA_SIZE;
            case RECORD_SOURCE_TYPE_ANALOGUE_SERVICE:
                return extraDataSize == AnalogueServiceSource.EXTRA_DATA_SIZE;
            case RECORD_SOURCE_TYPE_EXTERNAL_PLUG:
                return extraDataSize == ExternalPlugData.EXTRA_DATA_SIZE;
            case RECORD_SOURCE_TYPE_EXTERNAL_PHYSICAL_ADDRESS:
                return extraDataSize == ExternalPhysicalAddress.EXTRA_DATA_SIZE;
            default:
                return false;
        }
    }
}
