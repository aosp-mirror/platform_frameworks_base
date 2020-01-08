/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.BackupUtils;
import android.util.Range;
import android.util.RecurrenceRule;

import com.android.internal.util.Preconditions;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.Objects;

/**
 * Policy for networks matching a {@link NetworkTemplate}, including usage cycle
 * and limits to be enforced.
 *
 * @hide
 */
public class NetworkPolicy implements Parcelable, Comparable<NetworkPolicy> {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_RULE = 2;
    private static final int VERSION_RAPID = 3;

    public static final int CYCLE_NONE = -1;
    public static final long WARNING_DISABLED = -1;
    public static final long LIMIT_DISABLED = -1;
    public static final long SNOOZE_NEVER = -1;

    @UnsupportedAppUsage
    public NetworkTemplate template;
    public RecurrenceRule cycleRule;
    @UnsupportedAppUsage
    public long warningBytes = WARNING_DISABLED;
    @UnsupportedAppUsage
    public long limitBytes = LIMIT_DISABLED;
    public long lastWarningSnooze = SNOOZE_NEVER;
    public long lastLimitSnooze = SNOOZE_NEVER;
    public long lastRapidSnooze = SNOOZE_NEVER;
    @UnsupportedAppUsage
    @Deprecated public boolean metered = true;
    @UnsupportedAppUsage
    public boolean inferred = false;

    private static final long DEFAULT_MTU = 1500;

    public static RecurrenceRule buildRule(int cycleDay, ZoneId cycleTimezone) {
        if (cycleDay != NetworkPolicy.CYCLE_NONE) {
            return RecurrenceRule.buildRecurringMonthly(cycleDay, cycleTimezone);
        } else {
            return RecurrenceRule.buildNever();
        }
    }

    @Deprecated
    public NetworkPolicy(NetworkTemplate template, int cycleDay, String cycleTimezone,
            long warningBytes, long limitBytes, boolean metered) {
        this(template, cycleDay, cycleTimezone, warningBytes, limitBytes, SNOOZE_NEVER,
                SNOOZE_NEVER, metered, false);
    }

    @Deprecated
    @UnsupportedAppUsage
    public NetworkPolicy(NetworkTemplate template, int cycleDay, String cycleTimezone,
            long warningBytes, long limitBytes, long lastWarningSnooze, long lastLimitSnooze,
            boolean metered, boolean inferred) {
        this(template, buildRule(cycleDay, ZoneId.of(cycleTimezone)), warningBytes,
                limitBytes, lastWarningSnooze, lastLimitSnooze, metered, inferred);
    }

    @Deprecated
    public NetworkPolicy(NetworkTemplate template, RecurrenceRule cycleRule, long warningBytes,
            long limitBytes, long lastWarningSnooze, long lastLimitSnooze, boolean metered,
            boolean inferred) {
        this(template, cycleRule, warningBytes, limitBytes, lastWarningSnooze, lastLimitSnooze,
                SNOOZE_NEVER, metered, inferred);
    }

    public NetworkPolicy(NetworkTemplate template, RecurrenceRule cycleRule, long warningBytes,
            long limitBytes, long lastWarningSnooze, long lastLimitSnooze, long lastRapidSnooze,
            boolean metered, boolean inferred) {
        this.template = Preconditions.checkNotNull(template, "missing NetworkTemplate");
        this.cycleRule = Preconditions.checkNotNull(cycleRule, "missing RecurrenceRule");
        this.warningBytes = warningBytes;
        this.limitBytes = limitBytes;
        this.lastWarningSnooze = lastWarningSnooze;
        this.lastLimitSnooze = lastLimitSnooze;
        this.lastRapidSnooze = lastRapidSnooze;
        this.metered = metered;
        this.inferred = inferred;
    }

    private NetworkPolicy(Parcel source) {
        template = source.readParcelable(null);
        cycleRule = source.readParcelable(null);
        warningBytes = source.readLong();
        limitBytes = source.readLong();
        lastWarningSnooze = source.readLong();
        lastLimitSnooze = source.readLong();
        lastRapidSnooze = source.readLong();
        metered = source.readInt() != 0;
        inferred = source.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(template, flags);
        dest.writeParcelable(cycleRule, flags);
        dest.writeLong(warningBytes);
        dest.writeLong(limitBytes);
        dest.writeLong(lastWarningSnooze);
        dest.writeLong(lastLimitSnooze);
        dest.writeLong(lastRapidSnooze);
        dest.writeInt(metered ? 1 : 0);
        dest.writeInt(inferred ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public Iterator<Range<ZonedDateTime>> cycleIterator() {
        return cycleRule.cycleIterator();
    }

    /**
     * Test if given measurement is over {@link #warningBytes}.
     */
    @UnsupportedAppUsage
    public boolean isOverWarning(long totalBytes) {
        return warningBytes != WARNING_DISABLED && totalBytes >= warningBytes;
    }

    /**
     * Test if given measurement is near enough to {@link #limitBytes} to be
     * considered over-limit.
     */
    @UnsupportedAppUsage
    public boolean isOverLimit(long totalBytes) {
        // over-estimate, since kernel will trigger limit once first packet
        // trips over limit.
        totalBytes += 2 * DEFAULT_MTU;
        return limitBytes != LIMIT_DISABLED && totalBytes >= limitBytes;
    }

    /**
     * Clear any existing snooze values, setting to {@link #SNOOZE_NEVER}.
     */
    @UnsupportedAppUsage
    public void clearSnooze() {
        lastWarningSnooze = SNOOZE_NEVER;
        lastLimitSnooze = SNOOZE_NEVER;
        lastRapidSnooze = SNOOZE_NEVER;
    }

    /**
     * Test if this policy has a cycle defined, after which usage should reset.
     */
    public boolean hasCycle() {
        return cycleRule.cycleIterator().hasNext();
    }

    @Override
    @UnsupportedAppUsage
    public int compareTo(NetworkPolicy another) {
        if (another == null || another.limitBytes == LIMIT_DISABLED) {
            // other value is missing or disabled; we win
            return -1;
        }
        if (limitBytes == LIMIT_DISABLED || another.limitBytes < limitBytes) {
            // we're disabled or other limit is smaller; they win
            return 1;
        }
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(template, cycleRule, warningBytes, limitBytes,
                lastWarningSnooze, lastLimitSnooze, lastRapidSnooze, metered, inferred);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NetworkPolicy) {
            final NetworkPolicy other = (NetworkPolicy) obj;
            return warningBytes == other.warningBytes
                    && limitBytes == other.limitBytes
                    && lastWarningSnooze == other.lastWarningSnooze
                    && lastLimitSnooze == other.lastLimitSnooze
                    && lastRapidSnooze == other.lastRapidSnooze
                    && metered == other.metered
                    && inferred == other.inferred
                    && Objects.equals(template, other.template)
                    && Objects.equals(cycleRule, other.cycleRule);
        }
        return false;
    }

    @Override
    public String toString() {
        return new StringBuilder("NetworkPolicy{")
                .append("template=").append(template)
                .append(" cycleRule=").append(cycleRule)
                .append(" warningBytes=").append(warningBytes)
                .append(" limitBytes=").append(limitBytes)
                .append(" lastWarningSnooze=").append(lastWarningSnooze)
                .append(" lastLimitSnooze=").append(lastLimitSnooze)
                .append(" lastRapidSnooze=").append(lastRapidSnooze)
                .append(" metered=").append(metered)
                .append(" inferred=").append(inferred)
                .append("}").toString();
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<NetworkPolicy> CREATOR = new Creator<NetworkPolicy>() {
        @Override
        public NetworkPolicy createFromParcel(Parcel in) {
            return new NetworkPolicy(in);
        }

        @Override
        public NetworkPolicy[] newArray(int size) {
            return new NetworkPolicy[size];
        }
    };

    public byte[] getBytesForBackup() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(VERSION_RAPID);
        out.write(template.getBytesForBackup());
        cycleRule.writeToStream(out);
        out.writeLong(warningBytes);
        out.writeLong(limitBytes);
        out.writeLong(lastWarningSnooze);
        out.writeLong(lastLimitSnooze);
        out.writeLong(lastRapidSnooze);
        out.writeInt(metered ? 1 : 0);
        out.writeInt(inferred ? 1 : 0);
        return baos.toByteArray();
    }

    public static NetworkPolicy getNetworkPolicyFromBackup(DataInputStream in) throws IOException,
            BackupUtils.BadVersionException {
        final int version = in.readInt();
        if (version < VERSION_INIT || version > VERSION_RAPID) {
            throw new BackupUtils.BadVersionException("Unknown backup version: " + version);
        }

        final NetworkTemplate template = NetworkTemplate.getNetworkTemplateFromBackup(in);
        final RecurrenceRule cycleRule;
        if (version >= VERSION_RULE) {
            cycleRule = new RecurrenceRule(in);
        } else {
            final int cycleDay = in.readInt();
            final String cycleTimezone = BackupUtils.readString(in);
            cycleRule = buildRule(cycleDay, ZoneId.of(cycleTimezone));
        }
        final long warningBytes = in.readLong();
        final long limitBytes = in.readLong();
        final long lastWarningSnooze = in.readLong();
        final long lastLimitSnooze = in.readLong();
        final long lastRapidSnooze;
        if (version >= VERSION_RAPID) {
            lastRapidSnooze = in.readLong();
        } else {
            lastRapidSnooze = SNOOZE_NEVER;
        }
        final boolean metered = in.readInt() == 1;
        final boolean inferred = in.readInt() == 1;
        return new NetworkPolicy(template, cycleRule, warningBytes, limitBytes, lastWarningSnooze,
                lastLimitSnooze, lastRapidSnooze, metered, inferred);
    }
}
