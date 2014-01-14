/*
 * Copyright 2013, The Android Open Source Project
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

package android.telecomm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Date;

/**
 * A parcelable holder class of Call information data. This class is intended for transfering call
 * information from Telecomm to call services and thus is read-only.
 * TODO(santoscordon): Need final public-facing comments in this file.
 * @hide
 */
public final class CallInfo implements Parcelable {

    /**
     * Endpoint to which the call is connected.
     * This could be the dialed value for outgoing calls or the caller id of incoming calls.
     */
    private final String mHandle;

    // There are 4 timestamps that are important to a call:
    // 1) Created timestamp - The time at which the user explicitly chose to make the call.
    // 2) Connected timestamp - The time at which a call service confirms that it has connected
    //    this call. This happens through a method-call to either newOutgoingCall or newIncomingCall
    //    on CallServiceAdapter. Generally this should coincide roughly to the user physically
    //    hearing/seeing a ring.
    //    TODO(santoscordon): Consider renaming Call-active to better match the others.
    // 3) Call-active timestamp - The time at which the call switches to the active state. This
    //    happens when the user answers an incoming call or an outgoing call was answered by the
    //    other party.
    // 4) Disconnected timestamp - The time at which the call was disconnected.

    /**
     * Persists handle of the other party of this call.
     */
    public CallInfo(String handle) {
        mHandle = handle;
    }

    public String getHandle() {
        return mHandle;
    }

    //
    // Parceling related code below here.
    //

    /**
     * Responsible for creating CallInfo objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<CallInfo> CREATOR =
            new Parcelable.Creator<CallInfo> () {

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
        destination.writeString(mHandle);
    }
}
