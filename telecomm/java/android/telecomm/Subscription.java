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

package android.telecomm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a distinct subscription, line of service or call placement method that
 * a {@link ConnectionService} can use to place phone calls.
 */
public class Subscription implements Parcelable {

    public Subscription() {}

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {}

    public static final Parcelable.Creator<Subscription> CREATOR
            = new Parcelable.Creator<Subscription>() {
        public Subscription createFromParcel(Parcel in) {
            return new Subscription(in);
        }

        public Subscription[] newArray(int size) {
            return new Subscription[size];
        }
    };

    private Subscription(Parcel in) {}
}
