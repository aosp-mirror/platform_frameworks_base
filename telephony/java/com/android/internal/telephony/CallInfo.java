/*
** Copyright 2013, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

/**
 *  A parcelable holder class of Call information data.
 */
public class CallInfo implements Parcelable {

    /**
     * Endpoint to which the call is connected.
     * This could be the dialed value for outgoing calls or the caller id of incoming calls.
     */
    private String handle;

    public CallInfo(String handle) {
        this.handle = handle;
    }

    public String getHandle() {
        return handle;
    }

    //
    // Parcelling related code below here.
    //

    /**
     * Responsible for creating CallInfo objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<CallInfo> CREATOR
            = new Parcelable.Creator<CallInfo> () {

        @Override
        public CallInfo createFromParcel(Parcel source) {
            return new CallInfo(source.readString());
        }

        @Override
        public CallInfo[] newArray(int size) {
            return new CallInfo[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes CallInfo object into a serializeable Parcel.
     */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(handle);
    }
}
