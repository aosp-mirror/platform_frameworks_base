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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single URSP rule as defined in 3GPP TS 24.526. URSP stands for UE Route Selection
 * Policy. In 5G, network can provide URSP information to devices which provides information on
 * what connection parameters should be used for what traffic.
 */
public final class UrspRule implements Parcelable {
    /**
     * The min acceptable value for the precedence of a URSP rule.
     * @hide
     */
    public static final int MIN_URSP_PRECEDENCE = 0;

    /**
     * The max acceptable value for the precedence of a URSP rule.
     * @hide
     */
    public static final int MAX_URSP_PRECEDENCE = 255;

    @IntRange(from = MIN_URSP_PRECEDENCE, to = MAX_URSP_PRECEDENCE)
    private final int mPrecedence;
    private final List<TrafficDescriptor> mTrafficDescriptors;
    private final List<RouteSelectionDescriptor> mRouteSelectionDescriptor;

    /** @hide */
    public UrspRule(int precedence, List<TrafficDescriptor> trafficDescriptors,
            List<RouteSelectionDescriptor> routeSelectionDescriptor) {
        mPrecedence = precedence;
        mTrafficDescriptors = new ArrayList<>();
        mTrafficDescriptors.addAll(trafficDescriptors);
        mRouteSelectionDescriptor = new ArrayList<>();
        mRouteSelectionDescriptor.addAll(routeSelectionDescriptor);
    }

    private UrspRule(Parcel p) {
        mPrecedence = p.readInt();
        mTrafficDescriptors = p.createTypedArrayList(TrafficDescriptor.CREATOR);
        mRouteSelectionDescriptor = p.createTypedArrayList(RouteSelectionDescriptor.CREATOR);
    }

    /**
     * Precedence value in the range of 0 to 255. Higher value has lower precedence.
     * @return the precedence value for this URSP rule.
     */
    @IntRange(from = MIN_URSP_PRECEDENCE, to = MAX_URSP_PRECEDENCE)
    public int getPrecedence() {
        return mPrecedence;
    }

    /**
     * These traffic descriptors are used as a matcher for network requests.
     * @return the traffic descriptors which are associated to this URSP rule.
     */
    public @NonNull List<TrafficDescriptor> getTrafficDescriptors() {
        return mTrafficDescriptors;
    }

    /**
     * List of routes (connection parameters) that must be used by the device for requests matching
     * a traffic descriptor.
     * @return the route selection descriptors which are associated to this URSP rule.
     */
    public @NonNull List<RouteSelectionDescriptor> getRouteSelectionDescriptor() {
        return mRouteSelectionDescriptor;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPrecedence);
        dest.writeTypedList(mTrafficDescriptors, flags);
        dest.writeTypedList(mRouteSelectionDescriptor, flags);
    }

    public static final @NonNull Parcelable.Creator<UrspRule> CREATOR =
            new Parcelable.Creator<UrspRule>() {
                @Override
                public UrspRule createFromParcel(Parcel source) {
                    return new UrspRule(source);
                }

                @Override
                public UrspRule[] newArray(int size) {
                    return new UrspRule[size];
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
        UrspRule that = (UrspRule) o;
        return mPrecedence == that.mPrecedence
                && mTrafficDescriptors.size() == that.mTrafficDescriptors.size()
                && mTrafficDescriptors.containsAll(that.mTrafficDescriptors)
                && mRouteSelectionDescriptor.size() == that.mRouteSelectionDescriptor.size()
                && mRouteSelectionDescriptor.containsAll(that.mRouteSelectionDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPrecedence, mTrafficDescriptors, mRouteSelectionDescriptor);
    }

    @Override
    public String toString() {
        return "{.precedence = " + mPrecedence + ", .trafficDescriptors = " + mTrafficDescriptors
                + ", .routeSelectionDescriptor = " + mRouteSelectionDescriptor + "}";
    }
}
