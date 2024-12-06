/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony.ims.feature;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides details on why transmitting IMS traffic failed.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_SUPPORT_IMS_MMTEL_INTERFACE)
@SystemApi
public final class ConnectionFailureInfo implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = "REASON_",
        value = {
            REASON_NONE,
            REASON_ACCESS_DENIED,
            REASON_NAS_FAILURE,
            REASON_RACH_FAILURE,
            REASON_RLC_FAILURE,
            REASON_RRC_REJECT,
            REASON_RRC_TIMEOUT,
            REASON_NO_SERVICE,
            REASON_PDN_NOT_AVAILABLE,
            REASON_RF_BUSY,
            REASON_UNSPECIFIED
        })
    public @interface FailureReason {}

    /** Default value */
    public static final int REASON_NONE = 0;
    /** Access class check failed */
    public static final int REASON_ACCESS_DENIED = 1;
    /** 3GPP Non-access stratum failure */
    public static final int REASON_NAS_FAILURE = 2;
    /** Random access failure */
    public static final int REASON_RACH_FAILURE = 3;
    /** Radio link failure */
    public static final int REASON_RLC_FAILURE = 4;
    /** Radio connection establishment rejected by network */
    public static final int REASON_RRC_REJECT = 5;
    /** Radio connection establishment timed out */
    public static final int REASON_RRC_TIMEOUT = 6;
    /** Device currently not in service */
    public static final int REASON_NO_SERVICE = 7;
    /** The PDN is no longer active */
    public static final int REASON_PDN_NOT_AVAILABLE = 8;
    /** Radio resource is busy with another subscription */
    public static final int REASON_RF_BUSY = 9;
    /** Unspecified reason */
    public static final int REASON_UNSPECIFIED = 0xFFFF;

    private static final SparseArray<String> sReasonMap;
    static {
        sReasonMap = new SparseArray<>();
        sReasonMap.set(REASON_NONE, "NONE");
        sReasonMap.set(REASON_ACCESS_DENIED, "ACCESS_DENIED");
        sReasonMap.set(REASON_NAS_FAILURE, "NAS_FAILURE");
        sReasonMap.set(REASON_RACH_FAILURE, "RACH_FAILURE");
        sReasonMap.set(REASON_RLC_FAILURE, "RLC_FAILURE");
        sReasonMap.set(REASON_RRC_REJECT, "RRC_REJECT");
        sReasonMap.set(REASON_RRC_TIMEOUT, "RRC_TIMEOUT");
        sReasonMap.set(REASON_NO_SERVICE, "NO_SERVICE");
        sReasonMap.set(REASON_PDN_NOT_AVAILABLE, "PDN_NOT_AVAILABLE");
        sReasonMap.set(REASON_RF_BUSY, "RF_BUSY");
        sReasonMap.set(REASON_UNSPECIFIED, "UNSPECIFIED");
    }

    /** The reason of failure */
    private final @FailureReason int mReason;

    /**
     * Failure cause code from network or modem specific to the failure
     *
     * Reference: 3GPP TS 24.401 Annex A (Cause values for EPS mobility management)
     * Reference: 3GPP TS 24.501 Annex A (Cause values for 5GS mobility management)
     */
    private final int mCauseCode;

    /** Retry wait time provided by network in milliseconds */
    private final int mWaitTimeMillis;

    private ConnectionFailureInfo(Parcel in) {
        mReason = in.readInt();
        mCauseCode = in.readInt();
        mWaitTimeMillis = in.readInt();
    }

    /**
     * Constructor.
     *
     * @param reason The reason of failure.
     * @param causeCode Failure cause code from network or modem specific to the failure.
     *        See 3GPP TS 24.401 Annex A (Cause values for EPS mobility management) and
     *        3GPP TS 24.501 Annex A (Cause values for 5GS mobility management).
     * @param waitTimeMillis Retry wait time provided by network in milliseconds.
     * @hide
     */
    public ConnectionFailureInfo(@FailureReason int reason, int causeCode, int waitTimeMillis) {
        mReason = reason;
        mCauseCode = causeCode;
        mWaitTimeMillis = waitTimeMillis;
    }

    /**
     * @return the reason for the failure.
     */
    public @FailureReason int getReason() {
        return mReason;
    }

    /**
     * @return the cause code from the network or modem specific to the failure.
     *         See 3GPP TS 24.401 Annex A (Cause values for EPS mobility management) and
     *         3GPP TS 24.501 Annex A (Cause values for 5GS mobility management).
     */
    public int getCauseCode() {
        return mCauseCode;
    }

    /**
     * @return the retry wait time provided by the network in milliseconds.
     */
    public int getWaitTimeMillis() {
        return mWaitTimeMillis;
    }

    /**
     * @return the string format of {@link ConnectionFailureInfo}
     */
    @NonNull
    @Override
    public String toString() {
        String reason = sReasonMap.get(mReason, "UNKNOWN");
        return "ConnectionFailureInfo :: {" + mReason + " : " + reason + ", "
                + mCauseCode + ", " + mWaitTimeMillis + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mReason);
        out.writeInt(mCauseCode);
        out.writeInt(mWaitTimeMillis);
    }

    public static final @NonNull Creator<ConnectionFailureInfo> CREATOR =
            new Creator<ConnectionFailureInfo>() {
        @Override
        public ConnectionFailureInfo createFromParcel(Parcel in) {
            return new ConnectionFailureInfo(in);
        }

        @Override
        public ConnectionFailureInfo[] newArray(int size) {
            return new ConnectionFailureInfo[size];
        }
    };
}
