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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.util.MessageUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * An event logged when there is a change or event that requires updating the
 * the APF program in place with a new APF program.
 * {@hide}
 */
@TestApi
@SystemApi
public final class ApfProgramEvent implements IpConnectivityLog.Event {

    // Bitflag constants describing what an Apf program filters.
    // Bits are indexeds from LSB to MSB, starting at index 0.
    /** @hide */
    public static final int FLAG_MULTICAST_FILTER_ON = 0;
    /** @hide */
    public static final int FLAG_HAS_IPV4_ADDRESS    = 1;

    /** {@hide} */
    @IntDef(flag = true, value = {FLAG_MULTICAST_FILTER_ON, FLAG_HAS_IPV4_ADDRESS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /** @hide */
    @UnsupportedAppUsage
    public final long lifetime;       // Maximum computed lifetime of the program in seconds
    /** @hide */
    @UnsupportedAppUsage
    public final long actualLifetime; // Effective program lifetime in seconds
    /** @hide */
    @UnsupportedAppUsage
    public final int filteredRas;     // Number of RAs filtered by the APF program
    /** @hide */
    @UnsupportedAppUsage
    public final int currentRas;      // Total number of current RAs at generation time
    /** @hide */
    @UnsupportedAppUsage
    public final int programLength;   // Length of the APF program in bytes
    /** @hide */
    @UnsupportedAppUsage
    public final int flags;           // Bitfield compound of FLAG_* constants

    private ApfProgramEvent(long lifetime, long actualLifetime, int filteredRas, int currentRas,
            int programLength, int flags) {
        this.lifetime = lifetime;
        this.actualLifetime = actualLifetime;
        this.filteredRas = filteredRas;
        this.currentRas = currentRas;
        this.programLength = programLength;
        this.flags = flags;
    }

    private ApfProgramEvent(Parcel in) {
        this.lifetime = in.readLong();
        this.actualLifetime = in.readLong();
        this.filteredRas = in.readInt();
        this.currentRas = in.readInt();
        this.programLength = in.readInt();
        this.flags = in.readInt();
    }

    /**
     * Utility to create an instance of {@link ApfProgramEvent}.
     */
    public static final class Builder {
        private long mLifetime;
        private long mActualLifetime;
        private int mFilteredRas;
        private int mCurrentRas;
        private int mProgramLength;
        private int mFlags;

        /**
         * Set the maximum computed lifetime of the program in seconds.
         */
        @NonNull
        public Builder setLifetime(long lifetime) {
            mLifetime = lifetime;
            return this;
        }

        /**
         * Set the effective program lifetime in seconds.
         */
        @NonNull
        public Builder setActualLifetime(long lifetime) {
            mActualLifetime = lifetime;
            return this;
        }

        /**
         * Set the number of RAs filtered by the APF program.
         */
        @NonNull
        public Builder setFilteredRas(int filteredRas) {
            mFilteredRas = filteredRas;
            return this;
        }

        /**
         * Set the total number of current RAs at generation time.
         */
        @NonNull
        public Builder setCurrentRas(int currentRas) {
            mCurrentRas = currentRas;
            return this;
        }

        /**
         * Set the length of the APF program in bytes.
         */
        @NonNull
        public Builder setProgramLength(int programLength) {
            mProgramLength = programLength;
            return this;
        }

        /**
         * Set the flags describing what an Apf program filters.
         */
        @NonNull
        public Builder setFlags(boolean hasIPv4, boolean multicastFilterOn) {
            mFlags = flagsFor(hasIPv4, multicastFilterOn);
            return this;
        }

        /**
         * Build a new {@link ApfProgramEvent}.
         */
        @NonNull
        public ApfProgramEvent build() {
            return new ApfProgramEvent(mLifetime, mActualLifetime, mFilteredRas, mCurrentRas,
                    mProgramLength, mFlags);
        }
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(lifetime);
        out.writeLong(actualLifetime);
        out.writeInt(filteredRas);
        out.writeInt(currentRas);
        out.writeInt(programLength);
        out.writeInt(flags);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        String lifetimeString = (lifetime < Long.MAX_VALUE) ? lifetime + "s" : "forever";
        return String.format("ApfProgramEvent(%d/%d RAs %dB %ds/%s %s)", filteredRas, currentRas,
                programLength, actualLifetime, lifetimeString, namesOf(flags));
    }

    /** @hide */
    public static final Parcelable.Creator<ApfProgramEvent> CREATOR
            = new Parcelable.Creator<ApfProgramEvent>() {
        public ApfProgramEvent createFromParcel(Parcel in) {
            return new ApfProgramEvent(in);
        }

        public ApfProgramEvent[] newArray(int size) {
            return new ApfProgramEvent[size];
        }
    };

    /** @hide */
    @UnsupportedAppUsage
    public static @Flags int flagsFor(boolean hasIPv4, boolean multicastFilterOn) {
        int bitfield = 0;
        if (hasIPv4) {
            bitfield |= (1 << FLAG_HAS_IPV4_ADDRESS);
        }
        if (multicastFilterOn) {
            bitfield |= (1 << FLAG_MULTICAST_FILTER_ON);
        }
        return bitfield;
    }

    private static String namesOf(@Flags int bitfield) {
        List<String> names = new ArrayList<>(Integer.bitCount(bitfield));
        BitSet set = BitSet.valueOf(new long[]{bitfield & Integer.MAX_VALUE});
        // Only iterate over flag bits which are set.
        for (int bit = set.nextSetBit(0); bit >= 0; bit = set.nextSetBit(bit+1)) {
            names.add(Decoder.constants.get(bit));
        }
        return TextUtils.join("|", names);
    }

    final static class Decoder {
        static final SparseArray<String> constants =
                MessageUtils.findMessageNames(
                       new Class[]{ApfProgramEvent.class}, new String[]{"FLAG_"});
    }
}
