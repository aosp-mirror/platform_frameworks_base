/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Container for power stats, acquired by various PowerStatsCollector classes. See subclasses for
 * details.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class PowerStats {
    private static final String TAG = "PowerStats";

    private static final BatteryStatsHistory.VarintParceler VARINT_PARCELER =
            new BatteryStatsHistory.VarintParceler();
    private static final byte PARCEL_FORMAT_VERSION = 2;

    private static final int PARCEL_FORMAT_VERSION_MASK = 0x000000FF;
    private static final int PARCEL_FORMAT_VERSION_SHIFT =
            Integer.numberOfTrailingZeros(PARCEL_FORMAT_VERSION_MASK);
    private static final int STATS_ARRAY_LENGTH_MASK = 0x0000FF00;
    private static final int STATS_ARRAY_LENGTH_SHIFT =
            Integer.numberOfTrailingZeros(STATS_ARRAY_LENGTH_MASK);
    public static final int MAX_STATS_ARRAY_LENGTH =
            (1 << Integer.bitCount(STATS_ARRAY_LENGTH_MASK)) - 1;
    private static final int STATE_STATS_ARRAY_LENGTH_MASK = 0x00FF0000;
    private static final int STATE_STATS_ARRAY_LENGTH_SHIFT =
            Integer.numberOfTrailingZeros(STATE_STATS_ARRAY_LENGTH_MASK);
    public static final int MAX_STATE_STATS_ARRAY_LENGTH =
            (1 << Integer.bitCount(STATE_STATS_ARRAY_LENGTH_MASK)) - 1;
    private static final int UID_STATS_ARRAY_LENGTH_MASK = 0xFF000000;
    private static final int UID_STATS_ARRAY_LENGTH_SHIFT =
            Integer.numberOfTrailingZeros(UID_STATS_ARRAY_LENGTH_MASK);
    public static final int MAX_UID_STATS_ARRAY_LENGTH =
            (1 << Integer.bitCount(UID_STATS_ARRAY_LENGTH_MASK)) - 1;

    /**
     * Descriptor of the stats collected for a given power component (e.g. CPU, WiFi etc).
     * This descriptor is used for storing PowerStats and can also be used by power models
     * to adjust the algorithm in accordance with the stats available on the device.
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static class Descriptor {
        public static final String EXTRA_DEVICE_STATS_FORMAT = "format-device";
        public static final String EXTRA_STATE_STATS_FORMAT = "format-state";
        public static final String EXTRA_UID_STATS_FORMAT = "format-uid";

        public static final String XML_TAG_DESCRIPTOR = "descriptor";
        private static final String XML_ATTR_ID = "id";
        private static final String XML_ATTR_NAME = "name";
        private static final String XML_ATTR_STATS_ARRAY_LENGTH = "stats-array-length";
        private static final String XML_TAG_STATE = "state";
        private static final String XML_ATTR_STATE_KEY = "key";
        private static final String XML_ATTR_STATE_LABEL = "label";
        private static final String XML_ATTR_STATE_STATS_ARRAY_LENGTH = "state-stats-array-length";
        private static final String XML_ATTR_UID_STATS_ARRAY_LENGTH = "uid-stats-array-length";
        private static final String XML_TAG_EXTRAS = "extras";

        /**
         * {@link BatteryConsumer.PowerComponent} (e.g. CPU, WIFI etc) that this snapshot relates
         * to; or a custom power component ID (if the value
         * is &gt;= {@link BatteryConsumer#FIRST_CUSTOM_POWER_COMPONENT_ID}).
         */
        @BatteryConsumer.PowerComponentId
        public final int powerComponentId;
        public final String name;

        /**
         * Stats for the power component, such as the total usage time.
         */
        public final int statsArrayLength;

        /**
         * Map of device state codes to their corresponding human-readable labels.
         */
        public final SparseArray<String> stateLabels;

        /**
         * Stats for a specific state of the power component, e.g. "mobile radio in the 5G mode"
         */
        public final int stateStatsArrayLength;

        /**
         * Stats for the usage of this power component by a specific UID (app)
         */
        public final int uidStatsArrayLength;

        /**
         * Extra parameters specific to the power component, e.g. the availability of power
         * monitors.
         */
        public final PersistableBundle extras;

        private PowerStatsFormatter mDeviceStatsFormatter;
        private PowerStatsFormatter mStateStatsFormatter;
        private PowerStatsFormatter mUidStatsFormatter;

        public Descriptor(@BatteryConsumer.PowerComponent int powerComponentId,
                int statsArrayLength, @Nullable SparseArray<String> stateLabels,
                int stateStatsArrayLength, int uidStatsArrayLength,
                @NonNull PersistableBundle extras) {
            this(powerComponentId, BatteryConsumer.powerComponentIdToString(powerComponentId),
                    statsArrayLength, stateLabels, stateStatsArrayLength, uidStatsArrayLength,
                    extras);
        }

        public Descriptor(@BatteryConsumer.PowerComponentId int powerComponentId, String name,
                int statsArrayLength, @Nullable SparseArray<String> stateLabels,
                int stateStatsArrayLength, int uidStatsArrayLength,
                @NonNull PersistableBundle extras) {
            if (statsArrayLength > MAX_STATS_ARRAY_LENGTH) {
                throw new IllegalArgumentException(
                        "statsArrayLength is too high. Max = " + MAX_STATS_ARRAY_LENGTH);
            }
            if (stateStatsArrayLength > MAX_STATE_STATS_ARRAY_LENGTH) {
                throw new IllegalArgumentException(
                        "stateStatsArrayLength is too high. Max = " + MAX_STATE_STATS_ARRAY_LENGTH);
            }
            if (uidStatsArrayLength > MAX_UID_STATS_ARRAY_LENGTH) {
                throw new IllegalArgumentException(
                        "uidStatsArrayLength is too high. Max = " + MAX_UID_STATS_ARRAY_LENGTH);
            }
            this.powerComponentId = powerComponentId;
            this.name = name;
            this.statsArrayLength = statsArrayLength;
            this.stateLabels = stateLabels != null ? stateLabels : new SparseArray<>();
            this.stateStatsArrayLength = stateStatsArrayLength;
            this.uidStatsArrayLength = uidStatsArrayLength;
            this.extras = extras;
        }

        /**
         * Returns a custom formatter for this type of power stats.
         */
        public PowerStatsFormatter getDeviceStatsFormatter() {
            if (mDeviceStatsFormatter == null) {
                mDeviceStatsFormatter = new PowerStatsFormatter(
                        extras.getString(EXTRA_DEVICE_STATS_FORMAT));
            }
            return mDeviceStatsFormatter;
        }

        /**
         * Returns a custom formatter for this type of power stats, specifically per-state stats.
         */
        public PowerStatsFormatter getStateStatsFormatter() {
            if (mStateStatsFormatter == null) {
                mStateStatsFormatter = new PowerStatsFormatter(
                        extras.getString(EXTRA_STATE_STATS_FORMAT));
            }
            return mStateStatsFormatter;
        }

        /**
         * Returns a custom formatter for this type of power stats, specifically per-UID stats.
         */
        public PowerStatsFormatter getUidStatsFormatter() {
            if (mUidStatsFormatter == null) {
                mUidStatsFormatter = new PowerStatsFormatter(
                        extras.getString(EXTRA_UID_STATS_FORMAT));
            }
            return mUidStatsFormatter;
        }

        /**
         * Returns the label associated with the give state key, e.g. "5G-high" for the
         * state of Mobile Radio representing the 5G mode and high signal power.
         */
        public String getStateLabel(int key) {
            String label = stateLabels.get(key);
            if (label != null) {
                return label;
            }
            return name + "-" + Integer.toHexString(key);
        }

        /**
         * Writes the Descriptor into the parcel.
         */
        public void writeSummaryToParcel(Parcel parcel) {
            int firstWord = ((PARCEL_FORMAT_VERSION << PARCEL_FORMAT_VERSION_SHIFT)
                             & PARCEL_FORMAT_VERSION_MASK)
                            | ((statsArrayLength << STATS_ARRAY_LENGTH_SHIFT)
                               & STATS_ARRAY_LENGTH_MASK)
                            | ((stateStatsArrayLength << STATE_STATS_ARRAY_LENGTH_SHIFT)
                               & STATE_STATS_ARRAY_LENGTH_MASK)
                            | ((uidStatsArrayLength << UID_STATS_ARRAY_LENGTH_SHIFT)
                               & UID_STATS_ARRAY_LENGTH_MASK);
            parcel.writeInt(firstWord);
            parcel.writeInt(powerComponentId);
            parcel.writeString(name);
            parcel.writeInt(stateLabels.size());
            for (int i = 0, size = stateLabels.size(); i < size; i++) {
                parcel.writeInt(stateLabels.keyAt(i));
                parcel.writeString(stateLabels.valueAt(i));
            }
            extras.writeToParcel(parcel, 0);
        }

        /**
         * Reads a Descriptor from the parcel.  If the parcel has an incompatible format,
         * returns null.
         */
        @Nullable
        public static Descriptor readSummaryFromParcel(Parcel parcel) {
            int firstWord = parcel.readInt();
            int version = (firstWord & PARCEL_FORMAT_VERSION_MASK) >>> PARCEL_FORMAT_VERSION_SHIFT;
            if (version != PARCEL_FORMAT_VERSION) {
                Slog.w(TAG, "Cannot read PowerStats from Parcel - the parcel format version has "
                           + "changed from " + version + " to " + PARCEL_FORMAT_VERSION);
                return null;
            }
            int statsArrayLength =
                    (firstWord & STATS_ARRAY_LENGTH_MASK) >>> STATS_ARRAY_LENGTH_SHIFT;
            int stateStatsArrayLength =
                    (firstWord & STATE_STATS_ARRAY_LENGTH_MASK) >>> STATE_STATS_ARRAY_LENGTH_SHIFT;
            int uidStatsArrayLength =
                    (firstWord & UID_STATS_ARRAY_LENGTH_MASK) >>> UID_STATS_ARRAY_LENGTH_SHIFT;
            int powerComponentId = parcel.readInt();
            String name = parcel.readString();
            int stateLabelCount = parcel.readInt();
            SparseArray<String> stateLabels = new SparseArray<>(stateLabelCount);
            for (int i = stateLabelCount; i > 0; i--) {
                int key = parcel.readInt();
                String label = parcel.readString();
                stateLabels.put(key, label);
            }
            PersistableBundle extras = parcel.readPersistableBundle();
            return new Descriptor(powerComponentId, name, statsArrayLength, stateLabels,
                    stateStatsArrayLength, uidStatsArrayLength, extras);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Descriptor)) return false;
            Descriptor that = (Descriptor) o;
            return powerComponentId == that.powerComponentId
                    && statsArrayLength == that.statsArrayLength
                    && stateLabels.contentEquals(that.stateLabels)
                    && stateStatsArrayLength == that.stateStatsArrayLength
                    && uidStatsArrayLength == that.uidStatsArrayLength
                    && Objects.equals(name, that.name)
                    && extras.size() == that.extras.size()        // Unparcel the Parcel if not yet
                    && Bundle.kindofEquals(extras,
                    that.extras);  // Since the Parcel is now unparceled, do a deep comparison
        }

        /**
         * Stores contents in an XML doc.
         */
        public void writeXml(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, XML_TAG_DESCRIPTOR);
            serializer.attributeInt(null, XML_ATTR_ID, powerComponentId);
            serializer.attribute(null, XML_ATTR_NAME, name);
            serializer.attributeInt(null, XML_ATTR_STATS_ARRAY_LENGTH, statsArrayLength);
            serializer.attributeInt(null, XML_ATTR_STATE_STATS_ARRAY_LENGTH, stateStatsArrayLength);
            serializer.attributeInt(null, XML_ATTR_UID_STATS_ARRAY_LENGTH, uidStatsArrayLength);
            for (int i = stateLabels.size() - 1; i >= 0; i--) {
                serializer.startTag(null, XML_TAG_STATE);
                serializer.attributeInt(null, XML_ATTR_STATE_KEY, stateLabels.keyAt(i));
                serializer.attribute(null, XML_ATTR_STATE_LABEL, stateLabels.valueAt(i));
                serializer.endTag(null, XML_TAG_STATE);
            }
            try {
                serializer.startTag(null, XML_TAG_EXTRAS);
                extras.saveToXml(serializer);
                serializer.endTag(null, XML_TAG_EXTRAS);
            } catch (XmlPullParserException e) {
                throw new IOException(e);
            }
            serializer.endTag(null, XML_TAG_DESCRIPTOR);
        }

        /**
         * Creates a Descriptor by parsing an XML doc.  The parser is expected to be positioned
         * on or before the opening "descriptor" tag.
         */
        public static Descriptor createFromXml(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int powerComponentId = -1;
            String name = null;
            int statsArrayLength = 0;
            SparseArray<String> stateLabels = new SparseArray<>();
            int stateStatsArrayLength = 0;
            int uidStatsArrayLength = 0;
            PersistableBundle extras = null;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT
                   && !(eventType == XmlPullParser.END_TAG
                        && parser.getName().equals(XML_TAG_DESCRIPTOR))) {
                if (eventType == XmlPullParser.START_TAG) {
                    switch (parser.getName()) {
                        case XML_TAG_DESCRIPTOR:
                            powerComponentId = parser.getAttributeInt(null, XML_ATTR_ID);
                            name = parser.getAttributeValue(null, XML_ATTR_NAME);
                            statsArrayLength = parser.getAttributeInt(null,
                                    XML_ATTR_STATS_ARRAY_LENGTH);
                            stateStatsArrayLength = parser.getAttributeInt(null,
                                    XML_ATTR_STATE_STATS_ARRAY_LENGTH);
                            uidStatsArrayLength = parser.getAttributeInt(null,
                                    XML_ATTR_UID_STATS_ARRAY_LENGTH);
                            break;
                        case XML_TAG_STATE:
                            int value = parser.getAttributeInt(null, XML_ATTR_STATE_KEY);
                            String label = parser.getAttributeValue(null, XML_ATTR_STATE_LABEL);
                            stateLabels.put(value, label);
                            break;
                        case XML_TAG_EXTRAS:
                            extras = PersistableBundle.restoreFromXml(parser);
                            break;
                    }
                }
                eventType = parser.next();
            }
            if (powerComponentId == -1) {
                return null;
            } else if (powerComponentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
                return new Descriptor(powerComponentId, name, statsArrayLength,
                        stateLabels, stateStatsArrayLength, uidStatsArrayLength, extras);
            } else if (powerComponentId < BatteryConsumer.POWER_COMPONENT_COUNT) {
                return new Descriptor(powerComponentId, statsArrayLength, stateLabels,
                        stateStatsArrayLength, uidStatsArrayLength, extras);
            } else {
                Slog.e(TAG, "Unrecognized power component: " + powerComponentId);
                return null;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(powerComponentId);
        }

        @Override
        public String toString() {
            if (extras != null) {
                extras.size();  // Unparcel
            }
            return "PowerStats.Descriptor{"
                    + "powerComponentId=" + powerComponentId
                    + ", name='" + name + '\''
                    + ", statsArrayLength=" + statsArrayLength
                    + ", stateStatsArrayLength=" + stateStatsArrayLength
                    + ", stateLabels=" + stateLabels
                    + ", uidStatsArrayLength=" + uidStatsArrayLength
                    + ", extras=" + extras
                    + '}';
        }
    }

    /**
     * A registry for all supported power component types (e.g. CPU, WiFi).
     */
    public static class DescriptorRegistry {
        private final SparseArray<Descriptor> mDescriptors = new SparseArray<>();

        /**
         * Adds the specified descriptor to the registry. If the registry already
         * contained a descriptor for the same power component, then the new one replaces
         * the old one.
         */
        public void register(Descriptor descriptor) {
            mDescriptors.put(descriptor.powerComponentId, descriptor);
        }

        /**
         * @param powerComponentId either a BatteryConsumer.PowerComponent or a custom power
         *                         component ID
         */
        public Descriptor get(int powerComponentId) {
            return mDescriptors.get(powerComponentId);
        }
    }

    public final Descriptor descriptor;

    /**
     * Duration, in milliseconds, covered by this snapshot.
     */
    public long durationMs;

    /**
     * Device-wide stats.
     */
    public long[] stats;

    /**
     * Device-wide mode stats, used when the power component can operate in different modes,
     * e.g. RATs such as LTE and 5G.
     */
    public final SparseArray<long[]> stateStats = new SparseArray<>();

    /**
     * Per-UID CPU stats.
     */
    public final SparseArray<long[]> uidStats = new SparseArray<>();

    public PowerStats(Descriptor descriptor) {
        this.descriptor = descriptor;
        stats = new long[descriptor.statsArrayLength];
    }

    /**
     * Writes the object into the parcel.
     */
    public void writeToParcel(Parcel parcel) {
        int lengthPos = parcel.dataPosition();
        parcel.writeInt(0);     // Placeholder for length

        int startPos = parcel.dataPosition();
        parcel.writeInt(descriptor.powerComponentId);
        parcel.writeLong(durationMs);
        VARINT_PARCELER.writeLongArray(parcel, stats);

        if (descriptor.stateStatsArrayLength != 0) {
            parcel.writeInt(stateStats.size());
            for (int i = 0; i < stateStats.size(); i++) {
                parcel.writeInt(stateStats.keyAt(i));
                VARINT_PARCELER.writeLongArray(parcel, stateStats.valueAt(i));
            }
        }

        parcel.writeInt(uidStats.size());
        for (int i = 0; i < uidStats.size(); i++) {
            parcel.writeInt(uidStats.keyAt(i));
            VARINT_PARCELER.writeLongArray(parcel, uidStats.valueAt(i));
        }

        int endPos = parcel.dataPosition();
        parcel.setDataPosition(lengthPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }

    /**
     * Reads a PowerStats object from the supplied Parcel. If the parcel has an incompatible
     * format, returns null.
     */
    @Nullable
    public static PowerStats readFromParcel(Parcel parcel, DescriptorRegistry registry) {
        int length = parcel.readInt();
        int startPos = parcel.dataPosition();
        int endPos = startPos + length;

        try {
            int powerComponentId = parcel.readInt();

            Descriptor descriptor = registry.get(powerComponentId);
            if (descriptor == null) {
                Slog.e(TAG, "Unsupported PowerStats for power component ID: " + powerComponentId);
                return null;
            }
            PowerStats stats = new PowerStats(descriptor);
            stats.durationMs = parcel.readLong();
            stats.stats = new long[descriptor.statsArrayLength];
            VARINT_PARCELER.readLongArray(parcel, stats.stats);

            if (descriptor.stateStatsArrayLength != 0) {
                int count = parcel.readInt();
                for (int i = 0; i < count; i++) {
                    int state = parcel.readInt();
                    long[] stateStats = new long[descriptor.stateStatsArrayLength];
                    VARINT_PARCELER.readLongArray(parcel, stateStats);
                    stats.stateStats.put(state, stateStats);
                }
            }

            int uidCount = parcel.readInt();
            for (int i = 0; i < uidCount; i++) {
                int uid = parcel.readInt();
                long[] uidStats = new long[descriptor.uidStatsArrayLength];
                VARINT_PARCELER.readLongArray(parcel, uidStats);
                stats.uidStats.put(uid, uidStats);
            }
            if (parcel.dataPosition() != endPos) {
                Slog.e(TAG, "Corrupted PowerStats parcel. Expected length: " + length
                           + ", actual length: " + (parcel.dataPosition() - startPos));
                return null;
            }
            return stats;
        } finally {
            // Unconditionally skip to the end of the written data, even if the actual parcel
            // format is incompatible
            if (endPos > parcel.dataPosition()) {
                if (endPos >= parcel.dataSize()) {
                    throw new IndexOutOfBoundsException(
                            "PowerStats end position: " + endPos + " is outside the parcel bounds: "
                                    + parcel.dataSize());
                }
                parcel.setDataPosition(endPos);
            }
        }
    }

    /**
     * Formats the stats as a string suitable to be included in the Battery History dump.
     */
    public String formatForBatteryHistory(String uidPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("duration=").append(durationMs).append(" ").append(descriptor.name);
        if (stats.length > 0) {
            sb.append("=").append(descriptor.getDeviceStatsFormatter().format(stats));
        }
        if (descriptor.stateStatsArrayLength != 0) {
            PowerStatsFormatter formatter = descriptor.getStateStatsFormatter();
            for (int i = 0; i < stateStats.size(); i++) {
                sb.append(" (");
                sb.append(descriptor.getStateLabel(stateStats.keyAt(i)));
                sb.append(") ");
                sb.append(formatter.format(stateStats.valueAt(i)));
            }
        }
        PowerStatsFormatter uidStatsFormatter = descriptor.getUidStatsFormatter();
        for (int i = 0; i < uidStats.size(); i++) {
            sb.append(uidPrefix)
                    .append(UserHandle.formatUid(uidStats.keyAt(i)))
                    .append(": ").append(uidStatsFormatter.format(uidStats.valueAt(i)));
        }
        return sb.toString();
    }

    /**
     * Prints the contents of the stats snapshot.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println(descriptor.name + " (" + descriptor.powerComponentId + ')');
        pw.increaseIndent();
        pw.print("duration", durationMs).println();

        if (descriptor.statsArrayLength != 0) {
            pw.println(descriptor.getDeviceStatsFormatter().format(stats));
        }
        if (descriptor.stateStatsArrayLength != 0) {
            PowerStatsFormatter formatter = descriptor.getStateStatsFormatter();
            for (int i = 0; i < stateStats.size(); i++) {
                pw.print(" (");
                pw.print(descriptor.getStateLabel(stateStats.keyAt(i)));
                pw.print(") ");
                pw.print(formatter.format(stateStats.valueAt(i)));
                pw.println();
            }
        }
        PowerStatsFormatter uidStatsFormatter = descriptor.getUidStatsFormatter();
        for (int i = 0; i < uidStats.size(); i++) {
            String formattedStats = uidStatsFormatter.format(uidStats.valueAt(i));
            if (formattedStats.isBlank()) {
                continue;
            }

            pw.print("UID ");
            pw.print(UserHandle.formatUid(uidStats.keyAt(i)));
            pw.print(": ");
            pw.print(formattedStats);
            pw.println();
        }
        pw.decreaseIndent();
    }

    @Override
    public String toString() {
        return "PowerStats: " + formatForBatteryHistory(" UID ");
    }

    public static class PowerStatsFormatter {
        private static class Section {
            public String label;
            public int position;
            public int length;
            public boolean optional;
            public boolean typePower;
        }

        private static final double NANO_TO_MILLI_MULTIPLIER = 1.0 / 1000000.0;
        private static final Pattern SECTION_PATTERN =
                Pattern.compile("([^:]+):(\\d+)(\\[(?<L>\\d+)])?(?<F>\\S*)\\s*");
        private final List<Section> mSections;

        public PowerStatsFormatter(String format) {
            mSections = parseFormat(format);
        }

        /**
         * Produces a formatted string representing the supplied array, with labels
         * and other adornments specific to the power stats layout.
         */
        public String format(long[] stats) {
            return format(mSections, stats);
        }

        private List<Section> parseFormat(String format) {
            if (format == null || format.isBlank()) {
                return null;
            }

            ArrayList<Section> sections = new ArrayList<>();
            Matcher matcher = SECTION_PATTERN.matcher(format);
            for (int position = 0; position < format.length(); position = matcher.end()) {
                if (!matcher.find() || matcher.start() != position) {
                    Slog.wtf(TAG, "Bad power stats format '" + format + "'");
                    return null;
                }
                Section section = new Section();
                section.label = matcher.group(1);
                section.position = Integer.parseUnsignedInt(matcher.group(2));
                String length = matcher.group("L");
                if (length != null) {
                    section.length = Integer.parseUnsignedInt(length);
                } else {
                    section.length = 1;
                }
                String flags = matcher.group("F");
                if (flags != null) {
                    for (int i = 0; i < flags.length(); i++) {
                        char flag = flags.charAt(i);
                        switch (flag) {
                            case '?':
                                section.optional = true;
                                break;
                            case 'p':
                                section.typePower = true;
                                break;
                            default:
                                Slog.e(TAG,
                                        "Unsupported format option '" + flag + "' in " + format);
                                break;
                        }
                    }
                }
                sections.add(section);
            }

            return sections;
        }

        private String format(List<Section> sections, long[] stats) {
            if (sections == null) {
                return Arrays.toString(stats);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0, count = sections.size(); i < count; i++) {
                Section section = sections.get(i);
                if (section.length == 0) {
                    continue;
                }

                if (section.optional) {
                    boolean nonZero = false;
                    for (int offset = 0; offset < section.length; offset++) {
                        if (stats[section.position + offset] != 0) {
                            nonZero = true;
                            break;
                        }
                    }
                    if (!nonZero) {
                        continue;
                    }
                }

                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(section.label).append(": ");
                if (section.length != 1) {
                    sb.append('[');
                }
                for (int offset = 0; offset < section.length; offset++) {
                    if (offset != 0) {
                        sb.append(", ");
                    }
                    if (section.typePower) {
                        sb.append(BatteryStats.formatCharge(
                                stats[section.position + offset] * NANO_TO_MILLI_MULTIPLIER));
                    } else {
                        sb.append(stats[section.position + offset]);
                    }
                }
                if (section.length != 1) {
                    sb.append(']');
                }
            }
            return sb.toString();
        }
    }
}
