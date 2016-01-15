/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
@SystemApi
public class ParcelableCallAnalytics implements Parcelable {
    public static final int CALLTYPE_UNKNOWN = 0;
    public static final int CALLTYPE_INCOMING = 1;
    public static final int CALLTYPE_OUTGOING = 2;

    // Constants for call technology
    public static final int CDMA_PHONE = 0x1;
    public static final int GSM_PHONE = 0x2;
    public static final int IMS_PHONE = 0x4;
    public static final int SIP_PHONE = 0x8;
    public static final int THIRD_PARTY_PHONE = 0x10;

    public static final long MILLIS_IN_5_MINUTES = 1000 * 60 * 5;
    public static final long MILLIS_IN_1_SECOND = 1000;

    public static final int STILL_CONNECTED = -1;

    public static final Parcelable.Creator<ParcelableCallAnalytics> CREATOR =
            new Parcelable.Creator<ParcelableCallAnalytics> () {

                @Override
                public ParcelableCallAnalytics createFromParcel(Parcel in) {
                    return new ParcelableCallAnalytics(in);
                }

                @Override
                public ParcelableCallAnalytics[] newArray(int size) {
                    return new ParcelableCallAnalytics[size];
                }
            };

    // The start time of the call in milliseconds since Jan. 1, 1970, rounded to the nearest
    // 5 minute increment.
    private final long startTimeMillis;

    // The duration of the call, in milliseconds.
    private final long callDurationMillis;

    // ONE OF calltype_unknown, calltype_incoming, or calltype_outgoing
    private final int callType;

    // true if the call came in while another call was in progress or if the user dialed this call
    // while in the middle of another call.
    private final boolean isAdditionalCall;

    // true if the call was interrupted by an incoming or outgoing call.
    private final boolean isInterrupted;

    // bitmask denoting which technologies a call used.
    private final int callTechnologies;

    // Any of the DisconnectCause codes, or STILL_CONNECTED.
    private final int callTerminationCode;

    // Whether the call is an emergency call
    private final boolean isEmergencyCall;

    // The package name of the connection service that this call used.
    private final String connectionService;

    // Whether the call object was created from an existing connection.
    private final boolean isCreatedFromExistingConnection;

    public ParcelableCallAnalytics(long startTimeMillis, long callDurationMillis, int callType,
            boolean isAdditionalCall, boolean isInterrupted, int callTechnologies,
            int callTerminationCode, boolean isEmergencyCall, String connectionService,
            boolean isCreatedFromExistingConnection) {
        this.startTimeMillis = startTimeMillis;
        this.callDurationMillis = callDurationMillis;
        this.callType = callType;
        this.isAdditionalCall = isAdditionalCall;
        this.isInterrupted = isInterrupted;
        this.callTechnologies = callTechnologies;
        this.callTerminationCode = callTerminationCode;
        this.isEmergencyCall = isEmergencyCall;
        this.connectionService = connectionService;
        this.isCreatedFromExistingConnection = isCreatedFromExistingConnection;
    }

    public ParcelableCallAnalytics(Parcel in) {
        startTimeMillis = in.readLong();
        callDurationMillis = in.readLong();
        callType = in.readInt();
        isAdditionalCall = readByteAsBoolean(in);
        isInterrupted = readByteAsBoolean(in);
        callTechnologies = in.readInt();
        callTerminationCode = in.readInt();
        isEmergencyCall = readByteAsBoolean(in);
        connectionService = in.readString();
        isCreatedFromExistingConnection = readByteAsBoolean(in);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(startTimeMillis);
        out.writeLong(callDurationMillis);
        out.writeInt(callType);
        writeBooleanAsByte(out, isAdditionalCall);
        writeBooleanAsByte(out, isInterrupted);
        out.writeInt(callTechnologies);
        out.writeInt(callTerminationCode);
        writeBooleanAsByte(out, isEmergencyCall);
        out.writeString(connectionService);
        writeBooleanAsByte(out, isCreatedFromExistingConnection);
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getCallDurationMillis() {
        return callDurationMillis;
    }

    public int getCallType() {
        return callType;
    }

    public boolean isAdditionalCall() {
        return isAdditionalCall;
    }

    public boolean isInterrupted() {
        return isInterrupted;
    }

    public int getCallTechnologies() {
        return callTechnologies;
    }

    public int getCallTerminationCode() {
        return callTerminationCode;
    }

    public boolean isEmergencyCall() {
        return isEmergencyCall;
    }

    public String getConnectionService() {
        return connectionService;
    }

    public boolean isCreatedFromExistingConnection() {
        return isCreatedFromExistingConnection;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private static void writeBooleanAsByte(Parcel out, boolean b) {
        out.writeByte((byte) (b ? 1 : 0));
    }

    private static boolean readByteAsBoolean(Parcel in) {
        return (in.readByte() == 1);
    }
}
