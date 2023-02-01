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

import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkTemplate.MATCH_BLUETOOTH;
import static android.net.NetworkTemplate.MATCH_CARRIER;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_WIFI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.BackupUtils;
import android.util.Log;
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
import java.util.Set;

/**
 * Policy for networks matching a {@link NetworkTemplate}, including usage cycle
 * and limits to be enforced.
 *
 * @hide
 */
public class NetworkPolicy implements Parcelable, Comparable<NetworkPolicy> {
    private static final String TAG = NetworkPolicy.class.getSimpleName();
    private static final int VERSION_INIT = 1;
    private static final int VERSION_RULE = 2;
    private static final int VERSION_RAPID = 3;

    /**
     * Initial Version of the NetworkTemplate backup serializer.
     */
    private static final int TEMPLATE_BACKUP_VERSION_1_INIT = 1;
    private static final int TEMPLATE_BACKUP_VERSION_2_UNSUPPORTED = 2;
    /**
     * Version of the NetworkTemplate backup serializer that added carrier template support.
     */
    private static final int TEMPLATE_BACKUP_VERSION_3_SUPPORT_CARRIER_TEMPLATE = 3;
    /**
     * Latest Version of the NetworkTemplate Backup Serializer.
     */
    private static final int TEMPLATE_BACKUP_VERSION_LATEST =
            TEMPLATE_BACKUP_VERSION_3_SUPPORT_CARRIER_TEMPLATE;

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
        template = source.readParcelable(null, android.net.NetworkTemplate.class);
        cycleRule = source.readParcelable(null, android.util.RecurrenceRule.class);
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
    public boolean equals(@Nullable Object obj) {
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
        out.write(getNetworkTemplateBytesForBackup());
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

        final NetworkTemplate template = getNetworkTemplateFromBackup(in);
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

    @NonNull
    private byte[] getNetworkTemplateBytesForBackup() throws IOException {
        if (!isTemplatePersistable(this.template)) {
            Log.wtf(TAG, "Trying to backup non-persistable template: " + this);
        }

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(TEMPLATE_BACKUP_VERSION_LATEST);

        out.writeInt(template.getMatchRule());
        final Set<String> subscriberIds = template.getSubscriberIds();
        BackupUtils.writeString(out, subscriberIds.isEmpty()
                ? null : subscriberIds.iterator().next());
        BackupUtils.writeString(out, template.getWifiNetworkKeys().isEmpty()
                ? null : template.getWifiNetworkKeys().iterator().next());
        out.writeInt(template.getMeteredness());

        return baos.toByteArray();
    }

    @NonNull
    private static NetworkTemplate getNetworkTemplateFromBackup(DataInputStream in)
            throws IOException, BackupUtils.BadVersionException {
        int version = in.readInt();
        if (version < TEMPLATE_BACKUP_VERSION_1_INIT || version > TEMPLATE_BACKUP_VERSION_LATEST
                || version == TEMPLATE_BACKUP_VERSION_2_UNSUPPORTED) {
            throw new BackupUtils.BadVersionException("Unknown Backup Serialization Version");
        }

        int matchRule = in.readInt();
        final String subscriberId = BackupUtils.readString(in);
        final String wifiNetworkKey = BackupUtils.readString(in);

        final int metered;
        if (version >= TEMPLATE_BACKUP_VERSION_3_SUPPORT_CARRIER_TEMPLATE) {
            metered = in.readInt();
        } else {
            // For backward compatibility, fill the missing filters from match rules.
            metered = (matchRule == MATCH_MOBILE || matchRule == MATCH_CARRIER)
                    ? METERED_YES : METERED_ALL;
        }

        try {
            final NetworkTemplate.Builder builder = new NetworkTemplate.Builder(matchRule)
                    .setMeteredness(metered);
            if (subscriberId != null) {
                builder.setSubscriberIds(Set.of(subscriberId));
            }
            if (wifiNetworkKey != null) {
                builder.setWifiNetworkKeys(Set.of(wifiNetworkKey));
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new BackupUtils.BadVersionException(
                    "Restored network template contains unknown match rule " + matchRule, e);
        }
    }

    /**
     * Check if the template can be persisted into disk.
     */
    public static boolean isTemplatePersistable(@NonNull NetworkTemplate template) {
        switch (template.getMatchRule()) {
            case MATCH_BLUETOOTH:
            case MATCH_ETHERNET:
                return true;
            case MATCH_CARRIER:
            case MATCH_MOBILE:
                return !template.getSubscriberIds().isEmpty()
                        && template.getMeteredness() == METERED_YES;
            case MATCH_WIFI:
                if (template.getWifiNetworkKeys().isEmpty()
                        && template.getSubscriberIds().isEmpty()) {
                    return false;
                }
                return true;
            default:
                // Don't allow persistable for unknown types or legacy types such as
                // MATCH_MOBILE_WILDCARD, MATCH_PROXY, etc.
                return false;
        }
    }
}
