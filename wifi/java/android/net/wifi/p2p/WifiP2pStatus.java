/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * A class representing Wi-fi P2p status
 * @hide
 */
public class WifiP2pStatus implements Parcelable {

    //Comes from the wpa_supplicant
    enum p2p_status_code {
        SUCCESS,
        FAIL_INFO_CURRENTLY_UNAVAILABLE,
        FAIL_INCOMPATIBLE_PARAMS,
        FAIL_LIMIT_REACHED,
        FAIL_INVALID_PARAMS,
        FAIL_UNABLE_TO_ACCOMMODATE,
        FAIL_PREV_PROTOCOL_ERROR,
        FAIL_NO_COMMON_CHANNELS,
        FAIL_UNKNOWN_GROUP,
        FAIL_BOTH_GO_INTENT_15,
        FAIL_INCOMPATIBLE_PROV_METHOD,
        FAIL_REJECTED_BY_USER
    };

    public WifiP2pStatus() {
    }

    //TODO: add support
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    //TODO: implement
    public WifiP2pStatus(WifiP2pStatus source) {
        if (source != null) {
       }
    }

    /** Implement the Parcelable interface {@hide} */
    // STOPSHIP: implement
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pStatus> CREATOR =
        new Creator<WifiP2pStatus>() {
            public WifiP2pStatus createFromParcel(Parcel in) {
                WifiP2pStatus status = new WifiP2pStatus();
                return status;
            }

            public WifiP2pStatus[] newArray(int size) {
                return new WifiP2pStatus[size];
            }
        };
}
