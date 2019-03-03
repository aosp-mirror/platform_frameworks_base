/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Provides the conference information (defined in RFC 4575) for IMS conference call.
 *
 * @hide
 */
@SystemApi
public final class ImsConferenceState implements Parcelable {
    /**
     * conference-info : user
     */
    // user (String) : Tel or SIP URI
    public static final String USER = "user";
    // user > display text (String)
    public static final String DISPLAY_TEXT = "display-text";
    // user > endpoint (String) : URI or GRUU or Phone number
    public static final String ENDPOINT = "endpoint";
    // user > endpoint > status
    public static final String STATUS = "status";

    /**
     * status-type (String) :
     * "pending" : Endpoint is not yet in the session, but it is anticipated that he/she will
     *      join in the near future.
     * "dialing-out" : Focus has dialed out to connect the endpoint to the conference,
     *      but the endpoint is not yet in the roster (probably being authenticated).
     * "dialing-in" : Endpoint is dialing into the conference, not yet in the roster
     *      (probably being authenticated).
     * "alerting" : PSTN alerting or SIP 180 Ringing was returned for the outbound call;
     *      endpoint is being alerted.
     * "on-hold" : Active signaling dialog exists between an endpoint and a focus,
     *      but endpoint is "on-hold" for this conference, i.e., he/she is neither "hearing"
     *      the conference mix nor is his/her media being mixed in the conference.
     * "connected" : Endpoint is a participant in the conference. Depending on the media policies,
     *      he/she can send and receive media to and from other participants.
     * "disconnecting" : Focus is in the process of disconnecting the endpoint
     *      (e.g. in SIP a DISCONNECT or BYE was sent to the endpoint).
     * "disconnected" : Endpoint is not a participant in the conference, and no active dialog
     *      exists between the endpoint and the focus.
     * "muted-via-focus" : Active signaling dialog exists beween an endpoint and a focus and
     *      the endpoint can "listen" to the conference, but the endpoint's media is not being
     *      mixed into the conference.
     * "connect-fail" : Endpoint fails to join the conference by rejecting the conference call.
     */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DIALING_OUT = "dialing-out";
    public static final String STATUS_DIALING_IN = "dialing-in";
    public static final String STATUS_ALERTING = "alerting";
    public static final String STATUS_ON_HOLD = "on-hold";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISCONNECTING = "disconnecting";
    public static final String STATUS_DISCONNECTED = "disconnected";
    public static final String STATUS_MUTED_VIA_FOCUS = "muted-via-focus";
    public static final String STATUS_CONNECT_FAIL = "connect-fail";
    public static final String STATUS_SEND_ONLY = "sendonly";
    public static final String STATUS_SEND_RECV = "sendrecv";

    /**
     * conference-info : SIP status code (integer)
     */
    public static final String SIP_STATUS_CODE = "sipstatuscode";

    public final HashMap<String, Bundle> mParticipants = new HashMap<String, Bundle>();

    /** @hide */
    public ImsConferenceState() {
    }

    private ImsConferenceState(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mParticipants.size());

        if (mParticipants.size() > 0) {
            Set<Entry<String, Bundle>> entries = mParticipants.entrySet();

            if (entries != null) {
                Iterator<Entry<String, Bundle>> iterator = entries.iterator();

                while (iterator.hasNext()) {
                    Entry<String, Bundle> entry = iterator.next();

                    out.writeString(entry.getKey());
                    out.writeParcelable(entry.getValue(), 0);
                }
            }
        }
    }

    private void readFromParcel(Parcel in) {
        int size = in.readInt();

        for (int i = 0; i < size; ++i) {
            String user = in.readString();
            Bundle state = in.readParcelable(null);
            mParticipants.put(user, state);
        }
    }

    public static final @android.annotation.NonNull Creator<ImsConferenceState> CREATOR =
            new Creator<ImsConferenceState>() {
        @Override
        public ImsConferenceState createFromParcel(Parcel in) {
            return new ImsConferenceState(in);
        }

        @Override
        public ImsConferenceState[] newArray(int size) {
            return new ImsConferenceState[size];
        }
    };

    /**
     * Translates an {@code ImsConferenceState} status type to a telecom connection state.
     *
     * @param status The status type.
     * @return The corresponding {@link android.telecom.Connection} state.
     */
    public static int getConnectionStateForStatus(String status) {
        if (status.equals(STATUS_PENDING)) {
            return Connection.STATE_INITIALIZING;
        } else if (status.equals(STATUS_DIALING_IN)) {
            return Connection.STATE_RINGING;
        } else if (status.equals(STATUS_ALERTING) ||
                status.equals(STATUS_DIALING_OUT)) {
            return Connection.STATE_DIALING;
        } else if (status.equals(STATUS_ON_HOLD) ||
                status.equals(STATUS_SEND_ONLY)) {
            return Connection.STATE_HOLDING;
        } else if (status.equals(STATUS_CONNECTED) ||
                status.equals(STATUS_MUTED_VIA_FOCUS) ||
                status.equals(STATUS_DISCONNECTING) ||
                status.equals(STATUS_SEND_RECV)) {
            return Connection.STATE_ACTIVE;
        } else if (status.equals(STATUS_DISCONNECTED)) {
            return Connection.STATE_DISCONNECTED;
        }
        return Call.STATE_ACTIVE;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(ImsConferenceState.class.getSimpleName());
        sb.append(" ");
        if (mParticipants.size() > 0) {
            Set<Entry<String, Bundle>> entries = mParticipants.entrySet();

            if (entries != null) {
                Iterator<Entry<String, Bundle>> iterator = entries.iterator();
                sb.append("<");
                while (iterator.hasNext()) {
                    Entry<String, Bundle> entry = iterator.next();
                    sb.append(Log.pii(entry.getKey()));
                    sb.append(": ");
                    Bundle participantData = entry.getValue();

                    for (String key : participantData.keySet()) {
                        sb.append(key);
                        sb.append("=");
                        if (ENDPOINT.equals(key) || USER.equals(key)) {
                            sb.append(Log.pii(participantData.get(key)));
                        } else {
                            sb.append(participantData.get(key));
                        }
                        sb.append(", ");
                    }
                }
                sb.append(">");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
