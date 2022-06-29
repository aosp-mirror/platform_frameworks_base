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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * Class stores information related to the type of data throttling request to be sent to {@link
 * TelephonyManager#sendThermalMitigationRequest(ThermalMitigationResult)}.
 * @hide
 */
@SystemApi
public final class ThermalMitigationRequest implements Parcelable {
    /**
     * Sent as a thermal mititgation action to {@link
     * TelephonyManager#sendThermalMitigationRequest(ThermalMitigationResult)} to start data
     * throttling. {@link TelephonyManager#InvalidThermalMitigationRequestException} will be thrown
     * if dataThrottlingRequest is {@code null} or if completion duration is < 0.
     *
     * @hide
     */
    @SystemApi
    public static final int THERMAL_MITIGATION_ACTION_DATA_THROTTLING = 0;

    /**
     * Sent as a thermal mititgation action to {@link
     * TelephonyManager#sendThermalMitigationRequest(ThermalMitigationResult)} to allow only voice
     * calls and internet data will not be available. This attempts to enable radio if currently
     * disabled for thermal mitigation with no guarantee of it actually turning on.
     * dataThrottlingRequest must be {@code null} or {@link
     * TelephonyManager#InvalidThermalMitigationRequestException} will be thrown.
     *
     * @hide
     */
    @SystemApi
    public static final int THERMAL_MITIGATION_ACTION_VOICE_ONLY = 1;

    /**
     * Sent as a thermal mititgation action to {@link'
     * TelephonyManager#sendThermalMitigationRequest(ThermalMitigationResult)} to turn radio off. If
     * radio is not able to be powered off because of an ongoing voice call, pending emergency call,
     * or any other state that wouldn't allow radio off, {@link
     * TelephonyManager#THERMAL_MITIGATION_RESULT_INVALID_STATE}.
     * dataThrottlingRequest must be {@code null} or
     * {@link TelephonyManager#InvalidThermalMitigationRequestException} will be returned.
     *
     * @hide
     */
    @SystemApi
    public static final int THERMAL_MITIGATION_ACTION_RADIO_OFF = 2;

    /**
     * Type of thermal mitigation action.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "THERMAL_MITIGATION_ACTION_" }, value = {
        THERMAL_MITIGATION_ACTION_DATA_THROTTLING,
        THERMAL_MITIGATION_ACTION_VOICE_ONLY,
        THERMAL_MITIGATION_ACTION_RADIO_OFF})
    public @interface ThermalMitigationAction {}

    private @ThermalMitigationAction int mThermalMitigationAction;
    private DataThrottlingRequest mDataThrottlingRequest;

    /**
     * @param thermalMitigationAction thermal mitigation action.
     * @param dataThrottlingRequest is the parameters for more fine-controlled data throttling. This
     * is only applicable if thermalMitigationAction is
     * {@link #THERMAL_MITIGATION_ACTION_DATA_THROTTLING}. Otherwise, it must be set to
     * {@code null}. See {@link DataThrottlingRequest} for more details.
     */
    private ThermalMitigationRequest(@ThermalMitigationAction int thermalMitigationAction,
            @Nullable DataThrottlingRequest dataThrottlingRequest) {
        mThermalMitigationAction = thermalMitigationAction;
        mDataThrottlingRequest = dataThrottlingRequest;
    }

    private ThermalMitigationRequest(Parcel in) {
        mThermalMitigationAction = in.readInt();
        mDataThrottlingRequest = in.readParcelable(DataThrottlingRequest.class.getClassLoader(), android.telephony.DataThrottlingRequest.class);
    }

     /**
     * Implement the Parcelable interface
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mThermalMitigationAction);
        dest.writeParcelable(mDataThrottlingRequest, 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "[ThermalMitigationRequest "
            + ", thermalMitigationAction=" + mThermalMitigationAction
            + ", dataThrottlingRequest=" + mDataThrottlingRequest
            + "]";
    }

    /**
     * @return the thermal mitigation action.
     */
    public @ThermalMitigationAction int getThermalMitigationAction() {
        return mThermalMitigationAction;
    }

    /**
     * @return the data throttling request.
     */
    @Nullable
    public DataThrottlingRequest getDataThrottlingRequest() {
        return mDataThrottlingRequest;
    }

    public static final @NonNull Parcelable.Creator<ThermalMitigationRequest> CREATOR =
            new Parcelable.Creator<ThermalMitigationRequest>() {

        @Override
        public ThermalMitigationRequest createFromParcel(Parcel in) {
            return new ThermalMitigationRequest(in);
        }

        @Override
        public ThermalMitigationRequest[] newArray(int size) {
            return new ThermalMitigationRequest[size];
        }
    };

    /**
     * Provides a convenient way to set the fields of a {@link ThermalMitigationRequest} when
     * creating a new instance.
     *
     * <p>The example below shows how you might create a new {@code ThermalMitigationRequest}:
     *
     * <pre><code>
     *
     * ThermalMitigationRequest dp = new ThermalMitigationRequest.Builder()
     *     .setThermalMitigationAction(
     *          ThermalMitigationRequest.THERMAL_MITIGATION_ACTION_DATA_THROTTLING)
     *     .setDataThrottlingRequest(new DataThrottlingRequest.Builder()
     *          .setDataThrottlingAction(
     *              DataThrottlingRequest.DATA_THROTTLING_ACTION_THROTTLE_SECONDARY_CARRIER)
     *          .setCompletionDurationMillis(10000L)
     *          .build())
     *     .build();
     * </code></pre>
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private @ThermalMitigationAction int mThermalMitigationAction = -1;
        private DataThrottlingRequest mDataThrottlingRequest;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Set the thermal mitigation action.
         *
         * @param thermalMitigationAction thermal mitigation action. See {@link
         *      #THERMAL_MITIGATION_ACTION_DATA_THROTTLING}, {@link
         *      #THERMAL_MITIGATION_ACTION_VOICE_ONLY}, and {@link
         *      #THERMAL_MITIGATION_ACTION_RADIO_OFF} for more details.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setThermalMitigationAction(
                @ThermalMitigationAction int thermalMitigationAction) {
            mThermalMitigationAction = thermalMitigationAction;
            return this;
        }

        /**
         * Set the data throttling request.
         *
         * @param dataThrottlingRequest is the parameters for more fine-controlled data throttling.
         *      This is only applicable if thermalMitigationAction is {@link
         *      #THERMAL_MITIGATION_ACTION_DATA_THROTTLING}. Otherwise, it should not be set and
         *      will throw an IllegalArgumentException if it is. See {@link DataThrottlingRequest}
         *      for more details.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setDataThrottlingRequest(
                @NonNull DataThrottlingRequest dataThrottlingRequest) {
            mDataThrottlingRequest = dataThrottlingRequest;
            return this;
        }

        /**
         * Build the ThermalMitigationRequest.
         *
         * @return the ThermalMitigationRequest object.
         */
        public @NonNull ThermalMitigationRequest build() {
            if (mThermalMitigationAction < 0) {
                throw new IllegalArgumentException("thermalMitigationAction was "
                        + " not set");
            }

            if (mThermalMitigationAction == THERMAL_MITIGATION_ACTION_DATA_THROTTLING) {
                if (mDataThrottlingRequest == null) {
                    throw new IllegalArgumentException("dataThrottlingRequest  cannot be null for "
                            + "THERMAL_MITIGATION_ACTION_DATA_THROTTLING");
                }


            } else if (mDataThrottlingRequest != null) {
                throw new IllegalArgumentException("dataThrottlingRequest must be null for "
                        + "THERMAL_MITIGATION_ACTION_VOICE_ONLY and "
                        + "THERMAL_MITIGATION_ACTION_RADIO_OFF");
            }

            return new ThermalMitigationRequest(mThermalMitigationAction, mDataThrottlingRequest);
        }
    }
}
