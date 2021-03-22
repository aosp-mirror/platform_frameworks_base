/**
 * Copyright 2021 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single route selection descriptor as defined in
 * 3GPP TS 24.526.
 */
public final class RouteSelectionDescriptor implements Parcelable {
    /**
     * The min acceptable value for the precedence of a route selection descriptor.
     * @hide
     */
    public static final int MIN_ROUTE_PRECEDENCE = 0;

    /**
     * The max acceptable value for the precedence of a route selection descriptor.
     * @hide
     */
    public static final int MAX_ROUTE_PRECEDENCE = 255;

    /**
     * The route selection descriptor is for the session with IPV4 type.
     */
    public static final int SESSION_TYPE_IPV4 = 0;

    /**
     * The route selection descriptor is for the session with IPV6 type.
     */
    public static final int SESSION_TYPE_IPV6 = 1;

    /**
     * The route selection descriptor is for the session with both IP and IPV6 types.
     */
    public static final int SESSION_TYPE_IPV4V6 = 2;

    /** @hide */
    @IntDef(prefix = { "SESSION_TYPE_" }, value = {
            SESSION_TYPE_IPV4,
            SESSION_TYPE_IPV6,
            SESSION_TYPE_IPV4V6,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RouteSessionType {}

    /**
     * The route selection descriptor is using SSC mode 1. The session will provide continual
     * support when UE's location is updated.
     */
    public static final int ROUTE_SSC_MODE_1 = 1;

    /**
     * The route selection descriptor is using SSC mode 2. The new session for the same network
     * will be established after releasing the old session when UE's location is updated.
     */
    public static final int ROUTE_SSC_MODE_2 = 2;

    /**
     * The route selection descriptor is using SSC mode 3. The new session for the same network is
     * allowed to be established before releasing the old session when UE's location is updated.
     */
    public static final int ROUTE_SSC_MODE_3 = 3;

    /**
     * The min acceptable value for the SSC mode of a route selection descriptor.
     * @hide
     */
    public static final int MIN_ROUTE_SSC_MODE = ROUTE_SSC_MODE_1;

    /**
     * The max acceptable value for the SSC mode of a route selection descriptor.
     * @hide
     */
    public static final int MAX_ROUTE_SSC_MODE = ROUTE_SSC_MODE_3;

    /** @hide */
    @IntDef(prefix = { "ROUTE_SSC_MODE_" }, value = {
            ROUTE_SSC_MODE_1,
            ROUTE_SSC_MODE_2,
            ROUTE_SSC_MODE_3,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RouteSscMode {}

    @IntRange(from = MIN_ROUTE_PRECEDENCE, to = MAX_ROUTE_PRECEDENCE)
    private final int mPrecedence;
    @RouteSessionType
    private final int mSessionType;
    @RouteSscMode
    @IntRange(from = MIN_ROUTE_SSC_MODE, to = MAX_ROUTE_SSC_MODE)
    private final int mSscMode;
    private final List<NetworkSliceInfo> mSliceInfo;
    private final List<String> mDnn;

    /** @hide */
    RouteSelectionDescriptor(android.hardware.radio.V1_6.RouteSelectionDescriptor rsd) {
        this(rsd.precedence, rsd.sessionType.value(), rsd.sscMode.value(), rsd.sliceInfo,
                rsd.dnn);
    }

    /** @hide */
    public RouteSelectionDescriptor(int precedence, int sessionType, int sscMode,
            List<android.hardware.radio.V1_6.SliceInfo> sliceInfo, List<String> dnn) {
        mPrecedence = precedence;
        mSessionType = sessionType;
        mSscMode = sscMode;
        mSliceInfo = new ArrayList<NetworkSliceInfo>();
        for (android.hardware.radio.V1_6.SliceInfo si : sliceInfo) {
            mSliceInfo.add(sliceInfoBuilder(si));
        }
        mDnn = new ArrayList<String>();
        mDnn.addAll(dnn);
    }

    private NetworkSliceInfo sliceInfoBuilder(android.hardware.radio.V1_6.SliceInfo si) {
        NetworkSliceInfo.Builder builder = new NetworkSliceInfo.Builder()
                .setSliceServiceType(si.sst)
                .setMappedHplmnSliceServiceType(si.mappedHplmnSst);
        if (si.sliceDifferentiator != NetworkSliceInfo.SLICE_DIFFERENTIATOR_NO_SLICE) {
            builder
                .setSliceDifferentiator(si.sliceDifferentiator)
                .setMappedHplmnSliceDifferentiator(si.mappedHplmnSD);
        }
        return builder.build();
    }

    private RouteSelectionDescriptor(Parcel p) {
        mPrecedence = p.readInt();
        mSessionType = p.readInt();
        mSscMode = p.readInt();
        mSliceInfo = p.createTypedArrayList(NetworkSliceInfo.CREATOR);
        mDnn = new ArrayList<String>();
        p.readStringList(mDnn);
    }

    /**
     * Precedence value in the range of 0 to 255. Higher value has lower precedence.
     * @return the precedence value for this route selection descriptor.
     */
    @IntRange(from = MIN_ROUTE_PRECEDENCE, to = MAX_ROUTE_PRECEDENCE)
    public int getPrecedence() {
        return mPrecedence;
    }

    /**
     * This is used for checking which session type defined in 3GPP TS 23.501 is allowed for the
     * route in a route selection descriptor.
     * @return the session type for this route selection descriptor.
     */
    @RouteSessionType
    public int getSessionType() {
        return mSessionType;
    }

    /**
     * SSC mode stands for Session and Service Continuity mode (which specifies the IP continuity
     * mode) as defined in 3GPP TS 23.501.
     * @return the SSC mode for this route selection descriptor.
     */
    @RouteSscMode
    public int getSscMode() {
        return mSscMode;
    }

    /**
     * This is the list of all the slices available in the route selection descriptor as indicated
     * by the network. These are the slices that can be used by the device if this route selection
     * descriptor is used based the traffic (see 3GPP TS 23.501 for details).
     * @return the list of all the slices available in the route selection descriptor.
     */
    public @NonNull List<NetworkSliceInfo> getSliceInfo() {
        return mSliceInfo;
    }

    /**
     * DNN stands for Data Network Name and represents an APN as defined in 3GPP TS 23.003. There
     * can be 0 or more DNNs specified in a route selection descriptor.
     * @return the list of DNN for this route selection descriptor.
     */
    public @NonNull List<String> getDataNetworkName() {
        return mDnn;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPrecedence);
        dest.writeInt(mSessionType);
        dest.writeInt(mSscMode);
        dest.writeTypedList(mSliceInfo, flags);
        dest.writeStringList(mDnn);
    }

    public static final @NonNull Parcelable.Creator<RouteSelectionDescriptor> CREATOR =
            new Parcelable.Creator<RouteSelectionDescriptor>() {
                @Override
                public RouteSelectionDescriptor createFromParcel(Parcel source) {
                    return new RouteSelectionDescriptor(source);
                }

                @Override
                public RouteSelectionDescriptor[] newArray(int size) {
                    return new RouteSelectionDescriptor[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteSelectionDescriptor that = (RouteSelectionDescriptor) o;
        return mPrecedence == that.mPrecedence
                && mSessionType == that.mSessionType
                && mSscMode == that.mSscMode
                && mSliceInfo.size() == that.mSliceInfo.size()
                && mSliceInfo.containsAll(that.mSliceInfo)
                && mDnn.size() == that.mDnn.size()
                && mDnn.containsAll(that.mDnn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPrecedence, mSessionType, mSscMode, mSliceInfo, mDnn);
    }

    @Override
    public String toString() {
        return "{.precedence = " + mPrecedence + ", .sessionType = " + mSessionType
                + ", .sscMode = " + mSscMode + ", .sliceInfo = " + mSliceInfo
                + ", .dnn = " + mDnn + "}";
    }
}
