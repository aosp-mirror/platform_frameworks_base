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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event logged for an interface with APF capabilities when its IpClient state machine exits.
 * {@hide}
 */
public final class ApfStats implements Parcelable {

    /** time interval in milliseconds these stastistics covers. */
    public long durationMs;
    /** number of received RAs. */
    public int receivedRas;
    /** number of received RAs matching a known RA. */
    public int matchingRas;
    /** number of received RAs ignored due to the MAX_RAS limit. */
    public int droppedRas;
    /** number of received RAs with a minimum lifetime of 0. */
    public int zeroLifetimeRas;
    /** number of received RAs that could not be parsed. */
    public int parseErrors;
    /** number of APF program updates from receiving RAs.. */
    public int programUpdates;
    /** total number of APF program updates. */
    public int programUpdatesAll;
    /** number of APF program updates from allowing multicast traffic. */
    public int programUpdatesAllowingMulticast;
    /** maximum APF program size advertised by hardware. */
    public int maxProgramSize;

    public ApfStats() {
    }

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

    @Override
    public int describeContents() {
        return 0;
    }

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

    public static final Parcelable.Creator<ApfStats> CREATOR = new Parcelable.Creator<ApfStats>() {
        public ApfStats createFromParcel(Parcel in) {
            return new ApfStats(in);
        }

        public ApfStats[] newArray(int size) {
            return new ApfStats[size];
        }
    };
}
