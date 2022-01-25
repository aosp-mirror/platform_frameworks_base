/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.power;

import android.annotation.IntDef;
import android.content.res.XmlResourceParser;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseDoubleArray;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * ModemPowerProfile for handling the modem element in the power_profile.xml
 */
public class ModemPowerProfile {
    private static final String TAG = "ModemPowerProfile";

    private static final String TAG_SLEEP = "sleep";
    private static final String TAG_IDLE = "idle";
    private static final String TAG_ACTIVE = "active";
    private static final String TAG_RECEIVE = "receive";
    private static final String TAG_TRANSMIT = "transmit";
    private static final String ATTR_RAT = "rat";
    private static final String ATTR_NR_FREQUENCY = "nrFrequency";
    private static final String ATTR_LEVEL = "level";

    /**
     * A flattened list of the modem power constant extracted from the given XML parser.
     *
     * The bitfields of a key describes what its corresponding power constant represents:
     * [31:28] - {@link ModemDrainType} (max count = 16).
     * [27:24] - {@link ModemTxLevel} (only for {@link MODEM_DRAIN_TYPE_TX}) (max count = 16).
     * [23:20] - {@link ModemRatType} (max count = 16).
     * [19:16] - {@link ModemNrFrequencyRange} (only for {@link MODEM_RAT_TYPE_NR})
     * (max count = 16).
     * [15:0] - RESERVED
     */
    private final SparseDoubleArray mPowerConstants = new SparseDoubleArray();

    private static final int MODEM_DRAIN_TYPE_MASK = 0xF000_0000;
    private static final int MODEM_TX_LEVEL_MASK = 0x0F00_0000;
    private static final int MODEM_RAT_TYPE_MASK = 0x00F0_0000;
    private static final int MODEM_NR_FREQUENCY_RANGE_MASK = 0x000F_0000;

    /**
     * Corresponds to the overall modem battery drain while asleep.
     */
    public static final int MODEM_DRAIN_TYPE_SLEEP = 0x0000_0000;

    /**
     * Corresponds to the overall modem battery drain while idle.
     */
    public static final int MODEM_DRAIN_TYPE_IDLE = 0x1000_0000;

    /**
     * Corresponds to the modem battery drain while receiving data. A specific Rx battery drain
     * power constant can be selected using a bitwise OR (|) with {@link ModemRatType} and
     * {@link ModemNrFrequencyRange} (when applicable).
     */
    public static final int MODEM_DRAIN_TYPE_RX = 0x2000_0000;

    /**
     * Corresponds to the modem battery drain while receiving data.
     * {@link ModemTxLevel} must be specified with this drain type.
     * Specific Tx battery drain power constanta can be selected using a bitwise OR (|) with
     * {@link ModemRatType} and {@link ModemNrFrequencyRange} (when applicable).
     */
    public static final int MODEM_DRAIN_TYPE_TX = 0x3000_0000;

    @IntDef(prefix = {"MODEM_DRAIN_TYPE_"}, value = {
            MODEM_DRAIN_TYPE_SLEEP,
            MODEM_DRAIN_TYPE_IDLE,
            MODEM_DRAIN_TYPE_RX,
            MODEM_DRAIN_TYPE_TX,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModemDrainType {
    }


    private static final SparseArray<String> MODEM_DRAIN_TYPE_NAMES = new SparseArray<>(4);
    static {
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_DRAIN_TYPE_SLEEP, "SLEEP");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_DRAIN_TYPE_IDLE, "IDLE");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_DRAIN_TYPE_RX, "RX");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_DRAIN_TYPE_TX, "TX");
    }

    /**
     * Corresponds to {@link ModemActivityInfo#TX_POWER_LEVEL_0}.
     */

    public static final int MODEM_TX_LEVEL_0 = 0x0000_0000;

    /**
     * Corresponds to {@link ModemActivityInfo#TX_POWER_LEVEL_1}.
     */

    public static final int MODEM_TX_LEVEL_1 = 0x0100_0000;

    /**
     * Corresponds to {@link ModemActivityInfo#TX_POWER_LEVEL_2}.
     */

    public static final int MODEM_TX_LEVEL_2 = 0x0200_0000;

    /**
     * Corresponds to {@link ModemActivityInfo#TX_POWER_LEVEL_3}.
     */

    public static final int MODEM_TX_LEVEL_3 = 0x0300_0000;

    /**
     * Corresponds to {@link ModemActivityInfo#TX_POWER_LEVEL_4}.
     */

    public static final int MODEM_TX_LEVEL_4 = 0x0400_0000;

    private static final int MODEM_TX_LEVEL_COUNT = 5;

    @IntDef(prefix = {"MODEM_TX_LEVEL_"}, value = {
            MODEM_TX_LEVEL_0,
            MODEM_TX_LEVEL_1,
            MODEM_TX_LEVEL_2,
            MODEM_TX_LEVEL_3,
            MODEM_TX_LEVEL_4,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModemTxLevel {
    }

    private static final SparseArray<String> MODEM_TX_LEVEL_NAMES = new SparseArray<>(5);
    static {
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_TX_LEVEL_0, "0");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_TX_LEVEL_1, "1");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_TX_LEVEL_2, "2");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_TX_LEVEL_3, "3");
        MODEM_DRAIN_TYPE_NAMES.put(MODEM_TX_LEVEL_4, "4");
    }

    private static final int[] MODEM_TX_LEVEL_MAP = new int[]{
            MODEM_TX_LEVEL_0,
            MODEM_TX_LEVEL_1,
            MODEM_TX_LEVEL_2,
            MODEM_TX_LEVEL_3,
            MODEM_TX_LEVEL_4};

    /**
     * Fallback for any active modem usage that does not match specified Radio Access Technology
     * (RAT) power constants.
     */
    public static final int MODEM_RAT_TYPE_DEFAULT = 0x0000_0000;

    /**
     * Corresponds to active modem usage on 4G {@link TelephonyManager#NETWORK_TYPE_LTE} RAT.
     */
    public static final int MODEM_RAT_TYPE_LTE = 0x0010_0000;

    /**
     * Corresponds to active modem usage on 5G {@link TelephonyManager#NETWORK_TYPE_NR} RAT.
     */
    public static final int MODEM_RAT_TYPE_NR = 0x0020_0000;

    @IntDef(prefix = {"MODEM_RAT_TYPE_"}, value = {
            MODEM_RAT_TYPE_DEFAULT,
            MODEM_RAT_TYPE_LTE,
            MODEM_RAT_TYPE_NR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModemRatType {
    }

    private static final SparseArray<String> MODEM_RAT_TYPE_NAMES = new SparseArray<>(3);
    static {
        MODEM_RAT_TYPE_NAMES.put(MODEM_RAT_TYPE_DEFAULT, "DEFAULT");
        MODEM_RAT_TYPE_NAMES.put(MODEM_RAT_TYPE_LTE, "LTE");
        MODEM_RAT_TYPE_NAMES.put(MODEM_RAT_TYPE_NR, "NR");
    }

    /**
     * Fallback for any active 5G modem usage that does not match specified NR frequency power
     * constants.
     */
    public static final int MODEM_NR_FREQUENCY_RANGE_DEFAULT = 0x0000_0000;

    /**
     * Corresponds to active NR modem usage on {@link ServiceState#FREQUENCY_RANGE_LOW}.
     */
    public static final int MODEM_NR_FREQUENCY_RANGE_LOW = 0x0001_0000;

    /**
     * Corresponds to active NR modem usage on {@link ServiceState#FREQUENCY_RANGE_MID}.
     */
    public static final int MODEM_NR_FREQUENCY_RANGE_MID = 0x0002_0000;

    /**
     * Corresponds to active NR modem usage on {@link ServiceState#FREQUENCY_RANGE_HIGH}.
     */
    public static final int MODEM_NR_FREQUENCY_RANGE_HIGH = 0x0003_0000;

    /**
     * Corresponds to active NR modem usage on {@link ServiceState#FREQUENCY_RANGE_MMWAVE}.
     */
    public static final int MODEM_NR_FREQUENCY_RANGE_MMWAVE = 0x0004_0000;

    @IntDef(prefix = {"MODEM_NR_FREQUENCY_RANGE_"}, value = {
            MODEM_RAT_TYPE_DEFAULT,
            MODEM_NR_FREQUENCY_RANGE_LOW,
            MODEM_NR_FREQUENCY_RANGE_MID,
            MODEM_NR_FREQUENCY_RANGE_HIGH,
            MODEM_NR_FREQUENCY_RANGE_MMWAVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModemNrFrequencyRange {
    }
    private static final SparseArray<String> MODEM_NR_FREQUENCY_RANGE_NAMES = new SparseArray<>(5);
    static {
        MODEM_NR_FREQUENCY_RANGE_NAMES.put(MODEM_NR_FREQUENCY_RANGE_DEFAULT, "DEFAULT");
        MODEM_NR_FREQUENCY_RANGE_NAMES.put(MODEM_NR_FREQUENCY_RANGE_LOW, "LOW");
        MODEM_NR_FREQUENCY_RANGE_NAMES.put(MODEM_NR_FREQUENCY_RANGE_MID, "MID");
        MODEM_NR_FREQUENCY_RANGE_NAMES.put(MODEM_NR_FREQUENCY_RANGE_HIGH, "HIGH");
        MODEM_NR_FREQUENCY_RANGE_NAMES.put(MODEM_NR_FREQUENCY_RANGE_MMWAVE, "MMWAVE");
    }

    public ModemPowerProfile() {
    }

    /**
     * Generates a ModemPowerProfile object from the <modem /> element of a power_profile.xml
     */
    public void parseFromXml(XmlResourceParser parser) throws IOException,
            XmlPullParserException {
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String name = parser.getName();
            switch (name) {
                case TAG_SLEEP:
                    if (parser.next() != XmlPullParser.TEXT) {
                        continue;
                    }
                    final String sleepDrain = parser.getText();
                    setPowerConstant(MODEM_DRAIN_TYPE_SLEEP, sleepDrain);
                    break;
                case TAG_IDLE:
                    if (parser.next() != XmlPullParser.TEXT) {
                        continue;
                    }
                    final String idleDrain = parser.getText();
                    setPowerConstant(MODEM_DRAIN_TYPE_IDLE, idleDrain);
                    break;
                case TAG_ACTIVE:
                    parseActivePowerConstantsFromXml(parser);
                    break;
                default:
                    Slog.e(TAG, "Unexpected element parsed: " + name);
            }
        }
    }

    /** Parse the <active /> XML element */
    private void parseActivePowerConstantsFromXml(XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        // Parse attributes to get the type of active modem usage the power constants are for.
        final int ratType;
        final int nrfType;
        try {
            ratType = getTypeFromAttribute(parser, ATTR_RAT, MODEM_RAT_TYPE_NAMES);
            if (ratType == MODEM_RAT_TYPE_NR) {
                nrfType = getTypeFromAttribute(parser, ATTR_NR_FREQUENCY,
                        MODEM_NR_FREQUENCY_RANGE_NAMES);
            } else {
                nrfType = 0;
            }
        } catch (IllegalArgumentException iae) {
            Slog.e(TAG, "Failed parse to active modem power constants", iae);
            return;
        }

        // Parse and populate the active modem use power constants.
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String name = parser.getName();
            switch (name) {
                case TAG_RECEIVE:
                    if (parser.next() != XmlPullParser.TEXT) {
                        continue;
                    }
                    final String rxDrain = parser.getText();
                    final int rxKey = MODEM_DRAIN_TYPE_RX | ratType | nrfType;
                    setPowerConstant(rxKey, rxDrain);
                    break;
                case TAG_TRANSMIT:
                    final int level = XmlUtils.readIntAttribute(parser, ATTR_LEVEL, -1);
                    if (parser.next() != XmlPullParser.TEXT) {
                        continue;
                    }
                    final String txDrain = parser.getText();
                    if (level < 0 || level >= MODEM_TX_LEVEL_COUNT) {
                        Slog.e(TAG,
                                "Unexpected tx level: " + level + ". Must be between 0 and " + (
                                        MODEM_TX_LEVEL_COUNT - 1));
                        continue;
                    }
                    final int modemTxLevel = MODEM_TX_LEVEL_MAP[level];
                    final int txKey = MODEM_DRAIN_TYPE_TX | modemTxLevel | ratType | nrfType;
                    setPowerConstant(txKey, txDrain);
                    break;
                default:
                    Slog.e(TAG, "Unexpected element parsed: " + name);
            }
        }
    }

    private static int getTypeFromAttribute(XmlResourceParser parser, String attr,
            SparseArray<String> names) {
        final String value = XmlUtils.readStringAttribute(parser, attr);
        if (value == null) {
            // Attribute was not specified, just use the default.
            return 0;
        }
        int index = -1;
        final int size = names.size();
        // Manual linear search for string. (SparseArray uses == not equals.)
        for (int i = 0; i < size; i++) {
            if (value.equals(names.valueAt(i))) {
                index = i;
            }
        }
        if (index < 0) {
            final String[] stringNames = new String[size];
            for (int i = 0; i < size; i++) {
                stringNames[i] = names.valueAt(i);
            }
            throw new IllegalArgumentException(
                    "Unexpected " + attr + " value : " + value + ". Acceptable values are "
                            + Arrays.toString(stringNames));
        }
        return names.keyAt(index);
    }

    /**
     * Set the average battery drain in milli-amps of the modem for a given drain type.
     *
     * @param key   a key built from the union of {@link ModemDrainType}, {@link ModemTxLevel},
     *              {@link ModemRatType}, and {@link ModemNrFrequencyRange}.key
     * @param value the battery dram in milli-amps for the given key.
     */
    public void setPowerConstant(int key, String value) {
        try {
            mPowerConstants.put(key, Double.valueOf(value));
        } catch (Exception e) {
            Slog.e(TAG, "Failed to set power constant 0x" + Integer.toHexString(
                    key) + "(" + keyToString(key) + ") to " + value, e);
        }
    }

    /**
     * Returns the average battery drain in milli-amps of the modem for a given drain type.
     * Returns {@link Double.NaN} if a suitable value is not found for the given key.
     *
     * @param key a key built from the union of {@link ModemDrainType}, {@link ModemTxLevel},
     *            {@link ModemRatType}, and {@link ModemNrFrequencyRange}.
     */
    public double getAverageBatteryDrainMa(int key) {
        int bestKey = key;
        double value;
        value = mPowerConstants.get(bestKey, Double.NaN);
        if (!Double.isNaN(value)) return value;
        // The power constant for given key was not explicitly set. Try to fallback to possible
        // defaults.

        if ((bestKey & MODEM_NR_FREQUENCY_RANGE_MASK) != MODEM_NR_FREQUENCY_RANGE_DEFAULT) {
            // Fallback to NR Frequency default value
            bestKey &= ~MODEM_NR_FREQUENCY_RANGE_MASK;
            bestKey |= MODEM_NR_FREQUENCY_RANGE_DEFAULT;
            value = mPowerConstants.get(bestKey, Double.NaN);
            if (!Double.isNaN(value)) return value;
        }

        if ((bestKey & MODEM_RAT_TYPE_MASK) != MODEM_RAT_TYPE_DEFAULT) {
            // Fallback to RAT default value
            bestKey &= ~MODEM_RAT_TYPE_MASK;
            bestKey |= MODEM_RAT_TYPE_DEFAULT;
            value = mPowerConstants.get(bestKey, Double.NaN);
            if (!Double.isNaN(value)) return value;
        }

        Slog.w(TAG,
                "getAverageBatteryDrainMaH called with unexpected key: 0x" + Integer.toHexString(
                        key) + ", " + keyToString(key));
        return Double.NaN;
    }

    private static String keyToString(int key) {
        StringBuilder sb = new StringBuilder();
        final int drainType = key & MODEM_DRAIN_TYPE_MASK;
        appendFieldToString(sb, "drain", MODEM_DRAIN_TYPE_NAMES, drainType);
        sb.append(",");

        if (drainType == MODEM_DRAIN_TYPE_TX) {
            final int txLevel = key & MODEM_TX_LEVEL_MASK;
            appendFieldToString(sb, "level", MODEM_TX_LEVEL_NAMES, txLevel);
        }

        final int ratType = key & MODEM_RAT_TYPE_MASK;
        appendFieldToString(sb, "RAT", MODEM_RAT_TYPE_NAMES, ratType);

        if (ratType == MODEM_RAT_TYPE_NR) {
            sb.append(",");
            final int nrFreq = key & MODEM_NR_FREQUENCY_RANGE_MASK;
            appendFieldToString(sb, "nrFreq", MODEM_NR_FREQUENCY_RANGE_NAMES, nrFreq);
        }
        return sb.toString();
    }
    private static void appendFieldToString(StringBuilder sb, String fieldName,
            SparseArray<String> names, int key) {
        sb.append(fieldName);
        sb.append(":");
        final String name = names.get(key, null);
        if (name == null) {
            sb.append("UNKNOWN(0x");
            sb.append(Integer.toHexString(key));
            sb.append(")");
        } else {
            sb.append(name);
        }
    }

    /**
     * Clear this ModemPowerProfile power constants.
     */
    public void clear() {
        mPowerConstants.clear();
    }


    /**
     * Dump this ModemPowerProfile power constants.
     */
    public void dump(PrintWriter pw) {
        final int size = mPowerConstants.size();
        for (int i = 0; i < size; i++) {
            pw.print(keyToString(mPowerConstants.keyAt(i)));
            pw.print("=");
            pw.println(mPowerConstants.valueAt(i));
        }
    }
}
