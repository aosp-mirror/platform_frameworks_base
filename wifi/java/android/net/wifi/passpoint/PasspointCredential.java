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

public class PasspointCredential implements Parcelable {

    @Override
    public String toString() {
        // TODO
        return null;
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
    public static final Creator<PasspointCredential> CREATOR =
            new Creator<PasspointCredential>() {
                @Override
                public PasspointCredential createFromParcel(Parcel in) {
                    // TODO
                    return null;
                }

                @Override
                public PasspointCredential[] newArray(int size) {
                    return new PasspointCredential[size];
                }
            };
}
