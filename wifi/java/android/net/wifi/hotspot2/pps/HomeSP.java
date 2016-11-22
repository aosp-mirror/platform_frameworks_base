/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2.pps;

import android.os.Parcelable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

/**
 * Class representing HomeSP subtree in PerProviderSubscription (PPS)
 * Management Object (MO) tree.
 *
 * For more info, refer to Hotspot 2.0 PPS MO defined in section 9.1 of the Hotspot 2.0
 * Release 2 Technical Specification.
 *
 * Currently we only support the nodes that are used by Hotspot 2.0 Release 1.
 *
 * @hide
 */
public final class HomeSP implements Parcelable {
    private static final String TAG = "HomeSP";

    /**
     * FQDN (Fully Qualified Domain Name) of this home service provider.
     */
    public String fqdn = null;

    /**
     * Friendly name of this home service provider.
     */
    public String friendlyName = null;

    /**
     * List of Organization Identifiers (OIs) identifying a roaming consortium of
     * which this provider is a member.
     */
    public long[] roamingConsortiumOIs = null;

    /**
     * Constructor for creating HomeSP with default values.
     */
    public HomeSP() {}

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public HomeSP(HomeSP source) {
        if (source != null) {
            fqdn = source.fqdn;
            friendlyName = source.friendlyName;
            if (source.roamingConsortiumOIs != null) {
                roamingConsortiumOIs = Arrays.copyOf(source.roamingConsortiumOIs,
                                                     source.roamingConsortiumOIs.length);
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(fqdn);
        dest.writeString(friendlyName);
        dest.writeLongArray(roamingConsortiumOIs);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HomeSP)) {
            return false;
        }
        HomeSP that = (HomeSP) thatObject;

        return TextUtils.equals(fqdn, that.fqdn) &&
                TextUtils.equals(friendlyName, that.friendlyName) &&
                Arrays.equals(roamingConsortiumOIs, that.roamingConsortiumOIs);
    }

    /**
     * Validate HomeSP data.
     *
     * @return true on success or false on failure
     */
    public boolean validate() {
        if (TextUtils.isEmpty(fqdn)) {
            Log.d(TAG, "Missing FQDN");
            return false;
        }
        if (TextUtils.isEmpty(friendlyName)) {
            Log.d(TAG, "Missing friendly name");
            return false;
        }
        return true;
    }

    public static final Creator<HomeSP> CREATOR =
        new Creator<HomeSP>() {
            @Override
            public HomeSP createFromParcel(Parcel in) {
                HomeSP homeSp = new HomeSP();
                homeSp.fqdn = in.readString();
                homeSp.friendlyName = in.readString();
                homeSp.roamingConsortiumOIs = in.createLongArray();
                return homeSp;
            }

            @Override
            public HomeSP[] newArray(int size) {
                return new HomeSP[size];
            }
        };
}
