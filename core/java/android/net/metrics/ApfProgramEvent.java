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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.android.internal.util.MessageUtils;

/**
 * An event logged when there is a change or event that requires updating the
 * the APF program in place with a new APF program.
 * {@hide}
 */
@SystemApi
public final class ApfProgramEvent implements Parcelable {

    // Bitflag constants describing what an Apf program filters.
    // Bits are indexeds from LSB to MSB, starting at index 0.
    // TODO: use @IntDef
    public static final int FLAG_MULTICAST_FILTER_ON = 0;
    public static final int FLAG_HAS_IPV4_ADDRESS    = 1;

    public final long lifetime;     // Lifetime of the program in seconds
    public final int filteredRas;   // Number of RAs filtered by the APF program
    public final int currentRas;    // Total number of current RAs at generation time
    public final int programLength; // Length of the APF program in bytes
    public final int flags;         // Bitfield compound of FLAG_* constants

    /** {@hide} */
    public ApfProgramEvent(
            long lifetime, int filteredRas, int currentRas, int programLength, int flags) {
        this.lifetime = lifetime;
        this.filteredRas = filteredRas;
        this.currentRas = currentRas;
        this.programLength = programLength;
        this.flags = flags;
    }

    private ApfProgramEvent(Parcel in) {
        this.lifetime = in.readLong();
        this.filteredRas = in.readInt();
        this.currentRas = in.readInt();
        this.programLength = in.readInt();
        this.flags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(lifetime);
        out.writeInt(filteredRas);
        out.writeInt(currentRas);
        out.writeInt(programLength);
        out.writeInt(flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        String lifetimeString = (lifetime < Long.MAX_VALUE) ? lifetime + "s" : "forever";
        return String.format("ApfProgramEvent(%d/%d RAs %dB %s %s)",
                filteredRas, currentRas, programLength, lifetimeString, namesOf(flags));
    }

    public static final Parcelable.Creator<ApfProgramEvent> CREATOR
            = new Parcelable.Creator<ApfProgramEvent>() {
        public ApfProgramEvent createFromParcel(Parcel in) {
            return new ApfProgramEvent(in);
        }

        public ApfProgramEvent[] newArray(int size) {
            return new ApfProgramEvent[size];
        }
    };

    /** {@hide} */
    public static int flagsFor(boolean hasIPv4, boolean multicastFilterOn) {
        int bitfield = 0;
        if (hasIPv4) {
            bitfield |= (1 << FLAG_HAS_IPV4_ADDRESS);
        }
        if (multicastFilterOn) {
            bitfield |= (1 << FLAG_MULTICAST_FILTER_ON);
        }
        return bitfield;
    }

    // TODO: consider using java.util.BitSet
    private static int[] bitflagsOf(int bitfield) {
        int[] flags = new int[Integer.bitCount(bitfield)];
        int i = 0;
        int bitflag = 0;
        while (bitfield != 0) {
          if ((bitfield & 1) != 0) {
              flags[i++] = bitflag;
          }
          bitflag++;
          bitfield = bitfield >>> 1;
        }
        return flags;
    }

    private static String namesOf(int bitfields) {
        return Arrays.stream(bitflagsOf(bitfields))
                .mapToObj(i -> Decoder.constants.get(i))
                .collect(Collectors.joining(", "));
    }

    final static class Decoder {
        static final SparseArray<String> constants =
                MessageUtils.findMessageNames(
                       new Class[]{ApfProgramEvent.class}, new String[]{"FLAG_"});
    }
}
