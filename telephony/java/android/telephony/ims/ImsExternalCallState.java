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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Parcelable object to handle MultiEndpoint Dialog Event Package Information.
 * @hide
 */
@SystemApi
public final class ImsExternalCallState implements Parcelable {

    private static final String TAG = "ImsExternalCallState";

    // Dialog States
    /**
     * The external call is in the confirmed dialog state.
     */
    public static final int CALL_STATE_CONFIRMED = 1;
    /**
     * The external call is in the terminated dialog state.
     */
    public static final int CALL_STATE_TERMINATED = 2;

    /**@hide*/
    @IntDef(value = {
                    CALL_STATE_CONFIRMED,
                    CALL_STATE_TERMINATED
            },
            prefix = "CALL_STATE_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExternalCallState {}

    /**@hide*/
    @IntDef(value = {
                    ImsCallProfile.CALL_TYPE_VOICE,
                    ImsCallProfile.CALL_TYPE_VT_TX,
                    ImsCallProfile.CALL_TYPE_VT_RX,
                    ImsCallProfile.CALL_TYPE_VT
            },
            prefix = "CALL_TYPE_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExternalCallType {}



    // Dialog Id
    private int mCallId;
    // Number
    private Uri mAddress;
    private Uri mLocalAddress;
    private boolean mIsPullable;
    // CALL_STATE_CONFIRMED / CALL_STATE_TERMINATED
    private int mCallState;
    // ImsCallProfile#CALL_TYPE_*
    private int mCallType;
    private boolean mIsHeld;

    /** @hide */
    public ImsExternalCallState() {
    }

    /**@hide*/
    public ImsExternalCallState(int callId, Uri address, boolean isPullable,
            @ExternalCallState int callState, int callType, boolean isCallheld) {
        mCallId = callId;
        mAddress = address;
        mIsPullable = isPullable;
        mCallState = callState;
        mCallType = callType;
        mIsHeld = isCallheld;
        Rlog.d(TAG, "ImsExternalCallState = " + this);
    }

    /**@hide*/
    public ImsExternalCallState(int callId, Uri address, Uri localAddress,
            boolean isPullable, @ExternalCallState int callState, int callType,
            boolean isCallheld) {
        mCallId = callId;
        mAddress = address;
        mLocalAddress = localAddress;
        mIsPullable = isPullable;
        mCallState = callState;
        mCallType = callType;
        mIsHeld = isCallheld;
        Rlog.d(TAG, "ImsExternalCallState = " + this);
    }

    /**
     * Create a new ImsExternalCallState instance to contain Multiendpoint Dialog information.
     * @param callId The unique ID of the call, which will be used to identify this external
     *               connection.
     * @param address A {@link Uri} containing the remote address of this external connection.
     * @param localAddress A {@link Uri} containing the local address information.
     * @param isPullable A flag determining if this external connection can be pulled to the current
     *         device.
     * @param callState The state of the external call.
     * @param callType The type of external call.
     * @param isCallheld A flag determining if the external connection is currently held.
     */
    public ImsExternalCallState(@NonNull String callId, @NonNull Uri address,
            @Nullable Uri localAddress, boolean isPullable, @ExternalCallState int callState,
            @ExternalCallType int callType, boolean isCallheld) {
        mCallId = getIdForString(callId);
        mAddress = address;
        mLocalAddress = localAddress;
        mIsPullable = isPullable;
        mCallState = callState;
        mCallType = callType;
        mIsHeld = isCallheld;
        Rlog.d(TAG, "ImsExternalCallState = " + this);
    }

    /** @hide */
    public ImsExternalCallState(Parcel in) {
        mCallId = in.readInt();
        ClassLoader classLoader = ImsExternalCallState.class.getClassLoader();
        mAddress = in.readParcelable(classLoader, android.net.Uri.class);
        mLocalAddress = in.readParcelable(classLoader, android.net.Uri.class);
        mIsPullable = (in.readInt() != 0);
        mCallState = in.readInt();
        mCallType = in.readInt();
        mIsHeld = (in.readInt() != 0);
        Rlog.d(TAG, "ImsExternalCallState const = " + this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCallId);
        out.writeParcelable(mAddress, 0);
        out.writeParcelable(mLocalAddress, 0);
        out.writeInt(mIsPullable ? 1 : 0);
        out.writeInt(mCallState);
        out.writeInt(mCallType);
        out.writeInt(mIsHeld ? 1 : 0);
        Rlog.d(TAG, "ImsExternalCallState writeToParcel = " + out.toString());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ImsExternalCallState> CREATOR =
            new Parcelable.Creator<ImsExternalCallState>() {
        @Override
        public ImsExternalCallState createFromParcel(Parcel in) {
            return new ImsExternalCallState(in);
        }

        @Override
        public ImsExternalCallState[] newArray(int size) {
            return new ImsExternalCallState[size];
        }
    };

    public int getCallId() {
        return mCallId;
    }

    public @NonNull Uri getAddress() {
        return mAddress;
    }

    /**
     * @return A {@link Uri} containing the local address from the Multiendpoint Dialog Information.
     */
    public @Nullable Uri getLocalAddress() {
        return mLocalAddress;
    }

    public boolean isCallPullable() {
        return mIsPullable;
    }

    public @ExternalCallState int getCallState() {
        return mCallState;
    }

    public @ExternalCallType int getCallType() {
        return mCallType;
    }

    public boolean isCallHeld() {
        return mIsHeld;
    }

    @NonNull
    @Override
    public String toString() {
        return "ImsExternalCallState { mCallId = " + mCallId +
                ", mAddress = " + Rlog.pii(TAG, mAddress) +
                ", mLocalAddress = " + Rlog.pii(TAG, mLocalAddress) +
                ", mIsPullable = " + mIsPullable +
                ", mCallState = " + mCallState +
                ", mCallType = " + mCallType +
                ", mIsHeld = " + mIsHeld + "}";
    }

    private int getIdForString(String idString) {
        try {
            return Integer.parseInt(idString);
        } catch (NumberFormatException e) {
            // In the case that there are alphanumeric characters, we will create a hash of the
            // String value as a backup.
            // TODO: Modify call IDs to use Strings as keys instead of integers in telephony/telecom
            return idString.hashCode();
        }
    }
}
