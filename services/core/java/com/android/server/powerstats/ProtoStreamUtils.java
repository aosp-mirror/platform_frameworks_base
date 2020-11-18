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

import android.hardware.power.stats.ChannelInfo;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntityInfo;
import android.hardware.power.stats.StateResidencyResult;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import android.util.proto.WireTypeMismatchException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * ProtoStreamUtils provides helper functions for the PowerStats HAL objects returned from calls
 * to the PowerStats HAL APIs.  It provides functions to pack/unpack object arrays to/from protobuf
 * format.  These helper functions are required since frameworks code uses the genstream option
 * when generating source code and therefore, getter/setter helper functions are not available.  The
 * protobufs need to be packed/unpacked in a more manual way using
 * ProtoOutputStream/ProtoInputStream.  It also provides print() functions for debugging purposes.
 */
public class ProtoStreamUtils {
    private static final String TAG = ProtoStreamUtils.class.getSimpleName();

    static class PowerEntityInfoUtils {
        public static void print(PowerEntityInfo[] powerEntityInfo) {
            if (powerEntityInfo == null) return;

            for (int i = 0; i < powerEntityInfo.length; i++) {
                Slog.d(TAG, "PowerEntityId: " + powerEntityInfo[i].powerEntityId
                        + ", PowerEntityName: " + powerEntityInfo[i].powerEntityName);
                if (powerEntityInfo[i].states != null) {
                    for (int j = 0; j < powerEntityInfo[i].states.length; j++) {
                        Slog.d(TAG, "  StateId: " + powerEntityInfo[i].states[j].stateId
                                + ", StateName: " + powerEntityInfo[i].states[j].stateName);
                    }
                }
            }
        }

        public static void dumpsys(PowerEntityInfo[] powerEntityInfo, PrintWriter pw) {
            if (powerEntityInfo == null) return;

            for (int i = 0; i < powerEntityInfo.length; i++) {
                pw.println("PowerEntityId: " + powerEntityInfo[i].powerEntityId
                        + ", PowerEntityName: " + powerEntityInfo[i].powerEntityName);
                if (powerEntityInfo[i].states != null) {
                    for (int j = 0; j < powerEntityInfo[i].states.length; j++) {
                        pw.println("  StateId: " + powerEntityInfo[i].states[j].stateId
                                + ", StateName: " + powerEntityInfo[i].states[j].stateName);
                    }
                }
            }
        }
    }

    static class StateResidencyResultUtils {
        public static void print(StateResidencyResult[] stateResidencyResult) {
            if (stateResidencyResult == null) return;

            for (int i = 0; i < stateResidencyResult.length; i++) {
                Slog.d(TAG, "PowerEntityId: " + stateResidencyResult[i].powerEntityId);
                for (int j = 0; j < stateResidencyResult[i].stateResidencyData.length; j++) {
                    Slog.d(TAG, "  StateId: "
                            + stateResidencyResult[i].stateResidencyData[j].stateId
                            + ", TotalTimeInStateMs: "
                            + stateResidencyResult[i].stateResidencyData[j].totalTimeInStateMs
                            + ", TotalStateEntryCount: "
                            + stateResidencyResult[i].stateResidencyData[j].totalStateEntryCount
                            + ", LastEntryTimestampMs: "
                            + stateResidencyResult[i].stateResidencyData[j].lastEntryTimestampMs);
                }
            }
        }
    }

    static class ChannelInfoUtils {
        public static void packProtoMessage(ChannelInfo[] channelInfo, ProtoOutputStream pos) {
            long token;

            if (channelInfo == null) return;

            for (int i = 0; i < channelInfo.length; i++) {
                token = pos.start(PowerStatsServiceMeterProto.CHANNEL_INFO);
                pos.write(ChannelInfoProto.CHANNEL_ID, channelInfo[i].channelId);
                pos.write(ChannelInfoProto.CHANNEL_NAME, channelInfo[i].channelName);
                pos.end(token);
            }

        }

        public static void print(ChannelInfo[] channelInfo) {
            if (channelInfo == null) return;

            for (int i = 0; i < channelInfo.length; i++) {
                Slog.d(TAG, "ChannelId: " + channelInfo[i].channelId
                        + ", ChannelName: " + channelInfo[i].channelName);
            }
        }

        public static void dumpsys(ChannelInfo[] channelInfo, PrintWriter pw) {
            if (channelInfo == null) return;

            for (int i = 0; i < channelInfo.length; i++) {
                pw.println("ChannelId: " + channelInfo[i].channelId
                        + ", ChannelName: " + channelInfo[i].channelName);
            }
        }
    }

    static class EnergyMeasurementUtils {
        public static byte[] getProtoBytes(EnergyMeasurement[] energyMeasurement) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(energyMeasurement, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(EnergyMeasurement[] energyMeasurement,
                ProtoOutputStream pos) {
            long token;

            if (energyMeasurement == null) return;

            for (int i = 0; i < energyMeasurement.length; i++) {
                token = pos.start(PowerStatsServiceMeterProto.ENERGY_MEASUREMENT);
                pos.write(EnergyMeasurementProto.CHANNEL_ID, energyMeasurement[i].channelId);
                pos.write(EnergyMeasurementProto.TIMESTAMP_MS, energyMeasurement[i].timestampMs);
                pos.write(EnergyMeasurementProto.ENERGY_UWS, energyMeasurement[i].energyUWs);
                pos.end(token);
            }
        }

        public static EnergyMeasurement[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<EnergyMeasurement> energyMeasurementList = new ArrayList<EnergyMeasurement>();
            long token;

            while (true) {
                try {
                    int nextField = pis.nextField();
                    EnergyMeasurement energyMeasurement = new EnergyMeasurement();

                    if (nextField == (int) PowerStatsServiceMeterProto.ENERGY_MEASUREMENT) {
                        token = pis.start(PowerStatsServiceMeterProto.ENERGY_MEASUREMENT);
                        energyMeasurementList.add(unpackProtoMessage(pis));
                        pis.end(token);
                    } else if (nextField == ProtoInputStream.NO_MORE_FIELDS) {
                        return energyMeasurementList.toArray(
                            new EnergyMeasurement[energyMeasurementList.size()]);
                    } else {
                        Slog.e(TAG, "Unhandled field in proto: "
                                + ProtoUtils.currentFieldToString(pis));
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in proto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        private static EnergyMeasurement unpackProtoMessage(ProtoInputStream pis)
                throws IOException {
            EnergyMeasurement energyMeasurement = new EnergyMeasurement();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyMeasurementProto.CHANNEL_ID:
                            energyMeasurement.channelId =
                                pis.readInt(EnergyMeasurementProto.CHANNEL_ID);
                            break;

                        case (int) EnergyMeasurementProto.TIMESTAMP_MS:
                            energyMeasurement.timestampMs =
                                pis.readLong(EnergyMeasurementProto.TIMESTAMP_MS);
                            break;

                        case (int) EnergyMeasurementProto.ENERGY_UWS:
                            energyMeasurement.energyUWs =
                                pis.readLong(EnergyMeasurementProto.ENERGY_UWS);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return energyMeasurement;

                        default:
                            Slog.e(TAG, "Unhandled field in EnergyMeasurementProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in EnergyMeasurementProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        public static void print(EnergyMeasurement[] energyMeasurement) {
            if (energyMeasurement == null) return;

            for (int i = 0; i < energyMeasurement.length; i++) {
                Slog.d(TAG, "ChannelId: " + energyMeasurement[i].channelId
                        + ", Timestamp (ms): " + energyMeasurement[i].timestampMs
                        + ", Energy (uWs): " + energyMeasurement[i].energyUWs);
            }
        }
    }

    static class EnergyConsumerIdUtils {
        public static void packProtoMessage(int[] energyConsumerId, ProtoOutputStream pos) {
            long token;

            if (energyConsumerId == null) return;

            for (int i = 0; i < energyConsumerId.length; i++) {
                token = pos.start(PowerStatsServiceModelProto.ENERGY_CONSUMER_ID);
                pos.write(EnergyConsumerIdProto.ENERGY_CONSUMER_ID, energyConsumerId[i]);
                pos.end(token);
            }
        }

        public static void print(int[] energyConsumerId) {
            if (energyConsumerId == null) return;

            for (int i = 0; i < energyConsumerId.length; i++) {
                Slog.d(TAG, "EnergyConsumerId: " + energyConsumerId[i]);
            }
        }

        public static void dumpsys(int[] energyConsumerId, PrintWriter pw) {
            if (energyConsumerId == null) return;

            for (int i = 0; i < energyConsumerId.length; i++) {
                pw.println("EnergyConsumerId: " + energyConsumerId[i]);
            }
        }
    }

    static class EnergyConsumerResultUtils {
        public static byte[] getProtoBytes(EnergyConsumerResult[] energyConsumerResult) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(energyConsumerResult, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(EnergyConsumerResult[] energyConsumerResult,
                ProtoOutputStream pos) {
            long token;

            if (energyConsumerResult == null) return;

            for (int i = 0; i < energyConsumerResult.length; i++) {
                token = pos.start(PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT);
                pos.write(EnergyConsumerResultProto.ENERGY_CONSUMER_ID,
                        energyConsumerResult[i].energyConsumerId);
                pos.write(EnergyConsumerResultProto.TIMESTAMP_MS,
                        energyConsumerResult[i].timestampMs);
                pos.write(EnergyConsumerResultProto.ENERGY_UWS, energyConsumerResult[i].energyUWs);
                pos.end(token);
            }
        }

        public static EnergyConsumerResult[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<EnergyConsumerResult> energyConsumerResultList =
                    new ArrayList<EnergyConsumerResult>();
            long token;

            while (true) {
                try {
                    int nextField = pis.nextField();
                    EnergyConsumerResult energyConsumerResult = new EnergyConsumerResult();

                    if (nextField == (int) PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT) {
                        token = pis.start(PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT);
                        energyConsumerResultList.add(unpackProtoMessage(pis));
                        pis.end(token);
                    } else if (nextField == ProtoInputStream.NO_MORE_FIELDS) {
                        return energyConsumerResultList.toArray(
                            new EnergyConsumerResult[energyConsumerResultList.size()]);
                    } else {
                        Slog.e(TAG, "Unhandled field in proto: "
                                + ProtoUtils.currentFieldToString(pis));
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in proto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        private static EnergyConsumerResult unpackProtoMessage(ProtoInputStream pis)
                throws IOException {
            EnergyConsumerResult energyConsumerResult = new EnergyConsumerResult();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyConsumerResultProto.ENERGY_CONSUMER_ID:
                            energyConsumerResult.energyConsumerId =
                                pis.readInt(EnergyConsumerResultProto.ENERGY_CONSUMER_ID);
                            break;

                        case (int) EnergyConsumerResultProto.TIMESTAMP_MS:
                            energyConsumerResult.timestampMs =
                                pis.readLong(EnergyConsumerResultProto.TIMESTAMP_MS);
                            break;

                        case (int) EnergyConsumerResultProto.ENERGY_UWS:
                            energyConsumerResult.energyUWs =
                                pis.readLong(EnergyConsumerResultProto.ENERGY_UWS);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return energyConsumerResult;

                        default:
                            Slog.e(TAG, "Unhandled field in EnergyConsumerResultProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in EnergyConsumerResultProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        public static void print(EnergyConsumerResult[] energyConsumerResult) {
            if (energyConsumerResult == null) return;

            for (int i = 0; i < energyConsumerResult.length; i++) {
                Slog.d(TAG, "EnergyConsumerId: " + energyConsumerResult[i].energyConsumerId
                        + ", Timestamp (ms): " + energyConsumerResult[i].timestampMs
                        + ", Energy (uWs): " + energyConsumerResult[i].energyUWs);
            }
        }
    }
}
