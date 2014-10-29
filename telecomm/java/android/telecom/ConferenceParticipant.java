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
 * limitations under the License
 */

package android.telecom;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable representation of a participant's state in a conference call.
 * @hide
 */
public class ConferenceParticipant implements Parcelable {

    /**
     * The conference participant's handle (e.g., phone number).
     */
    private final Uri mHandle;

    /**
     * The display name for the participant.
     */
    private final String mDisplayName;

    /**
     * The endpoint Uri which uniquely identifies this conference participant.  E.g. for an IMS
     * conference call, this is the endpoint URI for the participant on the IMS conference server.
     */
    private final Uri mEndpoint;

    /**
     * The state of the participant in the conference.
     *
     * @see android.telecom.Connection
     */
    private final int mState;

    /**
     * Creates an instance of {@code ConferenceParticipant}.
     *
     * @param handle      The conference participant's handle (e.g., phone number).
     * @param displayName The display name for the participant.
     * @param endpoint    The enpoint Uri which uniquely identifies this conference participant.
     * @param state       The state of the participant in the conference.
     */
    public ConferenceParticipant(Uri handle, String displayName, Uri endpoint, int state) {
        mHandle = handle;
        mDisplayName = displayName;
        mEndpoint = endpoint;
        mState = state;
    }

    /**
     * Responsible for creating {@code ConferenceParticipant} objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<ConferenceParticipant> CREATOR =
            new Parcelable.Creator<ConferenceParticipant>() {

                @Override
                public ConferenceParticipant createFromParcel(Parcel source) {
                    ClassLoader classLoader = ParcelableCall.class.getClassLoader();
                    Uri handle = source.readParcelable(classLoader);
                    String displayName = source.readString();
                    Uri endpoint = source.readParcelable(classLoader);
                    int state = source.readInt();
                    return new ConferenceParticipant(handle, displayName, endpoint, state);
                }

                @Override
                public ConferenceParticipant[] newArray(int size) {
                    return new ConferenceParticipant[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes the {@code ConferenceParticipant} to a parcel.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mHandle, 0);
        dest.writeString(mDisplayName);
        dest.writeParcelable(mEndpoint, 0);
        dest.writeInt(mState);
    }

    /**
     * Builds a string representation of this instance.
     *
     * @return String representing the conference participant.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ConferenceParticipant Handle: ");
        sb.append(mHandle);
        sb.append(" DisplayName: ");
        sb.append(mDisplayName);
        sb.append(" Endpoint: ");
        sb.append(mEndpoint);
        sb.append(" State: ");
        sb.append(mState);
        sb.append("]");
        return sb.toString();
    }

    /**
     * The conference participant's handle (e.g., phone number).
     */
    public Uri getHandle() {
        return mHandle;
    }

    /**
     * The display name for the participant.
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * The enpoint Uri which uniquely identifies this conference participant.  E.g. for an IMS
     * conference call, this is the endpoint URI for the participant on the IMS conference server.
     */
    public Uri getEndpoint() {
        return mEndpoint;
    }

    /**
     * The state of the participant in the conference.
     *
     * @see android.telecom.Connection
     */
    public int getState() {
        return mState;
    }
}
