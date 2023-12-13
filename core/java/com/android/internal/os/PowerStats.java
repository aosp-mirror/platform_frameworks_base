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
import java.util.Arrays;
import java.util.Objects;

/**
 * Container for power stats, acquired by various PowerStatsCollector classes. See subclasses for
 * details.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class PowerStats {
    private static final String TAG = "PowerStats";

    private static final BatteryStatsHistory.VarintParceler VARINT_PARCELER =
            new BatteryStatsHistory.VarintParceler();
    private static final byte PARCEL_FORMAT_VERSION = 1;

    private static final int PARCEL_FORMAT_VERSION_MASK = 0x000000FF;
    private static final int PARCEL_FORMAT_VERSION_SHIFT =
            Integer.numberOfTrailingZeros(PARCEL_FORMAT_VERSION_MASK);
    private static final int STATS_ARRAY_LENGTH_MASK = 0x0000FF00;
    private static final int STATS_ARRAY_LENGTH_SHIFT =
            Integer.numberOfTrailingZeros(STATS_ARRAY_LENGTH_MASK);
    public static final int MAX_STATS_ARRAY_LENGTH =
            (1 << Integer.bitCount(STATS_ARRAY_LENGTH_MASK)) - 1;
    private static final int UID_STATS_ARRAY_LENGTH_MASK = 0x00FF0000;
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
        public static final String XML_TAG_DESCRIPTOR = "descriptor";
        private static final String XML_ATTR_ID = "id";
        private static final String XML_ATTR_NAME = "name";
        private static final String XML_ATTR_STATS_ARRAY_LENGTH = "stats-array-length";
        private static final String XML_ATTR_UID_STATS_ARRAY_LENGTH = "uid-stats-array-length";
        private static final String XML_TAG_EXTRAS = "extras";

        /**
         * {@link BatteryConsumer.PowerComponent} (e.g. CPU, WIFI etc) that this snapshot relates
         * to; or a custom power component ID (if the value
         * is &gt;= {@link BatteryConsumer#FIRST_CUSTOM_POWER_COMPONENT_ID}).
         */
        public final int powerComponentId;
        public final String name;

        public final int statsArrayLength;
        public final int uidStatsArrayLength;

        /**
         * Extra parameters specific to the power component, e.g. the availability of power
         * monitors.
         */
        public final PersistableBundle extras;

        public Descriptor(@BatteryConsumer.PowerComponent int powerComponentId,
                int statsArrayLength, int uidStatsArrayLength, @NonNull PersistableBundle extras) {
            this(powerComponentId, BatteryConsumer.powerComponentIdToString(powerComponentId),
                    statsArrayLength, uidStatsArrayLength, extras);
        }

        public Descriptor(int customPowerComponentId, String name, int statsArrayLength,
                int uidStatsArrayLength, PersistableBundle extras) {
            if (statsArrayLength > MAX_STATS_ARRAY_LENGTH) {
                throw new IllegalArgumentException(
                        "statsArrayLength is too high. Max = " + MAX_STATS_ARRAY_LENGTH);
            }
            if (uidStatsArrayLength > MAX_UID_STATS_ARRAY_LENGTH) {
                throw new IllegalArgumentException(
                        "uidStatsArrayLength is too high. Max = " + MAX_UID_STATS_ARRAY_LENGTH);
            }
            this.powerComponentId = customPowerComponentId;
            this.name = name;
            this.statsArrayLength = statsArrayLength;
            this.uidStatsArrayLength = uidStatsArrayLength;
            this.extras = extras;
        }

        /**
         * Writes the Descriptor into the parcel.
         */
        public void writeSummaryToParcel(Parcel parcel) {
            int firstWord = ((PARCEL_FORMAT_VERSION << PARCEL_FORMAT_VERSION_SHIFT)
                             & PARCEL_FORMAT_VERSION_MASK)
                            | ((statsArrayLength << STATS_ARRAY_LENGTH_SHIFT)
                               & STATS_ARRAY_LENGTH_MASK)
                            | ((uidStatsArrayLength << UID_STATS_ARRAY_LENGTH_SHIFT)
                               & UID_STATS_ARRAY_LENGTH_MASK);
            parcel.writeInt(firstWord);
            parcel.writeInt(powerComponentId);
            parcel.writeString(name);
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
            int uidStatsArrayLength =
                    (firstWord & UID_STATS_ARRAY_LENGTH_MASK) >>> UID_STATS_ARRAY_LENGTH_SHIFT;
            int powerComponentId = parcel.readInt();
            String name = parcel.readString();
            PersistableBundle extras = parcel.readPersistableBundle();
            return new Descriptor(powerComponentId, name, statsArrayLength, uidStatsArrayLength,
                    extras);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Descriptor)) return false;
            Descriptor that = (Descriptor) o;
            return powerComponentId == that.powerComponentId
                   && statsArrayLength == that.statsArrayLength
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
            serializer.attributeInt(null, XML_ATTR_UID_STATS_ARRAY_LENGTH, uidStatsArrayLength);
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
                            uidStatsArrayLength = parser.getAttributeInt(null,
                                    XML_ATTR_UID_STATS_ARRAY_LENGTH);
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
                return new Descriptor(powerComponentId, name, statsArrayLength, uidStatsArrayLength,
                        extras);
            } else if (powerComponentId < BatteryConsumer.POWER_COMPONENT_COUNT) {
                return new Descriptor(powerComponentId, statsArrayLength, uidStatsArrayLength,
                        extras);
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
            parcel.setDataPosition(endPos);
        }
    }

    /**
     * Formats the stats as a string suitable to be included in the Battery History dump.
     */
    public String formatForBatteryHistory(String uidPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("duration=").append(durationMs).append(" ").append(descriptor.name);
        if (stats.length > 0) {
            sb.append("=").append(Arrays.toString(stats));
        }
        for (int i = 0; i < uidStats.size(); i++) {
            sb.append(uidPrefix)
                    .append(UserHandle.formatUid(uidStats.keyAt(i)))
                    .append(": ").append(Arrays.toString(uidStats.valueAt(i)));
        }
        return sb.toString();
    }

    /**
     * Prints the contents of the stats snapshot.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("PowerStats: " + descriptor.name + " (" + descriptor.powerComponentId + ')');
        pw.increaseIndent();
        pw.print("duration", durationMs).println();
        for (int i = 0; i < uidStats.size(); i++) {
            pw.print("UID ");
            pw.print(uidStats.keyAt(i));
            pw.print(": ");
            pw.print(Arrays.toString(uidStats.valueAt(i)));
            pw.println();
        }
        pw.decreaseIndent();
    }

    @Override
    public String toString() {
        return "PowerStats: " + formatForBatteryHistory(" UID ");
    }
}
