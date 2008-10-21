/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.google.android.net;

import android.os.Parcel;
import android.os.Parcelable;

public class ParentalControlState implements Parcelable {
    public boolean isEnabled;
    public String redirectUrl;

    /**
     * Used to read a ParentalControlStatus from a Parcel.
     */
    public static final Parcelable.Creator<ParentalControlState> CREATOR =
        new Parcelable.Creator<ParentalControlState>() {
              public ParentalControlState createFromParcel(Parcel source) {
                    ParentalControlState status = new ParentalControlState();
                    status.isEnabled = (source.readInt() == 1);
                    status.redirectUrl = source.readString();
                    return status;
              }

              public ParentalControlState[] newArray(int size) {
                  return new ParentalControlState[size];
              }
        };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
      dest.writeInt(isEnabled ? 1 : 0);
      dest.writeString(redirectUrl);
    }

    @Override
    public String toString() {
        return isEnabled + ", " + redirectUrl;
    }
};
