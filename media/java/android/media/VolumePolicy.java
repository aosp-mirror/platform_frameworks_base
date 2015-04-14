/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.media;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** @hide */
public final class VolumePolicy implements Parcelable {
    public static final VolumePolicy DEFAULT = new VolumePolicy(false, false, true, 400);

    /** Allow volume adjustments lower from vibrate to enter ringer mode = silent */
    public final boolean volumeDownToEnterSilent;

    /** Allow volume adjustments higher to exit ringer mode = silent */
    public final boolean volumeUpToExitSilent;

    /** Automatically enter do not disturb when ringer mode = silent */
    public final boolean doNotDisturbWhenSilent;

    /** Only allow volume adjustment from vibrate to silent after this
        number of milliseconds since an adjustment from normal to vibrate. */
    public final int vibrateToSilentDebounce;

    public VolumePolicy(boolean volumeDownToEnterSilent, boolean volumeUpToExitSilent,
            boolean doNotDisturbWhenSilent, int vibrateToSilentDebounce) {
        this.volumeDownToEnterSilent = volumeDownToEnterSilent;
        this.volumeUpToExitSilent = volumeUpToExitSilent;
        this.doNotDisturbWhenSilent = doNotDisturbWhenSilent;
        this.vibrateToSilentDebounce = vibrateToSilentDebounce;
    }

    @Override
    public String toString() {
        return "VolumePolicy[volumeDownToEnterSilent=" + volumeDownToEnterSilent
                + ",volumeUpToExitSilent=" + volumeUpToExitSilent
                + ",doNotDisturbWhenSilent=" + doNotDisturbWhenSilent
                + ",vibrateToSilentDebounce=" + vibrateToSilentDebounce + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(volumeDownToEnterSilent, volumeUpToExitSilent, doNotDisturbWhenSilent,
                vibrateToSilentDebounce);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VolumePolicy)) return false;
        if (o == this) return true;
        final VolumePolicy other = (VolumePolicy) o;
        return other.volumeDownToEnterSilent == volumeDownToEnterSilent
                && other.volumeUpToExitSilent == volumeUpToExitSilent
                && other.doNotDisturbWhenSilent == doNotDisturbWhenSilent
                && other.vibrateToSilentDebounce == vibrateToSilentDebounce;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(volumeDownToEnterSilent ? 1 : 0);
        dest.writeInt(volumeUpToExitSilent ? 1 : 0);
        dest.writeInt(doNotDisturbWhenSilent ? 1 : 0);
        dest.writeInt(vibrateToSilentDebounce);
    }

    public static final Parcelable.Creator<VolumePolicy> CREATOR
            = new Parcelable.Creator<VolumePolicy>() {
        @Override
        public VolumePolicy createFromParcel(Parcel p) {
            return new VolumePolicy(p.readInt() != 0,
                    p.readInt() != 0,
                    p.readInt() != 0,
                    p.readInt());
        }

        @Override
        public VolumePolicy[] newArray(int size) {
            return new VolumePolicy[size];
        }
    };
}