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
import android.annotation.RequiresFeature;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * Class stores information related to the type of data throttling request. Must be populated as
 * field in {@link ThermalMitigationRequest} for sending of thermal mitigation request at {@link
 * TelephonyManager#sendThermalMitigationRequest(ThermalMitigationResult)}.
 * @hide
 */
@SystemApi
public final class DataThrottlingRequest implements Parcelable {
    /**
     * Clear all existing data throttling, enable data, and attempt to enable radio for thermal
     * mitigation all within the requested completion window. Note that attempting to enable radio
     * will not guarantee that radio will actually be enabled.
     *
     * @hide
     */
    @SystemApi
    public static final int DATA_THROTTLING_ACTION_NO_DATA_THROTTLING = 0;

    /**
     * Enact secondary carrier data throttling within specified completion window. This also
     * attempts to enables radio if currently disabled for thermal mitigation, enables data, and
     * removes any existing data throttling on primary carrier. Note that attempting to enable radio
     * will not guarantee that radio will actually be enabled.
     *
     * @hide
     */
    @SystemApi
    @RequiresFeature(
            enforcement = "android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported",
            value = TelephonyManager.CAPABILITY_THERMAL_MITIGATION_DATA_THROTTLING)
    public static final int DATA_THROTTLING_ACTION_THROTTLE_SECONDARY_CARRIER = 1;

    /**
     * Enact primary carrier data throttling within specified completion window. This also attempts
     * to enable radio if currently disabled for thermal mitigation and disables data on secondary
     * carrier if currently enabled. Note that attempting to enable radio will not guarantee that
     * radio will actually be enabled.
     *
     * @hide
     */
    @SystemApi
    @RequiresFeature(
            enforcement = "android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported",
            value = TelephonyManager.CAPABILITY_THERMAL_MITIGATION_DATA_THROTTLING)
    public static final int DATA_THROTTLING_ACTION_THROTTLE_PRIMARY_CARRIER = 2;

    /**
     * Immediately hold on to the current level of data throttling indicating that the current level
     * of data throttling has alleviated the thermal concerns which caused the original data
     * throttling request. A thermal module should remain actively monitoring the temperature levels
     * and request an appropriate thermal mitigation action. {@link
     * #THERMAL_MITIGATION_RESULT_INVALID_PARAMETERS} will be returned if completion window is not
     * 0.
     *
     * @hide
     */
    @SystemApi
    @RequiresFeature(
            enforcement = "android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported",
            value = TelephonyManager.CAPABILITY_THERMAL_MITIGATION_DATA_THROTTLING)
    public static final int DATA_THROTTLING_ACTION_HOLD = 3;

    /**
     * Type of data throttling action to carry out.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "DATA_THROTTLING_ACTION_" }, value = {
        DATA_THROTTLING_ACTION_NO_DATA_THROTTLING,
        DATA_THROTTLING_ACTION_THROTTLE_SECONDARY_CARRIER,
        DATA_THROTTLING_ACTION_THROTTLE_PRIMARY_CARRIER,
        DATA_THROTTLING_ACTION_HOLD})
    public @interface DataThrottlingAction {}

    /**
     * Represents the data throttling action that will be requested. See {@link
     * DATA_THROTTLING_ACTION_NO_DATA_THROTTLING}, {@link
     * #DATA_THROTTLING_ACTION_THROTTLE_SECONDARY_CARRIER}, {@link
     * #DATA_THROTTLING_ACTION_THROTTLE_PRIMARY_CARRIER}, and {@link
     * #DATA_THROTTLING_ACTION_HOLD} for more details.
     **/
    private @DataThrottlingAction int mDataThrottlingAction;
    /**
     * Represents the time over which modem should gradually execute the data thorttling request.
     */
    private long mCompletionDurationMillis;

    private DataThrottlingRequest(@NonNull int dataThrottlingAction,
            long completionDurationMillis) {
        mDataThrottlingAction = dataThrottlingAction;
        mCompletionDurationMillis = completionDurationMillis;
    }

    private DataThrottlingRequest(Parcel in) {
        mDataThrottlingAction = in.readInt();
        mCompletionDurationMillis = in.readLong();
    }

    /**
     * Implement the Parcelable interface
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDataThrottlingAction);
        dest.writeLong(mCompletionDurationMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "[DataThrottlingRequest "
            + ", DataThrottlingAction=" + mDataThrottlingAction
            + ", completionDurationMillis=" + mCompletionDurationMillis
            + "]";
    }

    /**
    * @return the dataThrottlingAction.
    */
    public @DataThrottlingAction int getDataThrottlingAction() {
        return mDataThrottlingAction;
    }

    /**
     * @return the completionDurationMillis which represents the time over which modem should
     * gradually execute the data thorttling request.
     */
    public long getCompletionDurationMillis() {
        return mCompletionDurationMillis;
    }

    public static final @NonNull Parcelable.Creator<DataThrottlingRequest> CREATOR =
            new Parcelable.Creator<DataThrottlingRequest>() {

        @Override
        public DataThrottlingRequest createFromParcel(Parcel in) {
            return new DataThrottlingRequest(in);
        }

        @Override
        public DataThrottlingRequest[] newArray(int size) {
            return new DataThrottlingRequest[size];
        }
    };

    /**
     * Provides a convenient way to set the fields of a {@link DataThrottlingRequest} when creating
     * a new instance.
     *
     * <p>The example below shows how you might create a new {@code DataThrottlingRequest}:
     *
     * <pre><code>
     *
     * DataThrottlingRequest dp = new DataThrottlingRequest.Builder()
     *     .setDataThrottlingAction(
     *          DataThrottlingRequest.DATA_THROTTLING_ACTION_THROTTLE_SECONDARY_CARRIER)
     *     .setCompletionDurationMillis(10000L)
     *     .build();
     * </code></pre>
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private @DataThrottlingAction int mDataThrottlingAction;
        private long mCompletionDurationMillis;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Set the data throttling action.
         *
         * @param dataThrottlingAction data throttling action.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setDataThrottlingAction(
                @DataThrottlingAction int dataThrottlingAction) {
            mDataThrottlingAction = dataThrottlingAction;
            return this;
        }

        /**
         * Set the completion duration.
         *
         * @param completionDurationMillis completion duration in millis which represents the time
         *      over which modem should gradually execute the data thorttling request. This can
         *      never be a negative number and must be 0 for {@link #DATA_THROTTLING_ACTION_HOLD}.
         *      Otherwise, an IllegalArgumentException will be thrown.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setCompletionDurationMillis(long completionDurationMillis) {
            mCompletionDurationMillis = completionDurationMillis;
            return this;
        }

        /**
         * Build the DataThrottlingRequest.
         *
         * @return the DataThrottlingRequest object.
         */
        public @NonNull DataThrottlingRequest build() {
            if (mCompletionDurationMillis < 0) {
                throw new IllegalArgumentException("completionDurationMillis cannot be a negative "
                        + "number");
            }

            if (mDataThrottlingAction == DataThrottlingRequest.DATA_THROTTLING_ACTION_HOLD
                    && mCompletionDurationMillis != 0) {
                throw new IllegalArgumentException("completionDurationMillis must be 0 for "
                    + "DataThrottlingRequest.DATA_THROTTLING_ACTION_HOLD");
            }

            return new DataThrottlingRequest(mDataThrottlingAction, mCompletionDurationMillis);
        }
    }

}
