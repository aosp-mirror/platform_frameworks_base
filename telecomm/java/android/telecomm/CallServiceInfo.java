/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecomm;

import android.content.ComponentName;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.UUID;

/**
 * An immutable object containing information about a given {@link CallService}. Instances are
 * created using the enclosed {@link Builder}.
 */
public final class CallServiceInfo implements Parcelable {
    private static final String TAG = CallServiceInfo.class.getSimpleName();

    /**
     * A placeholder value indicating an invalid network type.
     * @hide
     */
    private static final int FLAG_INVALID = 0;

    /**
     * Indicates that the device must be connected to a Wi-Fi network in order for the backing
     * {@link CallService} to be used.
     */
    public static final int FLAG_WIFI = 0x01;

    /**
     * Indicates that the device must be connected to a cellular PSTN network in order for the
     * backing {@link CallService} to be used.
     */
    public static final int FLAG_PSTN = 0x02;

    /**
     * Indicates that the device must be connected to a cellular data network in order for the
     * backing {@link CallService} to be used.
     */
    public static final int FLAG_MOBILE = 0x04;

    /**
     * Represents all of the defined FLAG_ constants so validity can be easily checked.
     * @hide
     */
    public static final int FLAG_ALL = FLAG_WIFI | FLAG_PSTN | FLAG_MOBILE;

    /**
     * A unique ID used to identify a given instance.
     */
    private final String mCallServiceId;

    /**
     * The {@link ComponentName} of the {@link CallService} implementation which this is describing.
     */
    private final ComponentName mComponentName;

    /**
     * The type of connection that the {@link CallService} requires; will be one of the FLAG_*
     * constants defined in this class.
     */
    private final int mNetworkType;

    private CallServiceInfo(String callServiceId, ComponentName componentName, int networkType) {
        mCallServiceId = callServiceId;
        mComponentName = componentName;
        mNetworkType = networkType;
    }

    /**
     * @return The ID used to identify this {@link CallService}.
     */
    public String getCallServiceId() {
        return mCallServiceId;
    }

    /**
     * @return The {@link ComponentName} of the {@link CallService}.
     */
    public ComponentName getServiceComponent() {
        return mComponentName;
    }

    /**
     * @return The network type required by the {@link CallService} to place a call.
     */
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * @param context {@link Context} to use for the construction of the {@link Builder}.
     * @return A new {@link Builder} instance.
     */
    public static Builder newBuilder(Context context) {
        return new Builder(context);
    }

    /**
     * Creates {@link CallServiceInfo} instances. Builders should be created with the
     * {@link CallServiceInfo#newBuilder(Context)} method.
     */
    public static class Builder {
        /** The {@link Context} to use to verify {@link ComponentName} ownership. */
        private Context mContext;

        /** The {@link ComponentName} pointing to the backing {@link CallService}. */
        private ComponentName mComponentName;

        /** The required network type that the {@link CallService} needs. */
        private int mNetworkType = FLAG_INVALID;

        private Builder(Context context) {
            mContext = context;
        }

        /**
         * Set which {@link CallService} this {@link CallServiceInfo} is describing.
         *
         * @param callServiceClass The {@link CallService} class
         * @return This {@link Builder} for method chaining.
         */
        public Builder setCallService(Class<? extends CallService> callServiceClass) {
            mComponentName = new ComponentName(mContext, callServiceClass);
            return this;
        }

        /**
         * Which network type the backing {@link CallService} requires. This must be one of the
         * {@link CallServiceInfo}.TYPE_* fields.
         *
         * @param networkType Which network type the backing {@link CallService} requires.
         * @return This {@link Builder} for method chaining.
         */
        public Builder setNetworkType(int networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * @return A constructed {@link CallServiceInfo} object.
         */
        public CallServiceInfo build() {
            // STOPSHIP: Verify validity of ComponentName (permissions, intents, etc)

            // Make sure that they passed in a valid network flag combination
            if (mNetworkType == FLAG_INVALID || ((mNetworkType & FLAG_ALL) == 0)) {

                Log.wtf(TAG, "Invalid network type for " + mComponentName);
                // Revert them back to TYPE_INVALID so it won't be considered.
                mNetworkType = FLAG_INVALID;
            }

            // TODO: Should we use a sha1 of the ComponentName? Would prevent duplicates.
            return new CallServiceInfo(UUID.randomUUID().toString(), mComponentName, mNetworkType);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCallServiceId);
        dest.writeParcelable(mComponentName, 0);
        dest.writeInt(mNetworkType);
    }

    static final Creator<CallServiceInfo> CREATOR = new Creator<CallServiceInfo>() {
        @Override
        public CallServiceInfo createFromParcel(Parcel source) {
            String id = source.readString();
            ComponentName componentName = source.readParcelable(
                    CallServiceInfo.class.getClassLoader());
            int networkType = source.readInt();

            return new CallServiceInfo(id, componentName, networkType);
        }

        @Override
        public CallServiceInfo[] newArray(int size) {
            return new CallServiceInfo[size];
        }
    };
}
