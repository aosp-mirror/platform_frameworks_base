/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.hardware.location;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @deprecated Use {@link android.hardware.location.ContextHubManager#queryNanoApps(ContextHubInfo)}
 *             to find loaded nanoapps, which doesn't require using this class as a parameter.
 *
 * @hide
 */
@SystemApi
@Deprecated
public class NanoAppFilter implements Parcelable {

    private static final String TAG = "NanoAppFilter";

    // The appId, can be set to APP_ID_ANY
    private long mAppId;

    // Version to filter apps
    private int mAppVersion;

    // filtering spec for version
    private int mVersionRestrictionMask;

    // If APP_ID is any, then a match is performef with the vendor mask
    private long mAppIdVendorMask;

    // Id of the context hub this instance is expected on
    // TODO: Provide an API which will let us change this HubId.
    private int mContextHubId = HUB_ANY;

    /**
     * Flag indicating any version. With this flag set, all versions shall match provided version.
     */
    public static final int FLAGS_VERSION_ANY = -1;
    /**
     * If this flag is set, only versions strictly greater than the version specified shall match.
     */
    public static final int FLAGS_VERSION_GREAT_THAN  = 2;
    /**
     * If this flag is set, only versions strictly less than the version specified shall match.
     */
    public static final int FLAGS_VERSION_LESS_THAN   = 4;
    /**
     * If this flag is set, only versions strictly equal to the
     * version specified shall match.
     */
    public static final int FLAGS_VERSION_STRICTLY_EQUAL = 8;

    /**
     * If this flag is set, only versions strictly equal to the version specified shall match.
     */
    public static final int APP_ANY = -1;

    /**
     * If this flag is set, all vendors shall match.
     */
    public static final int VENDOR_ANY = -1;

    /**
     * If this flag is set, any hub shall match.
     */
    public static final int HUB_ANY = -1;

    private NanoAppFilter(Parcel in) {
        mAppId = in.readLong();
        mAppVersion = in.readInt();
        mVersionRestrictionMask = in.readInt();
        mAppIdVendorMask = in.readLong();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mAppId);
        out.writeInt(mAppVersion);
        out.writeInt(mVersionRestrictionMask);
        out.writeLong(mAppIdVendorMask);
    }

    /**
     * Create a filter
     *
     * @param appId       application id
     * @param appVersion  application version
     * @param versionMask version
     * @param vendorMask  vendor
     */
    public NanoAppFilter(long appId, int appVersion, int versionMask, long vendorMask) {
        mAppId = appId;
        mAppVersion = appVersion;
        mVersionRestrictionMask = versionMask;
        mAppIdVendorMask = vendorMask;
    }

    private boolean versionsMatch(int versionRestrictionMask, int expected, int actual){
        // some refactoring of version restriction mask is needed, until then, return all
        return true;
    }
    /**
     * Test match method.
     *
     * @param info nano app instance info
     *
     * @return true if this is a match, false otherwise
     */
    public boolean testMatch(NanoAppInstanceInfo info) {
        return (mContextHubId == HUB_ANY || info.getContexthubId() == mContextHubId) &&
                (mAppId == APP_ANY || info.getAppId() == mAppId) &&
                (versionsMatch(mVersionRestrictionMask, mAppVersion, info.getAppVersion()));
    }

    @Override
    public String toString() {
        return "nanoAppId: 0x" + Long.toHexString(mAppId)
                + ", nanoAppVersion: 0x" + Integer.toHexString(mAppVersion)
                + ", versionMask: " + mVersionRestrictionMask
                + ", vendorMask: " + mAppIdVendorMask;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<NanoAppFilter> CREATOR
            = new Parcelable.Creator<NanoAppFilter>() {
        public NanoAppFilter createFromParcel(Parcel in) {
            return new NanoAppFilter(in);
        }

        public NanoAppFilter[] newArray(int size) {
            return new NanoAppFilter[size];
        }
    };
}
