/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import android.util.Log;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import android.util.proto.WireTypeMismatchException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PowerStatsData is a class that performs two operations:
 * 1) Unpacks serialized protobuf byte arrays, as defined in powerstatsservice.proto,
 *    into RailInfo or EnergyData object arrays.
 *
 * 2) Packs RailInfo or EnergyData object arrays in protobuf byte arrays as
 *    defined in powerstatsservice.proto.
 *
 * Inside frameworks, proto source is generated with the genstream option
 * and therefore the getter/setter helper functions are not available.
 * The protos need to be packed/unpacked in a more manual way using
 * ProtoOutputStream/ProtoInputStream.
 */
public class PowerStatsData {
    private static final String TAG = PowerStatsData.class.getSimpleName();

    private List<Data> mDataList;

    public PowerStatsData(ProtoInputStream pis) throws IOException {
        mDataList = new ArrayList<Data>();
        unpackProto(pis);
    }

    public PowerStatsData(Data[] data) {
        mDataList = new ArrayList<Data>(Arrays.asList(data));
    }

    private void unpackProto(ProtoInputStream pis) throws IOException {
        long token;

        while (true) {
            try {
                switch (pis.nextField()) {
                    case (int) PowerStatsServiceProto.RAIL_INFO:
                        token = pis.start(PowerStatsServiceProto.RAIL_INFO);
                        mDataList.add(new RailInfo(pis));
                        pis.end(token);
                        break;

                    case (int) PowerStatsServiceProto.ENERGY_DATA:
                        token = pis.start(PowerStatsServiceProto.ENERGY_DATA);
                        mDataList.add(new EnergyData(pis));
                        pis.end(token);
                        break;

                    case ProtoInputStream.NO_MORE_FIELDS:
                        return;

                    default:
                        Log.e(TAG, "Unhandled field in proto: "
                                + ProtoUtils.currentFieldToString(pis));
                        break;
                }
            } catch (WireTypeMismatchException wtme) {
                Log.e(TAG, "Wire Type mismatch in proto: " + ProtoUtils.currentFieldToString(pis));
            }
        }
    }

    /**
     * Write this object to an output stream in protobuf format.
     *
     * @param pos ProtoOutputStream of file where data is to be written.  Data is
     *            written in protobuf format as defined by powerstatsservice.proto.
     */
    public void toProto(ProtoOutputStream pos) {
        long token;

        for (Data data : mDataList) {
            if (data instanceof RailInfo) {
                token = pos.start(PowerStatsServiceProto.RAIL_INFO);
            } else {
                token = pos.start(PowerStatsServiceProto.ENERGY_DATA);
            }
            data.toProto(pos);
            pos.end(token);
        }
    }

    /**
     * Convert mDataList to proto format and return the serialized byte array.
     *
     * @return byte array containing a serialized protobuf of mDataList.
     */
    public byte[] getProtoBytes() {
        ProtoOutputStream pos = new ProtoOutputStream();
        long token;

        for (Data data : mDataList) {
            if (data instanceof RailInfo) {
                token = pos.start(PowerStatsServiceProto.RAIL_INFO);
            } else {
                token = pos.start(PowerStatsServiceProto.ENERGY_DATA);
            }
            data.toProto(pos);
            pos.end(token);
        }
        return pos.getBytes();
    }

    /**
     * Print this object to logcat.
     */
    public void print() {
        for (Data data : mDataList) {
            Log.d(TAG, data.toString());
        }
    }

    /**
     * RailInfo is a class that stores a description for an individual ODPM
     * rail.  It provides functionality to unpack a RailInfo object from a
     * serialized protobuf byte array, and to pack a RailInfo object into
     * a ProtoOutputStream.
     */
    public static class RailInfo extends Data {
        public String mRailName;
        public String mSubSysName;
        public long mSamplingRate;

        public RailInfo(ProtoInputStream pis) throws IOException {
            unpackProto(pis);
        }

        public RailInfo(long index, String railName, String subSysName, long samplingRate) {
            mIndex = index;
            mRailName = railName;
            mSubSysName = subSysName;
            mSamplingRate = samplingRate;
        }

        @Override
        protected void unpackProto(ProtoInputStream pis) throws IOException {
            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) RailInfoProto.INDEX:
                            mIndex = pis.readInt(RailInfoProto.INDEX);
                            break;

                        case (int) RailInfoProto.RAIL_NAME:
                            mRailName = pis.readString(RailInfoProto.RAIL_NAME);
                            break;

                        case (int) RailInfoProto.SUBSYS_NAME:
                            mSubSysName = pis.readString(RailInfoProto.SUBSYS_NAME);
                            break;

                        case (int) RailInfoProto.SAMPLING_RATE:
                            mSamplingRate = pis.readInt(RailInfoProto.SAMPLING_RATE);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return;

                        default:
                            Log.e(TAG, "Unhandled field in RailInfoProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;
                    }
                } catch (WireTypeMismatchException wtme) {
                    Log.e(TAG, "Wire Type mismatch in RailInfoProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        @Override
        public void toProto(ProtoOutputStream pos) {
            pos.write(RailInfoProto.INDEX, mIndex);
            pos.write(RailInfoProto.RAIL_NAME, mRailName);
            pos.write(RailInfoProto.SUBSYS_NAME, mSubSysName);
            pos.write(RailInfoProto.SAMPLING_RATE, mSamplingRate);
        }

        @Override
        public String toString() {
            return String.format("Index = " + mIndex
                + ", RailName = " + mRailName
                + ", SubSysName = " + mSubSysName
                + ", SamplingRate = " + mSamplingRate);
        }
    }

    /**
     * EnergyData is a class that stores an energy (uWs) data reading for an
     * individual ODPM rail.  It provides functionality to unpack an EnergyData
     * object from a serialized protobuf byte array, and to pack an EnergyData
     * object into a ProtoOutputStream.
     */
    public static class EnergyData extends Data {
        public long mTimestampMs;
        public long mEnergyUWs;

        public EnergyData(ProtoInputStream pis) throws IOException {
            unpackProto(pis);
        }

        public EnergyData(long index, long timestampMs, long energyUWs) {
            mIndex = index;
            mTimestampMs = timestampMs;
            mEnergyUWs = energyUWs;
        }

        @Override
        protected void unpackProto(ProtoInputStream pis) throws IOException {
            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyDataProto.INDEX:
                            mIndex = pis.readInt(EnergyDataProto.INDEX);
                            break;

                        case (int) EnergyDataProto.TIMESTAMP_MS:
                            mTimestampMs = pis.readLong(EnergyDataProto.TIMESTAMP_MS);
                            break;

                        case (int) EnergyDataProto.ENERGY_UWS:
                            mEnergyUWs = pis.readLong(EnergyDataProto.ENERGY_UWS);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return;

                        default:
                            Log.e(TAG, "Unhandled field in EnergyDataProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;
                    }
                } catch (WireTypeMismatchException wtme) {
                    Log.e(TAG, "Wire Type mismatch in EnergyDataProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        @Override
        protected void toProto(ProtoOutputStream pos) {
            pos.write(EnergyDataProto.INDEX, mIndex);
            pos.write(EnergyDataProto.TIMESTAMP_MS, mTimestampMs);
            pos.write(EnergyDataProto.ENERGY_UWS, mEnergyUWs);
        }

        @Override
        public String toString() {
            return String.format("Index = " + mIndex
                + ", Timestamp (ms) = " + mTimestampMs
                + ", Energy (uWs) = " + mEnergyUWs);
        }
    }

    private abstract static class Data {
        public long mIndex;
        protected abstract void unpackProto(ProtoInputStream pis) throws IOException;
        protected abstract void toProto(ProtoOutputStream pos);
    }
}
