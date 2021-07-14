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

import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.State;
import android.hardware.power.stats.StateResidency;
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

    static class PowerEntityUtils {
        public static byte[] getProtoBytes(PowerEntity[] powerEntity) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(powerEntity, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(PowerEntity[] powerEntity,
                ProtoOutputStream pos) {
            if (powerEntity == null) return;

            for (int i = 0; i < powerEntity.length; i++) {
                long peToken = pos.start(PowerStatsServiceResidencyProto.POWER_ENTITY);
                pos.write(PowerEntityProto.ID, powerEntity[i].id);
                pos.write(PowerEntityProto.NAME, powerEntity[i].name);
                if (powerEntity[i].states != null) {
                    final int statesLength = powerEntity[i].states.length;
                    for (int j = 0; j < statesLength; j++) {
                        final State state = powerEntity[i].states[j];
                        long stateToken = pos.start(PowerEntityProto.STATES);
                        pos.write(StateProto.ID, state.id);
                        pos.write(StateProto.NAME, state.name);
                        pos.end(stateToken);
                    }
                }
                pos.end(peToken);
            }
        }

        public static void print(PowerEntity[] powerEntity) {
            if (powerEntity == null) return;

            for (int i = 0; i < powerEntity.length; i++) {
                Slog.d(TAG, "powerEntityId: " + powerEntity[i].id
                        + ", powerEntityName: " + powerEntity[i].name);
                if (powerEntity[i].states != null) {
                    for (int j = 0; j < powerEntity[i].states.length; j++) {
                        Slog.d(TAG, "  StateId: " + powerEntity[i].states[j].id
                                + ", StateName: " + powerEntity[i].states[j].name);
                    }
                }
            }
        }

        public static void dumpsys(PowerEntity[] powerEntity, PrintWriter pw) {
            if (powerEntity == null) return;

            for (int i = 0; i < powerEntity.length; i++) {
                pw.println("PowerEntityId: " + powerEntity[i].id
                        + ", PowerEntityName: " + powerEntity[i].name);
                if (powerEntity[i].states != null) {
                    for (int j = 0; j < powerEntity[i].states.length; j++) {
                        pw.println("  StateId: " + powerEntity[i].states[j].id
                                + ", StateName: " + powerEntity[i].states[j].name);
                    }
                }
            }
        }
    }

    static class StateResidencyResultUtils {
        public static void adjustTimeSinceBootToEpoch(StateResidencyResult[] stateResidencyResult,
                long startWallTime) {
            if (stateResidencyResult == null) return;

            for (int i = 0; i < stateResidencyResult.length; i++) {
                final int stateLength = stateResidencyResult[i].stateResidencyData.length;
                for (int j = 0; j < stateLength; j++) {
                    final StateResidency stateResidencyData =
                            stateResidencyResult[i].stateResidencyData[j];
                    stateResidencyData.lastEntryTimestampMs += startWallTime;
                }
            }
        }

        public static byte[] getProtoBytes(StateResidencyResult[] stateResidencyResult) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(stateResidencyResult, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(StateResidencyResult[] stateResidencyResult,
                ProtoOutputStream pos) {
            if (stateResidencyResult == null) return;

            for (int i = 0; i < stateResidencyResult.length; i++) {
                final int stateLength = stateResidencyResult[i].stateResidencyData.length;
                long srrToken = pos.start(PowerStatsServiceResidencyProto.STATE_RESIDENCY_RESULT);
                pos.write(StateResidencyResultProto.ID,
                        stateResidencyResult[i].id);
                for (int j = 0; j < stateLength; j++) {
                    final StateResidency stateResidencyData =
                            stateResidencyResult[i].stateResidencyData[j];
                    long srdToken = pos.start(StateResidencyResultProto.STATE_RESIDENCY_DATA);
                    pos.write(StateResidencyProto.ID, stateResidencyData.id);
                    pos.write(StateResidencyProto.TOTAL_TIME_IN_STATE_MS,
                            stateResidencyData.totalTimeInStateMs);
                    pos.write(StateResidencyProto.TOTAL_STATE_ENTRY_COUNT,
                            stateResidencyData.totalStateEntryCount);
                    pos.write(StateResidencyProto.LAST_ENTRY_TIMESTAMP_MS,
                            stateResidencyData.lastEntryTimestampMs);
                    pos.end(srdToken);
                }
                pos.end(srrToken);
            }
        }

        public static StateResidencyResult[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<StateResidencyResult> stateResidencyResultList =
                    new ArrayList<StateResidencyResult>();
            while (true) {
                try {
                    int nextField = pis.nextField();
                    StateResidencyResult stateResidencyResult = new StateResidencyResult();

                    if (nextField == (int) PowerStatsServiceResidencyProto.STATE_RESIDENCY_RESULT) {
                        long token =
                                pis.start(PowerStatsServiceResidencyProto.STATE_RESIDENCY_RESULT);
                        stateResidencyResultList.add(unpackStateResidencyResultProto(pis));
                        pis.end(token);
                    } else if (nextField == ProtoInputStream.NO_MORE_FIELDS) {
                        return stateResidencyResultList.toArray(
                            new StateResidencyResult[stateResidencyResultList.size()]);
                    } else {
                        Slog.e(TAG, "Unhandled field in PowerStatsServiceResidencyProto: "
                                + ProtoUtils.currentFieldToString(pis));
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in PowerStatsServiceResidencyProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        private static StateResidencyResult unpackStateResidencyResultProto(ProtoInputStream pis)
                throws IOException {
            StateResidencyResult stateResidencyResult = new StateResidencyResult();
            List<StateResidency> stateResidencyList = new ArrayList<StateResidency>();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) StateResidencyResultProto.ID:
                            stateResidencyResult.id = pis.readInt(StateResidencyResultProto.ID);
                            break;

                        case (int) StateResidencyResultProto.STATE_RESIDENCY_DATA:
                            long token = pis.start(StateResidencyResultProto.STATE_RESIDENCY_DATA);
                            stateResidencyList.add(unpackStateResidencyProto(pis));
                            pis.end(token);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            stateResidencyResult.stateResidencyData = stateResidencyList.toArray(
                                new StateResidency[stateResidencyList.size()]);
                            return stateResidencyResult;

                        default:
                            Slog.e(TAG, "Unhandled field in StateResidencyResultProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;
                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in StateResidencyResultProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        private static StateResidency unpackStateResidencyProto(ProtoInputStream pis)
                throws IOException {
            StateResidency stateResidency = new StateResidency();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) StateResidencyProto.ID:
                            stateResidency.id = pis.readInt(StateResidencyProto.ID);
                            break;

                        case (int) StateResidencyProto.TOTAL_TIME_IN_STATE_MS:
                            stateResidency.totalTimeInStateMs =
                                pis.readLong(StateResidencyProto.TOTAL_TIME_IN_STATE_MS);
                            break;

                        case (int) StateResidencyProto.TOTAL_STATE_ENTRY_COUNT:
                            stateResidency.totalStateEntryCount =
                                pis.readLong(StateResidencyProto.TOTAL_STATE_ENTRY_COUNT);
                            break;

                        case (int) StateResidencyProto.LAST_ENTRY_TIMESTAMP_MS:
                            stateResidency.lastEntryTimestampMs =
                                pis.readLong(StateResidencyProto.LAST_ENTRY_TIMESTAMP_MS);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return stateResidency;

                        default:
                            Slog.e(TAG, "Unhandled field in StateResidencyProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;

                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in StateResidencyProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        public static void print(StateResidencyResult[] stateResidencyResult) {
            if (stateResidencyResult == null) return;

            for (int i = 0; i < stateResidencyResult.length; i++) {
                Slog.d(TAG, "PowerEntityId: " + stateResidencyResult[i].id);
                for (int j = 0; j < stateResidencyResult[i].stateResidencyData.length; j++) {
                    Slog.d(TAG, "  StateId: "
                            + stateResidencyResult[i].stateResidencyData[j].id
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

    static class ChannelUtils {
        public static byte[] getProtoBytes(Channel[] channel) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(channel, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(Channel[] channel, ProtoOutputStream pos) {
            if (channel == null) return;

            for (int i = 0; i < channel.length; i++) {
                long token = pos.start(PowerStatsServiceMeterProto.CHANNEL);
                pos.write(ChannelProto.ID, channel[i].id);
                pos.write(ChannelProto.NAME, channel[i].name);
                pos.write(ChannelProto.SUBSYSTEM, channel[i].subsystem);
                pos.end(token);
            }
        }

        public static void print(Channel[] channel) {
            if (channel == null) return;

            for (int i = 0; i < channel.length; i++) {
                Slog.d(TAG, "ChannelId: " + channel[i].id
                        + ", ChannelName: " + channel[i].name
                        + ", ChannelSubsystem: " + channel[i].subsystem);
            }
        }

        public static void dumpsys(Channel[] channel, PrintWriter pw) {
            if (channel == null) return;

            for (int i = 0; i < channel.length; i++) {
                pw.println("ChannelId: " + channel[i].id
                        + ", ChannelName: " + channel[i].name
                        + ", ChannelSubsystem: " + channel[i].subsystem);
            }
        }
    }

    static class EnergyMeasurementUtils {
        public static void adjustTimeSinceBootToEpoch(EnergyMeasurement[] energyMeasurement,
                long startWallTime) {
            if (energyMeasurement == null) return;

            for (int i = 0; i < energyMeasurement.length; i++) {
                energyMeasurement[i].timestampMs += startWallTime;
            }
        }

        public static byte[] getProtoBytes(EnergyMeasurement[] energyMeasurement) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(energyMeasurement, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(EnergyMeasurement[] energyMeasurement,
                ProtoOutputStream pos) {
            if (energyMeasurement == null) return;

            for (int i = 0; i < energyMeasurement.length; i++) {
                long token = pos.start(PowerStatsServiceMeterProto.ENERGY_MEASUREMENT);
                pos.write(EnergyMeasurementProto.ID, energyMeasurement[i].id);
                pos.write(EnergyMeasurementProto.TIMESTAMP_MS, energyMeasurement[i].timestampMs);
                pos.write(EnergyMeasurementProto.DURATION_MS, energyMeasurement[i].durationMs);
                pos.write(EnergyMeasurementProto.ENERGY_UWS, energyMeasurement[i].energyUWs);
                pos.end(token);
            }
        }

        public static EnergyMeasurement[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<EnergyMeasurement> energyMeasurementList = new ArrayList<EnergyMeasurement>();

            while (true) {
                try {
                    int nextField = pis.nextField();
                    EnergyMeasurement energyMeasurement = new EnergyMeasurement();

                    if (nextField == (int) PowerStatsServiceMeterProto.ENERGY_MEASUREMENT) {
                        long token = pis.start(PowerStatsServiceMeterProto.ENERGY_MEASUREMENT);
                        energyMeasurementList.add(unpackEnergyMeasurementProto(pis));
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

        private static EnergyMeasurement unpackEnergyMeasurementProto(ProtoInputStream pis)
                throws IOException {
            EnergyMeasurement energyMeasurement = new EnergyMeasurement();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyMeasurementProto.ID:
                            energyMeasurement.id =
                                pis.readInt(EnergyMeasurementProto.ID);
                            break;

                        case (int) EnergyMeasurementProto.TIMESTAMP_MS:
                            energyMeasurement.timestampMs =
                                pis.readLong(EnergyMeasurementProto.TIMESTAMP_MS);
                            break;

                        case (int) EnergyMeasurementProto.DURATION_MS:
                            energyMeasurement.durationMs =
                                pis.readLong(EnergyMeasurementProto.DURATION_MS);
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
                Slog.d(TAG, "ChannelId: " + energyMeasurement[i].id
                        + ", Timestamp (ms): " + energyMeasurement[i].timestampMs
                        + ", Duration (ms): " + energyMeasurement[i].durationMs
                        + ", Energy (uWs): " + energyMeasurement[i].energyUWs);
            }
        }
    }

    static class EnergyConsumerUtils {
        public static byte[] getProtoBytes(EnergyConsumer[] energyConsumer) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(energyConsumer, pos);
            return pos.getBytes();
        }

        public static void packProtoMessage(EnergyConsumer[] energyConsumer,
                ProtoOutputStream pos) {
            if (energyConsumer == null) return;

            for (int i = 0; i < energyConsumer.length; i++) {
                long token = pos.start(PowerStatsServiceModelProto.ENERGY_CONSUMER);
                pos.write(EnergyConsumerProto.ID, energyConsumer[i].id);
                pos.write(EnergyConsumerProto.ORDINAL, energyConsumer[i].ordinal);
                pos.write(EnergyConsumerProto.TYPE, energyConsumer[i].type);
                pos.write(EnergyConsumerProto.NAME, energyConsumer[i].name);
                pos.end(token);
            }
        }

        public static EnergyConsumer[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<EnergyConsumer> energyConsumerList = new ArrayList<EnergyConsumer>();

            while (true) {
                try {
                    int nextField = pis.nextField();
                    EnergyConsumer energyConsumer = new EnergyConsumer();

                    if (nextField == (int) PowerStatsServiceModelProto.ENERGY_CONSUMER) {
                        long token = pis.start(PowerStatsServiceModelProto.ENERGY_CONSUMER);
                        energyConsumerList.add(unpackEnergyConsumerProto(pis));
                        pis.end(token);
                    } else if (nextField == ProtoInputStream.NO_MORE_FIELDS) {
                        return energyConsumerList.toArray(
                            new EnergyConsumer[energyConsumerList.size()]);
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

        private static EnergyConsumer unpackEnergyConsumerProto(ProtoInputStream pis)
                throws IOException {
            final EnergyConsumer energyConsumer = new EnergyConsumer();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyConsumerProto.ID:
                            energyConsumer.id = pis.readInt(EnergyConsumerProto.ID);
                            break;

                        case (int) EnergyConsumerProto.ORDINAL:
                            energyConsumer.ordinal = pis.readInt(EnergyConsumerProto.ORDINAL);
                            break;

                        case (int) EnergyConsumerProto.TYPE:
                            energyConsumer.type = (byte) pis.readInt(EnergyConsumerProto.TYPE);
                            break;

                        case (int) EnergyConsumerProto.NAME:
                            energyConsumer.name = pis.readString(EnergyConsumerProto.NAME);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return energyConsumer;

                        default:
                            Slog.e(TAG, "Unhandled field in EnergyConsumerProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;

                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in EnergyConsumerProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        public static void print(EnergyConsumer[] energyConsumer) {
            if (energyConsumer == null) return;

            for (int i = 0; i < energyConsumer.length; i++) {
                Slog.d(TAG, "EnergyConsumerId: " + energyConsumer[i].id
                        + ", Ordinal: " + energyConsumer[i].ordinal
                        + ", Type: " + energyConsumer[i].type
                        + ", Name: " + energyConsumer[i].name);
            }
        }

        public static void dumpsys(EnergyConsumer[] energyConsumer, PrintWriter pw) {
            if (energyConsumer == null) return;

            for (int i = 0; i < energyConsumer.length; i++) {
                pw.println("EnergyConsumerId: " + energyConsumer[i].id
                        + ", Ordinal: " + energyConsumer[i].ordinal
                        + ", Type: " + energyConsumer[i].type
                        + ", Name: " + energyConsumer[i].name);
            }
        }
    }

    static class EnergyConsumerResultUtils {
        public static void adjustTimeSinceBootToEpoch(EnergyConsumerResult[] energyConsumerResult,
                long startWallTime) {
            if (energyConsumerResult == null) return;

            for (int i = 0; i < energyConsumerResult.length; i++) {
                energyConsumerResult[i].timestampMs += startWallTime;
            }
        }

        public static byte[] getProtoBytes(EnergyConsumerResult[] energyConsumerResult,
                boolean includeAttribution) {
            ProtoOutputStream pos = new ProtoOutputStream();
            packProtoMessage(energyConsumerResult, pos, includeAttribution);
            return pos.getBytes();
        }

        public static void packProtoMessage(EnergyConsumerResult[] energyConsumerResult,
                ProtoOutputStream pos, boolean includeAttribution) {
            if (energyConsumerResult == null) return;

            for (int i = 0; i < energyConsumerResult.length; i++) {
                long ecrToken = pos.start(PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT);
                pos.write(EnergyConsumerResultProto.ID, energyConsumerResult[i].id);
                pos.write(EnergyConsumerResultProto.TIMESTAMP_MS,
                        energyConsumerResult[i].timestampMs);
                pos.write(EnergyConsumerResultProto.ENERGY_UWS, energyConsumerResult[i].energyUWs);

                if (includeAttribution) {
                    final int attributionLength = energyConsumerResult[i].attribution.length;

                    for (int j = 0; j < attributionLength; j++) {
                        final EnergyConsumerAttribution energyConsumerAttribution =
                                energyConsumerResult[i].attribution[j];
                        final long ecaToken = pos.start(EnergyConsumerResultProto.ATTRIBUTION);
                        pos.write(EnergyConsumerAttributionProto.UID,
                                energyConsumerAttribution.uid);
                        pos.write(EnergyConsumerAttributionProto.ENERGY_UWS,
                                energyConsumerAttribution.energyUWs);
                        pos.end(ecaToken);
                    }
                }

                pos.end(ecrToken);
            }
        }

        public static EnergyConsumerResult[] unpackProtoMessage(byte[] data) throws IOException {
            final ProtoInputStream pis = new ProtoInputStream(new ByteArrayInputStream(data));
            List<EnergyConsumerResult> energyConsumerResultList =
                    new ArrayList<EnergyConsumerResult>();
            while (true) {
                try {
                    int nextField = pis.nextField();
                    EnergyConsumerResult energyConsumerResult = new EnergyConsumerResult();

                    if (nextField == (int) PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT) {
                        long token = pis.start(PowerStatsServiceModelProto.ENERGY_CONSUMER_RESULT);
                        energyConsumerResultList.add(unpackEnergyConsumerResultProto(pis));
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

        private static EnergyConsumerAttribution unpackEnergyConsumerAttributionProto(
                ProtoInputStream pis) throws IOException {
            final EnergyConsumerAttribution energyConsumerAttribution =
                    new EnergyConsumerAttribution();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyConsumerAttributionProto.UID:
                            energyConsumerAttribution.uid =
                                pis.readInt(EnergyConsumerAttributionProto.UID);
                            break;

                        case (int) EnergyConsumerAttributionProto.ENERGY_UWS:
                            energyConsumerAttribution.energyUWs =
                                pis.readLong(EnergyConsumerAttributionProto.ENERGY_UWS);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            return energyConsumerAttribution;

                        default:
                            Slog.e(TAG, "Unhandled field in EnergyConsumerAttributionProto: "
                                    + ProtoUtils.currentFieldToString(pis));
                            break;

                    }
                } catch (WireTypeMismatchException wtme) {
                    Slog.e(TAG, "Wire Type mismatch in EnergyConsumerAttributionProto: "
                            + ProtoUtils.currentFieldToString(pis));
                }
            }
        }

        private static EnergyConsumerResult unpackEnergyConsumerResultProto(ProtoInputStream pis)
                throws IOException {
            EnergyConsumerResult energyConsumerResult = new EnergyConsumerResult();
            final List<EnergyConsumerAttribution> energyConsumerAttributionList =
                    new ArrayList<EnergyConsumerAttribution>();

            while (true) {
                try {
                    switch (pis.nextField()) {
                        case (int) EnergyConsumerResultProto.ID:
                            energyConsumerResult.id = pis.readInt(EnergyConsumerResultProto.ID);
                            break;

                        case (int) EnergyConsumerResultProto.TIMESTAMP_MS:
                            energyConsumerResult.timestampMs =
                                pis.readLong(EnergyConsumerResultProto.TIMESTAMP_MS);
                            break;

                        case (int) EnergyConsumerResultProto.ENERGY_UWS:
                            energyConsumerResult.energyUWs =
                                pis.readLong(EnergyConsumerResultProto.ENERGY_UWS);
                            break;

                        case (int) EnergyConsumerResultProto.ATTRIBUTION:
                            final long token = pis.start(EnergyConsumerResultProto.ATTRIBUTION);
                            energyConsumerAttributionList.add(
                                    unpackEnergyConsumerAttributionProto(pis));
                            pis.end(token);
                            break;

                        case ProtoInputStream.NO_MORE_FIELDS:
                            energyConsumerResult.attribution =
                                energyConsumerAttributionList.toArray(
                                    new EnergyConsumerAttribution[
                                        energyConsumerAttributionList.size()]);
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
                final EnergyConsumerResult result = energyConsumerResult[i];
                Slog.d(TAG, "EnergyConsumerId: " + result.id
                        + ", Timestamp (ms): " + result.timestampMs
                        + ", Energy (uWs): " + result.energyUWs);
                final int attributionLength = result.attribution.length;
                for (int j = 0; j < attributionLength; j++) {
                    final EnergyConsumerAttribution attribution = result.attribution[j];
                    Slog.d(TAG, "  UID: " + attribution.uid
                            + "  Energy (uWs): " + attribution.energyUWs);
                }
            }
        }
    }
}
