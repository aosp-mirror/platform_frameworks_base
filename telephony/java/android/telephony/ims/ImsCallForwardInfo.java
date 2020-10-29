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
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the call forward information for the supplementary service configuration.
 *
 * @hide
 */
@SystemApi
public final class ImsCallForwardInfo implements Parcelable {

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for unconditional call
     * forwarding. See TC 27.007
     */
    public static final int CDIV_CF_REASON_UNCONDITIONAL = 0;

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for call forwarding
     * when the user is busy.
     */
    public static final int CDIV_CF_REASON_BUSY = 1;

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for call forwarding
     * when there is no reply from the user.
     */
    public static final int CDIV_CF_REASON_NO_REPLY = 2;

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for call forwarding
     * when the user is not reachable.
     */
    public static final int CDIV_CF_REASON_NOT_REACHABLE = 3;

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for setting all call
     * forwarding reasons simultaneously (i.e. unconditional, busy, no reply, and not reachable).
     */
    public static final int CDIV_CF_REASON_ALL = 4;

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604) call forwarding reason for setting all
     * conditional call forwarding reasons simultaneously (i.e. if busy, if no reply, and if not
     * reachable).
     */
    public static final int CDIV_CF_REASON_ALL_CONDITIONAL = 5;

    /**
     * CDIV (Communication Diversion) IMS only call forwarding reason for call forwarding when the
     * user is not logged in.
     */
    public static final int CDIV_CF_REASON_NOT_LOGGED_IN = 6;

    /**@hide*/
    @IntDef(prefix = {"CDIV_CF_REASON_"}, value = {
            CDIV_CF_REASON_UNCONDITIONAL,
            CDIV_CF_REASON_BUSY,
            CDIV_CF_REASON_NO_REPLY,
            CDIV_CF_REASON_NOT_REACHABLE,
            CDIV_CF_REASON_ALL,
            CDIV_CF_REASON_ALL_CONDITIONAL,
            CDIV_CF_REASON_NOT_LOGGED_IN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallForwardReasons{}

    /**
     * Call forwarding is not active for any service class.
     */
    public static final int STATUS_NOT_ACTIVE = 0;

    /**
     * Call forwarding is active for one or more service classes.
     */
    public static final int STATUS_ACTIVE = 1;

    /**@hide*/
    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_NOT_ACTIVE,
            STATUS_ACTIVE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallForwardStatus{}

    /**
     * The address defined in {@link #getNumber()} is in an unknown format.
     *
     * See TS 27.007, section 7.11 for more information.
     */
    public static final int TYPE_OF_ADDRESS_UNKNOWN = 0x81;
    /**
     * The address defined in {@link #getNumber()} is in E.164 international format, which includes
     * international access code "+".
     *
     * See TS 27.007, section 7.11 for more information.
     */
    public static final int TYPE_OF_ADDRESS_INTERNATIONAL = 0x91;

    /**@hide*/
    @IntDef(prefix = {"TYPE_OF_ADDRESS_"}, value = {
            TYPE_OF_ADDRESS_INTERNATIONAL,
            TYPE_OF_ADDRESS_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TypeOfAddress{}

    /**@hide*/
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @CallForwardReasons int mCondition;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @CallForwardStatus int mStatus;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @TypeOfAddress int mToA;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @ImsSsData.ServiceClassFlags int mServiceClass;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String mNumber;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int mTimeSeconds;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ImsCallForwardInfo() {
    }

    /**
     * IMS Call Forward Information.
     */
    public ImsCallForwardInfo(@CallForwardReasons int reason, @CallForwardStatus int status,
            @TypeOfAddress int toA, @ImsSsData.ServiceClassFlags int serviceClass,
            @NonNull String number, int replyTimerSec) {
        mCondition = reason;
        mStatus = status;
        mToA = toA;
        mServiceClass = serviceClass;
        mNumber = number;
        mTimeSeconds = replyTimerSec;
    }

    /** @hide */
    public ImsCallForwardInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCondition);
        out.writeInt(mStatus);
        out.writeInt(mToA);
        out.writeString(mNumber);
        out.writeInt(mTimeSeconds);
        out.writeInt(mServiceClass);
    }

    @NonNull
    @Override
    public String toString() {
        return super.toString() + ", Condition: " + mCondition
            + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled")
            + ", ToA: " + mToA
            + ", Service Class: " + mServiceClass
            + ", Number=" + mNumber
            + ", Time (seconds): " + mTimeSeconds;
    }

    private void readFromParcel(Parcel in) {
        mCondition = in.readInt();
        mStatus = in.readInt();
        mToA = in.readInt();
        mNumber = in.readString();
        mTimeSeconds = in.readInt();
        mServiceClass = in.readInt();
    }

    public static final @android.annotation.NonNull Creator<ImsCallForwardInfo> CREATOR =
            new Creator<ImsCallForwardInfo>() {
        @Override
        public ImsCallForwardInfo createFromParcel(Parcel in) {
            return new ImsCallForwardInfo(in);
        }

        @Override
        public ImsCallForwardInfo[] newArray(int size) {
            return new ImsCallForwardInfo[size];
        }
    };

    /**
     * @return the condition of call forwarding for the service classes specified.
     */
    public @CallForwardReasons int getCondition() {
        return mCondition;
    }

    /**
     * @return The call forwarding status.
     */
    public @CallForwardStatus int getStatus() {
        return mStatus;
    }

    /**
     * @return the type of address (ToA) for the number.
     * @see #getNumber()
     */
    public @TypeOfAddress int getToA() {
        return mToA;
    }

    /**
     * @return a bitfield containing the service classes that are enabled for call forwarding.
     */
    public @ImsSsData.ServiceClassFlags int getServiceClass() {
        return mServiceClass;
    }

    /**
     * @return the call forwarding number associated with call forwarding, with a number type
     * defined by {@link #getToA()}.
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * @return the number in seconds to wait before the call is forwarded for call forwarding
     * condition {@link #CDIV_CF_REASON_NO_REPLY}
     */
    public int getTimeSeconds() {
        return mTimeSeconds;
    }
}
