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

package android.telecom;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * When calls are made, they may contain gateway information for services which route phone calls
 * through their own service/numbers. The data consists of a number to call and the package name of
 * the service. This data is used in two ways:
 * <ol>
 * <li> Call the appropriate routing number
 * <li> Display information about how the call is being routed to the user
 * </ol>
 * @hide
 */
@SystemApi
public class GatewayInfo implements Parcelable {

    private final String mGatewayProviderPackageName;
    private final Uri mGatewayAddress;
    private final Uri mOriginalAddress;

    /** @hide */
    @SystemApi
    public GatewayInfo(String packageName, Uri gatewayUri, Uri originalAddress) {
        mGatewayProviderPackageName = packageName;
        mGatewayAddress = gatewayUri;
        mOriginalAddress = originalAddress;
    }

    /**
     * Package name of the gateway provider service. used to place the call with.
     */
    public String getGatewayProviderPackageName() {
        return mGatewayProviderPackageName;
    }

    /**
     * Gateway provider address to use when actually placing the call.
     */
    public Uri getGatewayAddress() {
        return mGatewayAddress;
    }

    /**
     * The actual call address that the user is trying to connect to via the gateway.
     */
    public Uri getOriginalAddress() {
        return mOriginalAddress;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(mGatewayProviderPackageName) || mGatewayAddress == null;
    }

    /** Implement the Parcelable interface */
    public static final Parcelable.Creator<GatewayInfo> CREATOR =
            new Parcelable.Creator<GatewayInfo> () {

        @Override
        public GatewayInfo createFromParcel(Parcel source) {
            String gatewayPackageName = source.readString();
            Uri gatewayUri = Uri.CREATOR.createFromParcel(source);
            Uri originalAddress = Uri.CREATOR.createFromParcel(source);
            return new GatewayInfo(gatewayPackageName, gatewayUri, originalAddress);
        }

        @Override
        public GatewayInfo[] newArray(int size) {
            return new GatewayInfo[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(mGatewayProviderPackageName);
        mGatewayAddress.writeToParcel(destination, 0);
        mOriginalAddress.writeToParcel(destination, 0);
    }
}
