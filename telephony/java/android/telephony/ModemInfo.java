/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information of a single logical modem indicating
 * its id, supported rats and whether it supports voice or data, etc.
 * @hide
 */
public class ModemInfo implements Parcelable {
    public final int modemId;
    public final int rat; /* bitset */
    public final boolean isVoiceSupported;
    public final boolean isDataSupported;

    // TODO b/121394331: Clean up this class after V1_1.PhoneCapability cleanup.
    public ModemInfo(int modemId) {
        this(modemId, 0, true, true);
    }

    public ModemInfo(int modemId, int rat, boolean isVoiceSupported, boolean isDataSupported) {
        this.modemId = modemId;
        this.rat = rat;
        this.isVoiceSupported = isVoiceSupported;
        this.isDataSupported = isDataSupported;
    }

    public ModemInfo(Parcel in) {
        modemId = in.readInt();
        rat = in.readInt();
        isVoiceSupported = in.readBoolean();
        isDataSupported = in.readBoolean();
    }

    @Override
    public String toString() {
        return "modemId=" + modemId + " rat=" + rat + " isVoiceSupported:" + isVoiceSupported
                + " isDataSupported:" + isDataSupported;
    }

    @Override
    public int hashCode() {
        return Objects.hash(modemId, rat, isVoiceSupported, isDataSupported);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof ModemInfo) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        ModemInfo s = (ModemInfo) o;

        return (modemId == s.modemId
                && rat == s.rat
                && isVoiceSupported == s.isVoiceSupported
                && isDataSupported == s.isDataSupported);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel dest, @WriteFlags int flags) {
        dest.writeInt(modemId);
        dest.writeInt(rat);
        dest.writeBoolean(isVoiceSupported);
        dest.writeBoolean(isDataSupported);
    }

    public static final Parcelable.Creator<ModemInfo> CREATOR = new Parcelable.Creator() {
        public ModemInfo createFromParcel(Parcel in) {
            return new ModemInfo(in);
        }

        public ModemInfo[] newArray(int size) {
            return new ModemInfo[size];
        }
    };
}
