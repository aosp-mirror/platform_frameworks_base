/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telecom;


import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the endpoint on which a call can be carried by the user.
 *
 * For example, the user may be able to carry out a call on another device on their local network
 * using a call streaming solution, or may be able to carry out a call on another device registered
 * with the same mobile line of service.
 */
public final class CallEndpoint implements Parcelable {
    /**
     * @hide
     */
    @IntDef(prefix = {"ENDPOINT_TYPE_"},
            value = {ENDPOINT_TYPE_TETHERED, ENDPOINT_TYPE_UNTETHERED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EndpointType {}

    /** Indicates the endpoint contains a complete calling stack and is capable of carrying out a
     *  call on its own. Untethered endpoints are typically other devices which share the same
     *  mobile line of service as the current device.
     */
    public static final int ENDPOINT_TYPE_UNTETHERED = 1;

    /** Indicates the endpoint itself doesn't have the required calling infrastructure in order to
     *  complete a call on its own. Tethered endpoints depend on a call streaming solution to
     *  transport the media and control for a call to another device, while depending on the current
     *  device to connect the call to the mobile network.
     */
    public static final int ENDPOINT_TYPE_TETHERED = 2;

    private final ParcelUuid mUuid;
    private CharSequence mDescription;
    private final int mType;
    private final ComponentName mComponentName;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mUuid.writeToParcel(dest, flags);
        dest.writeCharSequence(mDescription);
        dest.writeInt(mType);
        mComponentName.writeToParcel(dest, flags);
    }

    public static final @android.annotation.NonNull Creator<CallEndpoint> CREATOR =
            new Creator<CallEndpoint>() {
                @Override
                public CallEndpoint createFromParcel(Parcel in) {
                    return new CallEndpoint(in);
                }

                @Override
                public CallEndpoint[] newArray(int size) {
                    return new CallEndpoint[size];
                }
            };

    public CallEndpoint(@NonNull ParcelUuid uuid, @NonNull CharSequence description, int type,
            @NonNull ComponentName componentName) {
        mUuid = uuid;
        mDescription = description;
        mType = type;
        mComponentName = componentName;
    }

    private CallEndpoint(@NonNull Parcel in) {
        this(ParcelUuid.CREATOR.createFromParcel(in), in.readCharSequence(), in.readInt(),
                ComponentName.CREATOR.createFromParcel(in));
    }

    /**
     * A unique identifier for this call endpoint. An endpoint provider should take care to use an
     * identifier which is stable for the current association between an endpoint and the current
     * device, but which is not globally identifying.
     * @return the unique identifier.
     */
    public @NonNull ParcelUuid getIdentifier() {
        return mUuid;
    }

    /**
     * A human-readable description of this {@link CallEndpoint}. An {@link InCallService} uses
     * when informing the user of the endpoint.
     * @return the description.
     */
    public @NonNull CharSequence getDescription() {
        return mDescription;
    }

    public @EndpointType int getType() {
        return mType;
    }

    /**
     * @hide
     */
    public @NonNull ComponentName getComponentName() {
        return mComponentName;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CallEndpoint) {
            CallEndpoint d = (CallEndpoint) o;
            return Objects.equals(mUuid, d.mUuid)
                    && Objects.equals(mDescription, d.mDescription)
                    && Objects.equals(mType, d.mType)
                    && Objects.equals(mComponentName, d.mComponentName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUuid, mDescription, mType, mComponentName);
    }
}
