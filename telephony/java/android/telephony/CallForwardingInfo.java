/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Defines the call forwarding information.
 * @hide
 */
@SystemApi
public final class CallForwardingInfo implements Parcelable {
    private static final String TAG = "CallForwardingInfo";

    /**
     * Indicates that call forwarding reason is "unconditional".
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_UNCONDITIONAL = 0;

    /**
     * Indicates the call forwarding status is "busy".
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_BUSY = 1;

    /**
     * Indicates the call forwarding reason is "no reply".
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_NO_REPLY = 2;

    /**
     * Indicates the call forwarding reason is "not reachable".
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_NOT_REACHABLE = 3;

    /**
     * Indicates the call forwarding reason is "all", for setting all call forwarding reasons
     * simultaneously (unconditional, busy, no reply, and not reachable).
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_ALL = 4;

    /**
     * Indicates the call forwarding reason is "all_conditional", for setting all conditional call
     * forwarding reasons simultaneously (busy, no reply, and not reachable).
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    public static final int REASON_ALL_CONDITIONAL = 5;

    /**
     * Call forwarding reason types
     * @hide
     */
    @IntDef(prefix = { "REASON_" }, value = {
        REASON_UNCONDITIONAL,
        REASON_BUSY,
        REASON_NO_REPLY,
        REASON_NOT_REACHABLE,
        REASON_ALL,
        REASON_ALL_CONDITIONAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallForwardingReason {
    }

    /**
     * Whether call forwarding is enabled for this reason.
     */
    private boolean mEnabled;

    /**
     * The call forwarding reason indicates the condition under which calls will be forwarded.
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    private int mReason;

    /**
     * The phone number to which calls will be forwarded.
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10 - 7.11 Call forwarding number
     *            and conditions +CCFC
     */
    private String mNumber;

    /**
     * Gets the timeout (in seconds) before the forwarding is attempted.
     */
    private int mTimeSeconds;

    /**
     * Construct a CallForwardingInfo.
     *
     * @param enabled Whether to enable call forwarding for the reason specified
     *                in {@link #getReason()}.
     * @param reason the call forwarding reason
     * @param number the phone number to which calls will be forwarded
     * @param timeSeconds the timeout (in seconds) before the forwarding is attempted
     */
    public CallForwardingInfo(boolean enabled, @CallForwardingReason int reason,
            @Nullable String number, int timeSeconds) {
        mEnabled = enabled;
        mReason = reason;
        mNumber = number;
        mTimeSeconds = timeSeconds;
    }

    /**
     * Whether call forwarding is enabled for the reason from {@link #getReason()}.
     *
     * @return {@code true} if enabled, {@code false} otherwise.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Returns the call forwarding reason. The call forwarding reason indicates the condition
     * under which calls will be forwarded. For example, {@link #REASON_NO_REPLY} indicates
     * that calls will be forwarded when the user fails to answer the call.
     *
     * @return the call forwarding reason.
     */
    public @CallForwardingReason int getReason() {
        return mReason;
    }

    /**
     * Returns the phone number to which calls will be forwarded.
     *
     * @return the number calls will be forwarded to, or {@code null} if call forwarding
     *         is disabled.
     */
    @Nullable
    public String getNumber() {
        return mNumber;
    }

    /**
     * Gets the timeout (in seconds) before forwarding is attempted. For example,
     * if {@link #REASON_NO_REPLY} is the call forwarding reason, the device will wait this
     * duration of time before forwarding the call to the number returned by {@link #getNumber()}.
     *
     * Reference: 3GPP TS 27.007 version 10.3.0 Release 10
     *            7.11 Call forwarding number and conditions +CCFC
     *
     * @return the timeout (in seconds) before the forwarding is attempted.
     */
    @SuppressLint("MethodNameUnits")
    public int getTimeoutSeconds() {
        return mTimeSeconds;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mNumber);
        out.writeBoolean(mEnabled);
        out.writeInt(mReason);
        out.writeInt(mTimeSeconds);
    }

    private CallForwardingInfo(Parcel in) {
        mNumber = in.readString();
        mEnabled = in.readBoolean();
        mReason = in.readInt();
        mTimeSeconds = in.readInt();
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof CallForwardingInfo)) {
            return false;
        }

        CallForwardingInfo other = (CallForwardingInfo) o;
        return mEnabled == other.mEnabled
                && mNumber == other.mNumber
                && mReason == other.mReason
                && mTimeSeconds == other.mTimeSeconds;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mEnabled, mNumber, mReason, mTimeSeconds);
    }

    public static final @NonNull Parcelable.Creator<CallForwardingInfo> CREATOR =
            new Parcelable.Creator<CallForwardingInfo>() {
                @Override
                public CallForwardingInfo createFromParcel(Parcel in) {
                    return new CallForwardingInfo(in);
                }

                @Override
                public CallForwardingInfo[] newArray(int size) {
                    return new CallForwardingInfo[size];
                }
            };

    /**
     * @hide
     */
    @Override
    public String toString() {
        return "[CallForwardingInfo: enabled=" + mEnabled
                + ", reason= " + mReason
                + ", timeSec= " + mTimeSeconds + " seconds"
                + ", number=" + Rlog.pii(TAG, mNumber) + "]";
    }
}
