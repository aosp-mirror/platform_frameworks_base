/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.presence;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide  */
public class PresSubscriptionState implements Parcelable {

    /**
     *  Subscription states.
     *  @hide
     */

    /** Active state. */
    public static final int UCE_PRES_SUBSCRIPTION_STATE_ACTIVE = 0;
    /** Pending state. */
    public static final int UCE_PRES_SUBSCRIPTION_STATE_PENDING = 1;
    /** Terminated state. */
    public static final int UCE_PRES_SUBSCRIPTION_STATE_TERMINATED = 2;
    /** Unknown state. */
    public static final int UCE_PRES_SUBSCRIPTION_STATE_UNKNOWN = 3;


    private int mPresSubscriptionState = UCE_PRES_SUBSCRIPTION_STATE_UNKNOWN;


    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPresSubscriptionState);
    }

    /** @hide */
    public static final Parcelable.Creator<PresSubscriptionState> CREATOR =
                                    new Parcelable.Creator<PresSubscriptionState>() {

        public PresSubscriptionState createFromParcel(Parcel source) {
            return new PresSubscriptionState(source);
        }

        public PresSubscriptionState[] newArray(int size) {
            return new PresSubscriptionState[size];
        }
    };

    /** @hide */
    private PresSubscriptionState(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mPresSubscriptionState = source.readInt();
    }

    /**
     * Constructor for the PresSubscriptionState class.
     * @hide
     */
    @UnsupportedAppUsage
    public PresSubscriptionState() {    };

    /**
     * Gets the Presence subscription state.
     * @hide
     */
    public int getPresSubscriptionStateValue() {
        return mPresSubscriptionState;
    }


    /**
     * Sets the Presence subscription state.
     * @hide
     */
    @UnsupportedAppUsage
    public void setPresSubscriptionState(int nPresSubscriptionState) {
        this.mPresSubscriptionState = nPresSubscriptionState;
    }
}