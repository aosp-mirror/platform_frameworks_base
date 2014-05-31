/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net.wifi.passpoint;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

/** @hide */
public class WifiPasspointPolicy implements Parcelable {

    private final static String TAG = "PasspointPolicy";

    /** @hide */
    public static final int HOME_SP = 0;

    /** @hide */
    public static final int ROAMING_PARTNER = 1;

    /** @hide */
    public static final int UNRESTRICTED = 2;

    private String mName;
    private int mCredentialPriority;
    private int mRoamingPriority;
    private String mBssid;
    private String mSsid;
    private WifiPasspointCredential mCredential;
    private int mRestriction;// Permitted values are "HomeSP", "RoamingPartner", or "Unrestricted"
    private boolean mIsHomeSp;

    /** @hide */
    public WifiPasspointPolicy(String name, String ssid,
            String bssid, WifiPasspointCredential pc,
            int restriction, boolean ishomesp) {
        mName = name;
        if (pc != null) {
            mCredentialPriority = pc.getPriority();
        }
        //PerProviderSubscription/<X+>/Policy/PreferredRoamingPartnerList/<X+>/Priority
        mRoamingPriority = 128; //default priority value of 128
        mSsid = ssid;
        mCredential = pc;
        mBssid = bssid;
        mRestriction = restriction;
        mIsHomeSp = ishomesp;
    }

    public String getSsid() {
        return mSsid;
    }

    /** @hide */
    public void setBssid(String bssid) {
        mBssid = bssid;
    }

    public String getBssid() {
        return mBssid;
    }

    /** @hide */
    public void setRestriction(int r) {
        mRestriction = r;
    }

    /** @hide */
    public int getRestriction() {
        return mRestriction;
    }

    /** @hide */
    public void setHomeSp(boolean b) {
        mIsHomeSp = b;
    }

    /** @hide */
    public boolean getHomeSp() {
        return mIsHomeSp;
    }

    /** @hide */
    public void setCredential(WifiPasspointCredential newCredential) {
        mCredential = newCredential;
    }

    public WifiPasspointCredential getCredential() {
        // TODO: return a copy
        return mCredential;
    }

    /** @hide */
    public void setCredentialPriority(int priority) {
        mCredentialPriority = priority;
    }

    /** @hide */
    public void setRoamingPriority(int priority) {
        mRoamingPriority = priority;
    }

    public int getCredentialPriority() {
        return mCredentialPriority;
    }

    public int getRoamingPriority() {
        return mRoamingPriority;
    }

    /** {@inheritDoc} @hide */
    public int compareTo(WifiPasspointPolicy another) {
        Log.d(TAG, "this:" + this);
        Log.d(TAG, "another:" + another);

        if (another == null) {
            return -1;
        } else if (this.mIsHomeSp == true && another.getHomeSp() == false) {
            //home sp priority is higher then roaming
            Log.d(TAG, "compare HomeSP  first, this is HomeSP, another isn't");
            return -1;
        } else if ((this.mIsHomeSp == true && another.getHomeSp() == true)) {
            Log.d(TAG, "both HomeSP");
            //if both home sp, compare credential priority
            if (this.mCredentialPriority < another.getCredentialPriority()) {
                Log.d(TAG, "this priority is higher");
                return -1;
            } else if (this.mCredentialPriority == another.getCredentialPriority()) {
                Log.d(TAG, "both priorities equal");
                //if priority still the same, compare name(ssid)
                if (this.mName.compareTo(another.mName) != 0) {
                    Log.d(TAG, "compare mName return:" + this.mName.compareTo(another.mName));
                    return this.mName.compareTo(another.mName);
                }
                /**
                 *if name still the same, compare credential
                 *the device may has two more credentials(TLS,SIM..etc)
                 *it can associate to one AP(same ssid). so we should compare by credential
                 */
                if (this.mCredential != null && another.mCredential != null) {
                    if (this.mCredential.compareTo(another.mCredential) != 0) {
                        Log.d(TAG,
                                "compare mCredential return:" + this.mName.compareTo(another.mName));
                        return this.mCredential.compareTo(another.mCredential);
                    }
                }
            } else {
                return 1;
            }
        } else if ((this.mIsHomeSp == false && another.getHomeSp() == false)) {
            Log.d(TAG, "both RoamingSp");
            //if both roaming sp, compare roaming priority(preferredRoamingPartnerList/<X+>/priority)
            if (this.mRoamingPriority < another.getRoamingPriority()) {
                Log.d(TAG, "this priority is higher");
                return -1;
            } else if (this.mRoamingPriority == another.getRoamingPriority()) {//priority equals, compare name
                Log.d(TAG, "both priorities equal");
                //if priority still the same, compare name(ssid)
                if (this.mName.compareTo(another.mName) != 0) {
                    Log.d(TAG, "compare mName return:" + this.mName.compareTo(another.mName));
                    return this.mName.compareTo(another.mName);
                }
                //if name still the same, compare credential
                if (this.mCredential != null && another.mCredential != null) {
                    if (this.mCredential.compareTo(another.mCredential) != 0) {
                        Log.d(TAG,
                                "compare mCredential return:"
                                        + this.mCredential.compareTo(another.mCredential));
                        return this.mCredential.compareTo(another.mCredential);
                    }
                }
            } else {
                return 1;
            }
        }

        Log.d(TAG, "both policies equal");
        return 0;
    }

    @Override
    /** @hide */
    public String toString() {
        return "PasspointPolicy: name=" + mName + " CredentialPriority=" + mCredentialPriority +
                " mRoamingPriority" + mRoamingPriority +
                " ssid=" + mSsid + " restriction=" + mRestriction +
                " ishomesp=" + mIsHomeSp + " Credential=" + mCredential;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiPasspointPolicy> CREATOR =
            new Creator<WifiPasspointPolicy>() {
                @Override
                public WifiPasspointPolicy createFromParcel(Parcel in) {
                    return null;
                }

                @Override
                public WifiPasspointPolicy[] newArray(int size) {
                    return new WifiPasspointPolicy[size];
                }
            };
}
