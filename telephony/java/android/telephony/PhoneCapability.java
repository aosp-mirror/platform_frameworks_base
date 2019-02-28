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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Define capability of a modem group. That is, the capabilities
 * are shared between those modems defined by list of modem IDs.
 * @hide
 */
public class PhoneCapability implements Parcelable {
    public final int maxActiveVoiceCalls;
    public final int maxActiveData;
    public final int max5G;
    public final boolean validationBeforeSwitchSupported;
    public final List<ModemInfo> logicalModemList;

    public PhoneCapability(int maxActiveVoiceCalls, int maxActiveData, int max5G,
            List<ModemInfo> logicalModemList, boolean validationBeforeSwitchSupported) {
        this.maxActiveVoiceCalls = maxActiveVoiceCalls;
        this.maxActiveData = maxActiveData;
        this.max5G = max5G;
        // Make sure it's not null.
        this.logicalModemList = logicalModemList == null ? new ArrayList<>() : logicalModemList;
        this.validationBeforeSwitchSupported = validationBeforeSwitchSupported;
    }

    @Override
    public String toString() {
        return "maxActiveVoiceCalls=" + maxActiveVoiceCalls + " maxActiveData=" + maxActiveData
                + " max5G=" + max5G + "logicalModemList:"
                + Arrays.toString(logicalModemList.toArray());
    }

    private PhoneCapability(Parcel in) {
        maxActiveVoiceCalls = in.readInt();
        maxActiveData = in.readInt();
        max5G = in.readInt();
        validationBeforeSwitchSupported = in.readBoolean();
        logicalModemList = new ArrayList<>();
        in.readList(logicalModemList, ModemInfo.class.getClassLoader());
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxActiveVoiceCalls, maxActiveData, max5G, logicalModemList,
                validationBeforeSwitchSupported);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof PhoneCapability) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        PhoneCapability s = (PhoneCapability) o;

        return (maxActiveVoiceCalls == s.maxActiveVoiceCalls
                && maxActiveData == s.maxActiveData
                && max5G == s.max5G
                && validationBeforeSwitchSupported == s.validationBeforeSwitchSupported
                && logicalModemList.equals(s.logicalModemList));
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @Parcelable.ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel dest, @Parcelable.WriteFlags int flags) {
        dest.writeInt(maxActiveVoiceCalls);
        dest.writeInt(maxActiveData);
        dest.writeInt(max5G);
        dest.writeBoolean(validationBeforeSwitchSupported);
        dest.writeList(logicalModemList);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PhoneCapability> CREATOR = new Parcelable.Creator() {
        public PhoneCapability createFromParcel(Parcel in) {
            return new PhoneCapability(in);
        }

        public PhoneCapability[] newArray(int size) {
            return new PhoneCapability[size];
        }
    };
}
