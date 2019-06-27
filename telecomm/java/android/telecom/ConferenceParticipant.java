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
import android.telephony.PhoneNumberUtils;
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
     * The direction of the call;
     * {@link Call.Details#DIRECTION_INCOMING} for incoming calls, or
     * {@link Call.Details#DIRECTION_OUTGOING} for outgoing calls.
     */
    private int mCallDirection;

    /**
     * Creates an instance of {@code ConferenceParticipant}.
     *
     * @param handle      The conference participant's handle (e.g., phone number).
     * @param displayName The display name for the participant.
     * @param endpoint    The enpoint Uri which uniquely identifies this conference participant.
     * @param state       The state of the participant in the conference.
     * @param callDirection The direction of the call (incoming/outgoing).
     */
    public ConferenceParticipant(Uri handle, String displayName, Uri endpoint, int state,
            int callDirection) {
        mHandle = handle;
        mDisplayName = displayName;
        mEndpoint = endpoint;
        mState = state;
        mCallDirection = callDirection;
    }

    /**
     * Responsible for creating {@code ConferenceParticipant} objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<ConferenceParticipant> CREATOR =
            new Parcelable.Creator<ConferenceParticipant>() {

                @Override
                public ConferenceParticipant createFromParcel(Parcel source) {
                    ClassLoader classLoader = ParcelableCall.class.getClassLoader();
                    Uri handle = source.readParcelable(classLoader);
                    String displayName = source.readString();
                    Uri endpoint = source.readParcelable(classLoader);
                    int state = source.readInt();
                    long connectTime = source.readLong();
                    long elapsedRealTime = source.readLong();
                    int callDirection = source.readInt();
                    ConferenceParticipant participant =
                            new ConferenceParticipant(handle, displayName, endpoint, state,
                                    callDirection);
                    participant.setConnectTime(connectTime);
                    participant.setConnectElapsedTime(elapsedRealTime);
                    participant.setCallDirection(callDirection);
                    return participant;
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
        dest.writeLong(mConnectTime);
        dest.writeLong(mConnectElapsedTime);
        dest.writeInt(mCallDirection);
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
        sb.append(" Direction: ");
        sb.append(getCallDirection() == Call.Details.DIRECTION_INCOMING ? "Incoming" : "Outgoing");
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
     * The connect elapsed time of the participant to the conference.
     */
    public long getConnectElapsedTime() {
        return mConnectElapsedTime;
    }

    public void setConnectElapsedTime(long connectElapsedTime) {
        mConnectElapsedTime = connectElapsedTime;
    }

    /**
     * @return The direction of the call (incoming/outgoing).
     */
    public @Call.Details.CallDirection int getCallDirection() {
        return mCallDirection;
    }

    /**
     * Sets the direction of the call.
     * @param callDirection Whether the call is incoming or outgoing.
     */
    public void setCallDirection(@Call.Details.CallDirection int callDirection) {
        mCallDirection = callDirection;
    }

    /**
     * Attempts to build a tel: style URI from a conference participant.
     * Conference event package data contains SIP URIs, so we try to extract the phone number and
     * format into a typical tel: style URI.
     *
     * @param address The conference participant's address.
     * @param countryIso The country ISO of the current subscription; used when formatting the
     *                   participant phone number to E.164 format.
     * @return The participant's address URI.
     * @hide
     */
    @VisibleForTesting
    public static Uri getParticipantAddress(Uri address, String countryIso) {
        if (address == null) {
            return address;
        }
        // Even if address is already in tel: format, still parse it and rebuild.
        // This is to recognize tel URIs such as:
        // tel:6505551212;phone-context=ims.mnc012.mcc034.3gppnetwork.org

        // Conference event package participants are identified using SIP URIs (see RFC3261).
        // A valid SIP uri has the format: sip:user:password@host:port;uri-parameters?headers
        // Per RFC3261, the "user" can be a telephone number.
        // For example: sip:1650555121;phone-context=blah.com@host.com
        // In this case, the phone number is in the user field of the URI, and the parameters can be
        // ignored.
        //
        // A SIP URI can also specify a phone number in a format similar to:
        // sip:+1-212-555-1212@something.com;user=phone
        // In this case, the phone number is again in user field and the parameters can be ignored.
        // We can get the user field in these instances by splitting the string on the @, ;, or :
        // and looking at the first found item.
        String number = address.getSchemeSpecificPart();
        if (TextUtils.isEmpty(number)) {
            return address;
        }

        String numberParts[] = number.split("[@;:]");
        if (numberParts.length == 0) {
            return address;
        }
        number = numberParts[0];

        // Attempt to format the number in E.164 format and use that as part of the TEL URI.
        // RFC2806 recommends to format telephone numbers using E.164 since it is independent of
        // how the dialing of said numbers takes place.
        // If conversion to E.164 fails, the returned value is null.  In that case, fallback to the
        // number which was in the CEP data.
        String formattedNumber = null;
        if (!TextUtils.isEmpty(countryIso)) {
            formattedNumber = PhoneNumberUtils.formatNumberToE164(number, countryIso);
        }

        return Uri.fromParts(PhoneAccount.SCHEME_TEL,
                formattedNumber != null ? formattedNumber : number, null);
    }
}
