/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A class describing the nanoapp state information resulting from a query to a Context Hub
 * through {@link ContextHubManager.queryNanoApps(ContextHubInfo)}. It contains metadata about
 * the nanoapp running on a Context Hub.
 *
 * See "struct chreNanoappInfo" in the CHRE API (system/chre/chre_api) for additional details.
 *
 * @hide
 */
@SystemApi
public final class NanoAppState implements Parcelable {
    private long mNanoAppId;
    private int mNanoAppVersion;
    private boolean mIsEnabled;
    private List<String> mNanoAppPermissions = new ArrayList<String>();
    private List<NanoAppRpcService> mNanoAppRpcServiceList =
                new ArrayList<NanoAppRpcService>();

    /**
     * @param nanoAppId The unique ID of this nanoapp, see {#getNanoAppId()}.
     * @param appVersion The software version of this nanoapp, see {#getNanoAppVersion()}.
     * @param enabled True if the nanoapp is enabled and running on the Context Hub.
     */
    public NanoAppState(long nanoAppId, int appVersion, boolean enabled) {
        mNanoAppId = nanoAppId;
        mNanoAppVersion = appVersion;
        mIsEnabled = enabled;
    }

    /**
     * @param nanoAppId The unique ID of this nanoapp, see {#getNanoAppId()}.
     * @param appVersion The software version of this nanoapp, see {#getNanoAppVersion()}.
     * @param enabled True if the nanoapp is enabled and running on the Context Hub.
     * @param nanoAppPermissions The list of permissions required to communicate with this
     *   nanoapp.
     */
    public NanoAppState(long nanoAppId, int appVersion, boolean enabled,
                        @NonNull List<String> nanoAppPermissions) {
        mNanoAppId = nanoAppId;
        mNanoAppVersion = appVersion;
        mIsEnabled = enabled;
        mNanoAppPermissions = Collections.unmodifiableList(nanoAppPermissions);
    }

    /**
     * @param nanoAppId The unique ID of this nanoapp, see {#getNanoAppId()}.
     * @param appVersion The software version of this nanoapp, see {#getNanoAppVersion()}.
     * @param enabled True if the nanoapp is enabled and running on the Context Hub.
     * @param nanoAppPermissions The list of permissions required to communicate with this
     *   nanoapp.
     * @param nanoAppRpcServiceList The list of RPC services published by this nanoapp, see
     *   {@link NanoAppRpcService} for additional details.
     */
    public NanoAppState(long nanoAppId, int appVersion, boolean enabled,
                        @NonNull List<String> nanoAppPermissions,
                        @NonNull List<NanoAppRpcService> nanoAppRpcServiceList) {
        mNanoAppId = nanoAppId;
        mNanoAppVersion = appVersion;
        mIsEnabled = enabled;
        mNanoAppPermissions = Collections.unmodifiableList(nanoAppPermissions);
        mNanoAppRpcServiceList = Collections.unmodifiableList(nanoAppRpcServiceList);
    }

    /**
     * @return the unique ID of this nanoapp, which must never change once released on Android.
     */
    public long getNanoAppId() {
        return mNanoAppId;
    }

    /**
     * The software version of this service, which follows the sematic
     * versioning scheme (see semver.org). It follows the format
     * major.minor.patch, where major and minor versions take up one byte
     * each, and the patch version takes up the final 2 (lower) bytes.
     * I.e. the version is encoded as 0xMMmmpppp, where MM, mm, pppp are
     * the major, minor, patch versions, respectively.
     *
     * @return the app version
     */
    public long getNanoAppVersion() {
        return mNanoAppVersion;
    }

    /**
     * @return {@code true} if the app is enabled at the Context Hub, {@code false} otherwise
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * @return A read-only list of Android permissions that are all required to communicate with
     *   this nanoapp.
     */
    public @NonNull List<String> getNanoAppPermissions() {
        return mNanoAppPermissions;
    }

    /**
     * @return A read-only list of RPC services supported by this nanoapp.
     */
    public @NonNull List<NanoAppRpcService> getRpcServices() {
        return mNanoAppRpcServiceList;
    }

    private NanoAppState(Parcel in) {
        mNanoAppId = in.readLong();
        mNanoAppVersion = in.readInt();
        mIsEnabled = (in.readInt() == 1);
        mNanoAppPermissions = new ArrayList<String>();
        in.readStringList(mNanoAppPermissions);
        mNanoAppRpcServiceList = Collections.unmodifiableList(
                    Arrays.asList(in.readParcelableArray(
                    NanoAppRpcService.class.getClassLoader(), NanoAppRpcService.class)));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mNanoAppId);
        out.writeInt(mNanoAppVersion);
        out.writeInt(mIsEnabled ? 1 : 0);
        out.writeStringList(mNanoAppPermissions);
        out.writeParcelableArray(mNanoAppRpcServiceList.toArray(new NanoAppRpcService[0]), 0);
    }

    public static final @android.annotation.NonNull Creator<NanoAppState> CREATOR =
            new Creator<NanoAppState>() {
                @Override
                public NanoAppState createFromParcel(Parcel in) {
                    return new NanoAppState(in);
                }

                @Override
                public NanoAppState[] newArray(int size) {
                    return new NanoAppState[size];
                }
            };
}
