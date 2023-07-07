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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The state of an ongoing SIP dialog.
 * @hide
 */
@SystemApi
public final class SipDialogState implements Parcelable {

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "STATE_", value = {STATE_EARLY, STATE_CONFIRMED, STATE_CLOSED})
    public @interface SipDialogStateCode {}
    /**
     * The device has sent out a dialog starting event and is awaiting a confirmation.
     */
    public static final int STATE_EARLY = 0;

    /**
     * The device has received a 2XX response to the early dialog.
     */
    public static final int STATE_CONFIRMED = 1;

    /**
     * The device has received either a 3XX+ response to a pending dialog request or a BYE
     * request has been sent on this dialog.
     */
    public static final int STATE_CLOSED = 2;

    private final int mState;

    /**
     * Builder for {@link SipDialogState}.
     * @hide
     */
    public static final class Builder {
        private int mState = STATE_EARLY;

        /**
         * constructor
         * @param state The state of SipDialog
         */
        public Builder(@SipDialogStateCode int state) {
            mState = state;
        }

        /**
         * Build the {@link SipDialogState}.
         * @return The {@link SipDialogState} instance.
         */
        public @NonNull SipDialogState build() {
            return new SipDialogState(this);
        }
    }

    /**
     * set Dialog state
     */
    private SipDialogState(@NonNull Builder builder) {
        this.mState = builder.mState;
    }

    private SipDialogState(Parcel in) {
        mState = in.readInt();
    }

    /**
     * @return The state of the SIP dialog
     */
    public @SipDialogStateCode int getState() {
        return mState;
    }

    public static final @NonNull Creator<SipDialogState> CREATOR = new Creator<SipDialogState>() {
        @Override
        public SipDialogState createFromParcel(@NonNull Parcel in) {
            return new SipDialogState(in);
        }

        @Override
        public SipDialogState[] newArray(int size) {
            return new SipDialogState[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mState);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SipDialogState sipDialog = (SipDialogState) o;

        return mState == sipDialog.mState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState);
    }
}
