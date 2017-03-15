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
public final class ApfProgramEvent implements Parcelable {

    // Bitflag constants describing what an Apf program filters.
    // Bits are indexeds from LSB to MSB, starting at index 0.
    public static final int FLAG_MULTICAST_FILTER_ON = 0;
    public static final int FLAG_HAS_IPV4_ADDRESS    = 1;

    /** {@hide} */
    @IntDef(flag = true, value = {FLAG_MULTICAST_FILTER_ON, FLAG_HAS_IPV4_ADDRESS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    public long lifetime;       // Maximum computed lifetime of the program in seconds
    public long actualLifetime; // Effective program lifetime in seconds
    public int filteredRas;     // Number of RAs filtered by the APF program
    public int currentRas;      // Total number of current RAs at generation time
    public int programLength;   // Length of the APF program in bytes
    public int flags;           // Bitfield compound of FLAG_* constants

    public ApfProgramEvent() {
    }

    private ApfProgramEvent(Parcel in) {
        this.lifetime = in.readLong();
        this.actualLifetime = in.readLong();
        this.filteredRas = in.readInt();
        this.currentRas = in.readInt();
        this.programLength = in.readInt();
        this.flags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(lifetime);
        out.writeLong(actualLifetime);
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
        return String.format("ApfProgramEvent(%d/%d RAs %dB %ds/%s %s)", filteredRas, currentRas,
                programLength, actualLifetime, lifetimeString, namesOf(flags));
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
