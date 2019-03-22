/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * Class that stores information specific to voice network registration.
 * @hide
 */
public class VoiceSpecificRegistrationInfo implements Parcelable{
    /**
     * oncurrent services support indicator. if
     * registered on a CDMA system.
     * false - Concurrent services not supported,
     * true - Concurrent services supported
     */
    public final boolean cssSupported;

    /**
     * TSB-58 Roaming Indicator if registered
     * on a CDMA or EVDO system or -1 if not.
     * Valid values are 0-255.
     */
    public final int roamingIndicator;

    /**
     * indicates whether the current system is in the
     * PRL if registered on a CDMA or EVDO system or -1 if
     * not. 0=not in the PRL, 1=in the PRL
     */
    public final int systemIsInPrl;

    /**
     * default Roaming Indicator from the PRL,
     * if registered on a CDMA or EVDO system or -1 if not.
     * Valid values are 0-255.
     */
    public final int defaultRoamingIndicator;

    VoiceSpecificRegistrationInfo(boolean cssSupported, int roamingIndicator, int systemIsInPrl,
                                  int defaultRoamingIndicator) {
        this.cssSupported = cssSupported;
        this.roamingIndicator = roamingIndicator;
        this.systemIsInPrl = systemIsInPrl;
        this.defaultRoamingIndicator = defaultRoamingIndicator;
    }

    private VoiceSpecificRegistrationInfo(Parcel source) {
        this.cssSupported = source.readBoolean();
        this.roamingIndicator = source.readInt();
        this.systemIsInPrl = source.readInt();
        this.defaultRoamingIndicator = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(cssSupported);
        dest.writeInt(roamingIndicator);
        dest.writeInt(systemIsInPrl);
        dest.writeInt(defaultRoamingIndicator);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "VoiceSpecificRegistrationInfo {"
                + " mCssSupported=" + cssSupported
                + " mRoamingIndicator=" + roamingIndicator
                + " mSystemIsInPrl=" + systemIsInPrl
                + " mDefaultRoamingIndicator=" + defaultRoamingIndicator + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(cssSupported, roamingIndicator, systemIsInPrl,
                defaultRoamingIndicator);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof VoiceSpecificRegistrationInfo)) {
            return false;
        }

        VoiceSpecificRegistrationInfo other = (VoiceSpecificRegistrationInfo) o;
        return this.cssSupported == other.cssSupported
                && this.roamingIndicator == other.roamingIndicator
                && this.systemIsInPrl == other.systemIsInPrl
                && this.defaultRoamingIndicator == other.defaultRoamingIndicator;
    }


    public static final @NonNull Parcelable.Creator<VoiceSpecificRegistrationInfo> CREATOR =
            new Parcelable.Creator<VoiceSpecificRegistrationInfo>() {
                @Override
                public VoiceSpecificRegistrationInfo createFromParcel(Parcel source) {
                    return new VoiceSpecificRegistrationInfo(source);
                }

                @Override
                public VoiceSpecificRegistrationInfo[] newArray(int size) {
                    return new VoiceSpecificRegistrationInfo[size];
                }
            };
}
