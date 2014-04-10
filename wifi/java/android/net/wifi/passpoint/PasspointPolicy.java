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

public class PasspointPolicy implements Parcelable {

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
    public static final Creator<PasspointPolicy> CREATOR =
            new Creator<PasspointPolicy>() {
                @Override
                public PasspointPolicy createFromParcel(Parcel in) {
                    return null;
                }

                @Override
                public PasspointPolicy[] newArray(int size) {
                    return new PasspointPolicy[size];
                }
            };
}
