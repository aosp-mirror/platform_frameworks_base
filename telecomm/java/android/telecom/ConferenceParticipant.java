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
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;

/**
 * Parcelable representation of a participant's state in a conference call.
 * @hide
 */
public class ConferenceParticipant implements Parcelable {

    /**
     * RFC5767 states that a SIP URI with an unknown number should use an address of
     * {@code anonymous@anonymous.invalid}.  E.g. the host name is anonymous.invalid.
     */
    private static final String ANONYMOUS_INVALID_HOST = "anonymous.invalid";
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
     * The connect time of the participant.
     */
    private long mConnectTime;

    /**
     * The connect elapsed time of the participant.
     */
    private long mConnectElapsedTime;

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
     * Determines the number presentation for a conference participant.  Per RFC5767, if the host
     * name contains {@code anonymous.invalid} we can assume that there is no valid caller ID
     * information for the caller, otherwise we'll assume that the URI can be shown.
     *
     * @return The number presentation.
     */
    @VisibleForTesting
    public int getParticipantPresentation() {
        Uri address = getHandle();
        if (address == null) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }

        String number = address.getSchemeSpecificPart();
        // If no number, bail early and set restricted presentation.
        if (TextUtils.isEmpty(number)) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }
        // Per RFC3261, the host name portion can also potentially include extra information:
        // E.g. sip:anonymous1@anonymous.invalid;legid=1
        // In this case, hostName will be anonymous.invalid and there is an extra parameter for
        // legid=1.
        // Parameters are optional, and the address (e.g. test@test.com) will always be the first
        // part, with any parameters coming afterwards.
        String [] hostParts = number.split("[;]");
        String addressPart = hostParts[0];

        // Get the number portion from the address part.
        // This will typically be formatted similar to: 6505551212@test.com
        String [] numberParts = addressPart.split("[@]");

        // If we can't parse the host name out of the URI, then there is probably other data
        // present, and is likely a valid SIP URI.
        if (numberParts.length != 2) {
            return PhoneConstants.PRESENTATION_ALLOWED;
        }
        String hostName = numberParts[1];

        // If the hostname portion of the SIP URI is the invalid host string, presentation is
        // restricted.
        if (hostName.equals(ANONYMOUS_INVALID_HOST)) {
            return PhoneConstants.PRESENTATION_RESTRICTED;
        }

        return PhoneConstants.PRESENTATION_ALLOWED;
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
        sb.append(Log.pii(mHandle));
        sb.append(" DisplayName: ");
        sb.append(Log.pii(mDisplayName));
        sb.append(" Endpoint: ");
        sb.append(Log.pii(mEndpoint));
        sb.append(" State: ");
        sb.append(Connection.stateToString(mState));
        sb.append(" ConnectTime: ");
        sb.append(getConnectTime());
        sb.append(" ConnectElapsedTime: ");
        sb.append(getConnectElapsedTime());
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

    /**
     * The connect time of the participant to the conference.
     */
    public long getConnectTime() {
        return mConnectTime;
    }

    public void setConnectTime(long connectTime) {
        this.mConnectTime = connectTime;
    }

    /**
     * The connect elpased time of the participant to the conference.
     */
    public long getConnectElapsedTime() {
        return mConnectElapsedTime;
    }

    public void setConnectElapsedTime(long connectElapsedTime) {
        mConnectElapsedTime = connectElapsedTime;
    }
}
