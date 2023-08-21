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

package com.android.server.power.stats;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import java.io.PrintWriter;

/**
 * Keeps snapshots of data from previously pulled EnergyConsumerResults.
 */
public class EnergyConsumerSnapshot {
    private static final String TAG = "EnergyConsumerSnapshot";

    private static final int MILLIVOLTS_PER_VOLT = 1000;

    public static final long UNAVAILABLE = android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

    /** Map of {@link EnergyConsumer#id} to its corresponding {@link EnergyConsumer}. */
    private final SparseArray<EnergyConsumer> mEnergyConsumers;

    /** Number of ordinals for {@link EnergyConsumerType#CPU_CLUSTER}. */
    private final int mNumCpuClusterOrdinals;

    /** Number of ordinals for {@link EnergyConsumerType#DISPLAY}. */
    private final int mNumDisplayOrdinals;

    /** Number of ordinals for {@link EnergyConsumerType#OTHER}. */
    private final int mNumOtherOrdinals;

    /**
     * Energy snapshots, mapping {@link EnergyConsumer#id} to energy (UJ) from the last time
     * each {@link EnergyConsumer} was updated.
     *
     * Note that the snapshots for different ids may have been taken at different times.
     * Note that energies for all existing ids are stored here, including each ordinal of type
     * {@link EnergyConsumerType#OTHER} (tracking their total energy usage).
     *
     * If an id is not present yet, it is treated as uninitialized (energy {@link #UNAVAILABLE}).
     */
    private final SparseLongArray mEnergyConsumerSnapshots;

    /**
     * Voltage snapshots, mapping {@link EnergyConsumer#id} to voltage (mV) from the last time
     * each {@link EnergyConsumer} was updated.
     *
     * see {@link #mEnergyConsumerSnapshots}.
     */
    private final SparseIntArray mVoltageSnapshots;

    /**
     * Energy snapshots <b>per uid</b> from the last time each {@link EnergyConsumer} of type
     * {@link EnergyConsumerType#OTHER} was updated.
     * It maps each OTHER {@link EnergyConsumer#id} to a SparseLongArray, which itself maps each
     * uid to an energy (UJ). That is,
     * mAttributionSnapshots.get(consumerId).get(uid) = energy used by uid for this consumer.
     *
     * If an id is not present yet, it is treated as uninitialized (i.e. each uid is unavailable).
     * If an id is present but a uid is not present, that uid's energy is 0.
     */
    private final SparseArray<SparseLongArray> mAttributionSnapshots;

    /**
     * Constructor that initializes to the given id->EnergyConsumer map, indicating which consumers
     * exist and what their details are.
     */
    EnergyConsumerSnapshot(@NonNull SparseArray<EnergyConsumer> idToConsumerMap) {
        mEnergyConsumers = idToConsumerMap;
        mEnergyConsumerSnapshots = new SparseLongArray(mEnergyConsumers.size());
        mVoltageSnapshots = new SparseIntArray(mEnergyConsumers.size());

        mNumCpuClusterOrdinals = calculateNumOrdinals(EnergyConsumerType.CPU_CLUSTER,
                idToConsumerMap);
        mNumDisplayOrdinals = calculateNumOrdinals(EnergyConsumerType.DISPLAY, idToConsumerMap);
        mNumOtherOrdinals = calculateNumOrdinals(EnergyConsumerType.OTHER, idToConsumerMap);
        mAttributionSnapshots = new SparseArray<>(mNumOtherOrdinals);
    }

    /** Class for returning the relevant data calculated from the energy consumer delta */
    static class EnergyConsumerDeltaData {
        /** The chargeUC for {@link EnergyConsumerType#BLUETOOTH}. */
        public long bluetoothChargeUC = UNAVAILABLE;

        /** The chargeUC for {@link EnergyConsumerType#CPU_CLUSTER}s. */
        public long[] cpuClusterChargeUC = null;

        /** The chargeUC for {@link EnergyConsumerType#DISPLAY}. */
        public long[] displayChargeUC = null;

        /** The chargeUC for {@link EnergyConsumerType#GNSS}. */
        public long gnssChargeUC = UNAVAILABLE;

        /** The chargeUC for {@link EnergyConsumerType#MOBILE_RADIO}. */
        public long mobileRadioChargeUC = UNAVAILABLE;

        /** The chargeUC for {@link EnergyConsumerType#WIFI}. */
        public long wifiChargeUC = UNAVAILABLE;

        /** The chargeUC for {@link EnergyConsumerType#CAMERA}. */
        public long cameraChargeUC = UNAVAILABLE;

        /** Map of {@link EnergyConsumerType#OTHER} ordinals to their total chargeUC. */
        public @Nullable long[] otherTotalChargeUC = null;

        /** Map of {@link EnergyConsumerType#OTHER} ordinals to their {uid->chargeUC} maps. */
        public @Nullable SparseLongArray[] otherUidChargesUC = null;

        boolean isEmpty() {
            return bluetoothChargeUC <= 0
                    && isEmpty(cpuClusterChargeUC)
                    && isEmpty(displayChargeUC)
                    && gnssChargeUC <= 0
                    && mobileRadioChargeUC <= 0
                    && wifiChargeUC <= 0
                    && isEmpty(otherTotalChargeUC);
        }

        private boolean isEmpty(long[] values) {
            if (values == null) {
                return true;
            }
            for (long value: values) {
                if (value > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Update with the freshly retrieved energy consumers and return the difference (delta)
     * between the previously stored values and the passed-in values.
     *
     * @param ecrs EnergyConsumerResults for some (possibly not all) {@link EnergyConsumer}s.
     *             Consumers that are not present are ignored (they are *not* treated as 0).
     * @param voltageMV current voltage.
     *
     * @return an EnergyConsumerDeltaData, containing maps from the updated consumers to
     *         their corresponding charge deltas.
     *         Fields with no interesting data (consumers not present in ecrs or with no energy
     *         difference) will generally be left as their default values.
     *         otherTotalChargeUC and otherUidChargesUC are always either both null or both of
     *         length {@link #getOtherOrdinalNames().length}.
     *         Returns null, if ecrs is null or empty.
     */
    @Nullable
    public EnergyConsumerDeltaData updateAndGetDelta(EnergyConsumerResult[] ecrs, int voltageMV) {
        if (ecrs == null || ecrs.length == 0) {
            return null;
        }
        if (voltageMV <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMV
                    + " mV) when taking energy consumer snapshot");
            // TODO (b/181685156): consider adding the nominal voltage to power profile and
            //  falling back to it if measured voltage is unavailable.
            return null;
        }
        final EnergyConsumerDeltaData output = new EnergyConsumerDeltaData();

        for (final EnergyConsumerResult ecr : ecrs) {
            // Extract the new energy data for the current consumer.
            final int consumerId = ecr.id;
            final long newEnergyUJ = ecr.energyUWs;
            final EnergyConsumerAttribution[] newAttributions = ecr.attribution;

            // Look up the static information about this consumer.
            final EnergyConsumer consumer = mEnergyConsumers.get(consumerId, null);
            if (consumer == null) {
                Slog.e(TAG, "updateAndGetDelta given invalid consumerId " + consumerId);
                continue;
            }
            final int type = consumer.type;
            final int ordinal = consumer.ordinal;

            // Look up, and update, the old energy and voltage information about this consumer.
            final long oldEnergyUJ = mEnergyConsumerSnapshots.get(consumerId, UNAVAILABLE);
            final int oldVoltageMV = mVoltageSnapshots.get(consumerId);
            mEnergyConsumerSnapshots.put(consumerId, newEnergyUJ);
            mVoltageSnapshots.put(consumerId, voltageMV);

            final int avgVoltageMV = (oldVoltageMV + voltageMV + 1) / 2;
            final SparseLongArray otherUidCharges =
                    updateAndGetDeltaForTypeOther(consumer, newAttributions, avgVoltageMV);
            // Everything is fully done being updated. We now calculate the delta for returning.

            // NB: Since sum(attribution.energyUWs)<=energyUWs we assume that if deltaEnergy==0
            // there's no attribution either. Technically that isn't enforced at the HAL, but we
            // can't really trust data like that anyway.

            if (oldEnergyUJ < 0) continue; // Generally happens only on initialization.
            if (newEnergyUJ == oldEnergyUJ) continue;

            final long deltaUJ = newEnergyUJ - oldEnergyUJ;
            if (deltaUJ < 0 || oldVoltageMV <= 0) {
                Slog.e(TAG, "Bad data! EnergyConsumer " + consumer.name
                        + ": new energy (" + newEnergyUJ + ") < old energy (" + oldEnergyUJ
                        + "), new voltage (" + voltageMV + "), old voltage (" + oldVoltageMV
                        + "). Skipping. ");
                continue;
            }

            final long deltaChargeUC = calculateChargeConsumedUC(deltaUJ, avgVoltageMV);
            switch (type) {
                case EnergyConsumerType.BLUETOOTH:
                    output.bluetoothChargeUC = deltaChargeUC;
                    break;

                case EnergyConsumerType.CPU_CLUSTER:
                    if (output.cpuClusterChargeUC == null) {
                        output.cpuClusterChargeUC = new long[mNumCpuClusterOrdinals];
                    }
                    output.cpuClusterChargeUC[ordinal] = deltaChargeUC;
                    break;

                case EnergyConsumerType.DISPLAY:
                    if (output.displayChargeUC == null) {
                        output.displayChargeUC = new long[mNumDisplayOrdinals];
                    }
                    output.displayChargeUC[ordinal]  = deltaChargeUC;
                    break;

                case EnergyConsumerType.GNSS:
                    output.gnssChargeUC = deltaChargeUC;
                    break;

                case EnergyConsumerType.MOBILE_RADIO:
                    output.mobileRadioChargeUC = deltaChargeUC;
                    break;

                case EnergyConsumerType.WIFI:
                    output.wifiChargeUC = deltaChargeUC;
                    break;

                case EnergyConsumerType.CAMERA:
                    output.cameraChargeUC = deltaChargeUC;
                    break;

                case EnergyConsumerType.OTHER:
                    if (output.otherTotalChargeUC == null) {
                        output.otherTotalChargeUC = new long[mNumOtherOrdinals];
                        output.otherUidChargesUC = new SparseLongArray[mNumOtherOrdinals];
                    }
                    output.otherTotalChargeUC[ordinal] = deltaChargeUC;
                    output.otherUidChargesUC[ordinal] = otherUidCharges;
                    break;

                default:
                    Slog.w(TAG, "Ignoring consumer " + consumer.name + " of unknown type " + type);

            }
        }
        return output;
    }

    /**
     * For a consumer of type {@link EnergyConsumerType#OTHER}, updates
     * {@link #mAttributionSnapshots} with freshly retrieved energy consumers (per uid) and returns
     * the charge consumed (in microcoulombs) between the previously stored values and the passed-in
     * values.
     *
     * @param consumerInfo a consumer of type {@link EnergyConsumerType#OTHER}.
     * @param newAttributions Record of uids and their new energyUJ values.
     *                        Any uid not present is treated as having energy 0.
     *                        If null or empty, all uids are treated as having energy 0.
     * @param avgVoltageMV The average voltage since the last snapshot.
     * @return A map (in the sense of {@link EnergyConsumerDeltaData#otherUidChargesUC} for this
     *         consumer) of uid -> chargeDelta, with all uids that have a non-zero chargeDelta.
     *         Returns null if no delta available to calculate.
     */
    private @Nullable SparseLongArray updateAndGetDeltaForTypeOther(
            @NonNull EnergyConsumer consumerInfo,
            @Nullable EnergyConsumerAttribution[] newAttributions, int avgVoltageMV) {

        if (consumerInfo.type != EnergyConsumerType.OTHER) {
            return null;
        }
        if (newAttributions == null) {
            // Treat null as empty (i.e. all uids have 0 energy).
            newAttributions = new EnergyConsumerAttribution[0];
        }

        // SparseLongArray mapping uid -> energyUJ (for this particular consumerId)
        SparseLongArray uidOldEnergyMap = mAttributionSnapshots.get(consumerInfo.id, null);

        // If uidOldEnergyMap wasn't present, each uid was UNAVAILABLE, so update data and return.
        if (uidOldEnergyMap == null) {
            uidOldEnergyMap = new SparseLongArray(newAttributions.length);
            mAttributionSnapshots.put(consumerInfo.id, uidOldEnergyMap);
            for (EnergyConsumerAttribution newAttribution : newAttributions) {
                uidOldEnergyMap.put(newAttribution.uid, newAttribution.energyUWs);
            }
            return null;
        }

        // Map uid -> chargeDelta. No initial capacity since many deltas might be 0.
        final SparseLongArray uidChargeDeltas = new SparseLongArray();

        for (EnergyConsumerAttribution newAttribution : newAttributions) {
            final int uid = newAttribution.uid;
            final long newEnergyUJ = newAttribution.energyUWs;
            // uidOldEnergyMap was present. So any particular uid that wasn't present, had 0 energy.
            final long oldEnergyUJ = uidOldEnergyMap.get(uid, 0L);
            uidOldEnergyMap.put(uid, newEnergyUJ);

            // Everything is fully done being updated. We now calculate the delta for returning.
            if (oldEnergyUJ < 0) continue;
            if (newEnergyUJ == oldEnergyUJ) continue;
            final long deltaUJ = newEnergyUJ - oldEnergyUJ;
            if (deltaUJ < 0 || avgVoltageMV <= 0) {
                Slog.e(TAG, "EnergyConsumer " + consumerInfo.name + ": new energy (" + newEnergyUJ
                        + ") but old energy (" + oldEnergyUJ + "). Average voltage (" + avgVoltageMV
                        + ")Skipping. ");
                continue;
            }

            final long deltaChargeUC = calculateChargeConsumedUC(deltaUJ, avgVoltageMV);
            uidChargeDeltas.put(uid, deltaChargeUC);
        }
        return uidChargeDeltas;
    }

    /** Dump debug data. */
    public void dump(PrintWriter pw) {
        pw.println("Energy consumer snapshot");
        pw.println("List of EnergyConsumers:");
        for (int i = 0; i < mEnergyConsumers.size(); i++) {
            final int id = mEnergyConsumers.keyAt(i);
            final EnergyConsumer consumer = mEnergyConsumers.valueAt(i);
            pw.println(String.format("    Consumer %d is {id=%d, ordinal=%d, type=%d, name=%s}", id,
                    consumer.id, consumer.ordinal, consumer.type, consumer.name));
        }
        pw.println("Map of consumerIds to energy (in microjoules):");
        for (int i = 0; i < mEnergyConsumerSnapshots.size(); i++) {
            final int id = mEnergyConsumerSnapshots.keyAt(i);
            final long energyUJ = mEnergyConsumerSnapshots.valueAt(i);
            final long voltageMV = mVoltageSnapshots.valueAt(i);
            pw.println(String.format("    Consumer %d has energy %d uJ at %d mV", id, energyUJ,
                    voltageMV));
        }
        pw.println("List of the " + mNumOtherOrdinals + " OTHER EnergyConsumers:");
        pw.println("    " + mAttributionSnapshots);
        pw.println();
    }

    /**
     * Returns the names of ordinals for {@link EnergyConsumerType#OTHER}, i.e. the names of
     * custom energy buckets supported by the device.
     */
    public String[] getOtherOrdinalNames() {
        final String[] names = new String[mNumOtherOrdinals];
        int consumerIndex = 0;
        final int size = mEnergyConsumers.size();
        for (int idx = 0; idx < size; idx++) {
            final EnergyConsumer consumer = mEnergyConsumers.valueAt(idx);
            if (consumer.type == (int) EnergyConsumerType.OTHER) {
                names[consumerIndex++] = sanitizeCustomBucketName(consumer.name);
            }
        }
        return names;
    }

    private String sanitizeCustomBucketName(String bucketName) {
        if (bucketName == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bucketName.length());
        for (char c : bucketName.toCharArray()) {
            if (Character.isWhitespace(c)) {
                sb.append(' ');
            } else if (Character.isISOControl(c)) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Determines the number of ordinals for a given {@link EnergyConsumerType}. */
    private static int calculateNumOrdinals(@EnergyConsumerType int type,
            SparseArray<EnergyConsumer> idToConsumer) {
        if (idToConsumer == null) return 0;
        int numOrdinals = 0;
        final int size = idToConsumer.size();
        for (int idx = 0; idx < size; idx++) {
            final EnergyConsumer consumer = idToConsumer.valueAt(idx);
            if (consumer.type == type) numOrdinals++;
        }
        return numOrdinals;
    }

    /** Calculate charge consumption (in microcoulombs) from a given energy and voltage */
    private long calculateChargeConsumedUC(long deltaEnergyUJ, int avgVoltageMV) {
        // To overflow, a 3.7V 10000mAh battery would need to completely drain 69244 times
        // since the last snapshot. Round off to the nearest whole long.
        return (deltaEnergyUJ * MILLIVOLTS_PER_VOLT + (avgVoltageMV / 2)) / avgVoltageMV;
    }
}
