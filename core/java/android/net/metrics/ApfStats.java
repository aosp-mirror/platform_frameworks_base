/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.metrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event logged for an interface with APF capabilities when its IpClient state machine exits.
 * {@hide}
 * @deprecated The event may not be sent in Android S and above. The events
 * are logged by a single caller in the system using signature permissions
 * and that caller is migrating to statsd.
 */
@Deprecated
@SystemApi
public final class ApfStats implements IpConnectivityLog.Event {

    /**
     * time interval in milliseconds these stastistics covers.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final long durationMs;
    /**
     * number of received RAs.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int receivedRas;
    /**
     * number of received RAs matching a known RA.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int matchingRas;
    /**
     * number of received RAs ignored due to the MAX_RAS limit.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int droppedRas;
    /**
     * number of received RAs with a minimum lifetime of 0.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int zeroLifetimeRas;
    /**
     * number of received RAs that could not be parsed.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int parseErrors;
    /**
     * number of APF program updates from receiving RAs.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int programUpdates;
    /**
     * total number of APF program updates.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int programUpdatesAll;
    /**
     * number of APF program updates from allowing multicast traffic.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int programUpdatesAllowingMulticast;
    /**
     * maximum APF program size advertised by hardware.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final int maxProgramSize;

    private ApfStats(Parcel in) {
        this.durationMs = in.readLong();
        this.receivedRas = in.readInt();
        this.matchingRas = in.readInt();
        this.droppedRas = in.readInt();
        this.zeroLifetimeRas = in.readInt();
        this.parseErrors = in.readInt();
        this.programUpdates = in.readInt();
        this.programUpdatesAll = in.readInt();
        this.programUpdatesAllowingMulticast = in.readInt();
        this.maxProgramSize = in.readInt();
    }

    private ApfStats(long durationMs, int receivedRas, int matchingRas, int droppedRas,
            int zeroLifetimeRas, int parseErrors, int programUpdates, int programUpdatesAll,
            int programUpdatesAllowingMulticast, int maxProgramSize) {
        this.durationMs = durationMs;
        this.receivedRas = receivedRas;
        this.matchingRas = matchingRas;
        this.droppedRas = droppedRas;
        this.zeroLifetimeRas = zeroLifetimeRas;
        this.parseErrors = parseErrors;
        this.programUpdates = programUpdates;
        this.programUpdatesAll = programUpdatesAll;
        this.programUpdatesAllowingMulticast = programUpdatesAllowingMulticast;
        this.maxProgramSize = maxProgramSize;
    }

    /**
     * Utility to create an instance of {@link ApfStats}.
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private long mDurationMs;
        private int mReceivedRas;
        private int mMatchingRas;
        private int mDroppedRas;
        private int mZeroLifetimeRas;
        private int mParseErrors;
        private int mProgramUpdates;
        private int mProgramUpdatesAll;
        private int mProgramUpdatesAllowingMulticast;
        private int mMaxProgramSize;

        /**
         * Set the time interval in milliseconds these statistics covers.
         */
        @NonNull
        public Builder setDurationMs(long durationMs) {
            mDurationMs = durationMs;
            return this;
        }

        /**
         * Set the number of received RAs.
         */
        @NonNull
        public Builder setReceivedRas(int receivedRas) {
            mReceivedRas = receivedRas;
            return this;
        }

        /**
         * Set the number of received RAs matching a known RA.
         */
        @NonNull
        public Builder setMatchingRas(int matchingRas) {
            mMatchingRas = matchingRas;
            return this;
        }

        /**
         * Set the number of received RAs ignored due to the MAX_RAS limit.
         */
        @NonNull
        public Builder setDroppedRas(int droppedRas) {
            mDroppedRas = droppedRas;
            return this;
        }

        /**
         * Set the number of received RAs with a minimum lifetime of 0.
         */
        @NonNull
        public Builder setZeroLifetimeRas(int zeroLifetimeRas) {
            mZeroLifetimeRas = zeroLifetimeRas;
            return this;
        }

        /**
         * Set the number of received RAs that could not be parsed.
         */
        @NonNull
        public Builder setParseErrors(int parseErrors) {
            mParseErrors = parseErrors;
            return this;
        }

        /**
         * Set the number of APF program updates from receiving RAs.
         */
        @NonNull
        public Builder setProgramUpdates(int programUpdates) {
            mProgramUpdates = programUpdates;
            return this;
        }

        /**
         * Set the total number of APF program updates.
         */
        @NonNull
        public Builder setProgramUpdatesAll(int programUpdatesAll) {
            mProgramUpdatesAll = programUpdatesAll;
            return this;
        }

        /**
         * Set the number of APF program updates from allowing multicast traffic.
         */
        @NonNull
        public Builder setProgramUpdatesAllowingMulticast(int programUpdatesAllowingMulticast) {
            mProgramUpdatesAllowingMulticast = programUpdatesAllowingMulticast;
            return this;
        }

        /**
         * Set the maximum APF program size advertised by hardware.
         */
        @NonNull
        public Builder setMaxProgramSize(int maxProgramSize) {
            mMaxProgramSize = maxProgramSize;
            return this;
        }

        /**
         * Create a new {@link ApfStats}.
         */
        @NonNull
        public ApfStats build() {
            return new ApfStats(mDurationMs, mReceivedRas, mMatchingRas, mDroppedRas,
                    mZeroLifetimeRas, mParseErrors, mProgramUpdates, mProgramUpdatesAll,
                    mProgramUpdatesAllowingMulticast, mMaxProgramSize);
        }
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(durationMs);
        out.writeInt(receivedRas);
        out.writeInt(matchingRas);
        out.writeInt(droppedRas);
        out.writeInt(zeroLifetimeRas);
        out.writeInt(parseErrors);
        out.writeInt(programUpdates);
        out.writeInt(programUpdatesAll);
        out.writeInt(programUpdatesAllowingMulticast);
        out.writeInt(maxProgramSize);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return new StringBuilder("ApfStats(")
                .append(String.format("%dms ", durationMs))
                .append(String.format("%dB RA: {", maxProgramSize))
                .append(String.format("%d received, ", receivedRas))
                .append(String.format("%d matching, ", matchingRas))
                .append(String.format("%d dropped, ", droppedRas))
                .append(String.format("%d zero lifetime, ", zeroLifetimeRas))
                .append(String.format("%d parse errors}, ", parseErrors))
                .append(String.format("updates: {all: %d, RAs: %d, allow multicast: %d})",
                        programUpdatesAll, programUpdates, programUpdatesAllowingMulticast))
                .toString();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null || !(obj.getClass().equals(ApfStats.class))) return false;
        final ApfStats other = (ApfStats) obj;
        return durationMs == other.durationMs
                && receivedRas == other.receivedRas
                && matchingRas == other.matchingRas
                && droppedRas == other.droppedRas
                && zeroLifetimeRas == other.zeroLifetimeRas
                && parseErrors == other.parseErrors
                && programUpdates == other.programUpdates
                && programUpdatesAll == other.programUpdatesAll
                && programUpdatesAllowingMulticast == other.programUpdatesAllowingMulticast
                && maxProgramSize == other.maxProgramSize;
    }

    /** @hide */
    public static final @android.annotation.NonNull Parcelable.Creator<ApfStats> CREATOR = new Parcelable.Creator<ApfStats>() {
        public ApfStats createFromParcel(Parcel in) {
            return new ApfStats(in);
        }

        public ApfStats[] newArray(int size) {
            return new ApfStats[size];
        }
    };
}
