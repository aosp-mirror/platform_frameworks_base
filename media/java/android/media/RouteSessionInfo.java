/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes a route session that is made when a media route is selected.
 * @hide
 */
public class RouteSessionInfo implements Parcelable {

    @NonNull
    public static final Creator<RouteSessionInfo> CREATOR =
            new Creator<RouteSessionInfo>() {
                @Override
                public RouteSessionInfo createFromParcel(Parcel in) {
                    return new RouteSessionInfo(in);
                }
                @Override
                public RouteSessionInfo[] newArray(int size) {
                    return new RouteSessionInfo[size];
                }
            };

    public static final String TAG = "RouteSessionInfo";

    final String mId;
    final String mClientPackageName;
    final String mRouteType;
    @Nullable
    final String mProviderId;
    final List<String> mSelectedRoutes;
    final List<String> mSelectableRoutes;
    final List<String> mDeselectableRoutes;
    final List<String> mTransferrableRoutes;
    @Nullable
    final Bundle mControlHints;

    RouteSessionInfo(@NonNull Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null.");

        mId = builder.mId;
        mClientPackageName = builder.mClientPackageName;
        mRouteType = builder.mRouteType;
        mProviderId = builder.mProviderId;

        mSelectedRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mSelectedRoutes));
        mSelectableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mSelectableRoutes));
        mDeselectableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mDeselectableRoutes));
        mTransferrableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mTransferrableRoutes));

        mControlHints = builder.mControlHints;
    }

    RouteSessionInfo(@NonNull Parcel src) {
        Objects.requireNonNull(src, "src must not be null.");

        mId = ensureString(src.readString());
        mClientPackageName = ensureString(src.readString());
        mRouteType = ensureString(src.readString());
        mProviderId = src.readString();

        mSelectedRoutes = ensureList(src.createStringArrayList());
        mSelectableRoutes = ensureList(src.createStringArrayList());
        mDeselectableRoutes = ensureList(src.createStringArrayList());
        mTransferrableRoutes = ensureList(src.createStringArrayList());

        mControlHints = src.readBundle();
    }

    private static String ensureString(String str) {
        if (str != null) {
            return str;
        }
        return "";
    }

    private static <T> List<T> ensureList(List<? extends T> list) {
        if (list != null) {
            return Collections.unmodifiableList(list);
        }
        return Collections.emptyList();
    }

    /**
     * Returns whether the session info is valid or not
     *
     * TODO in this CL: Remove this method.
     */
    public boolean isValid() {
        return !TextUtils.isEmpty(mId)
                && !TextUtils.isEmpty(mClientPackageName)
                && !TextUtils.isEmpty(mRouteType)
                && mSelectedRoutes.size() > 0;
    }

    /**
     * Gets the id of the session. The sessions which are given by {@link MediaRouter2} will have
     * unique IDs.
     * <p>
     * In order to ensure uniqueness in {@link MediaRouter2} side, the value of this method
     * can be different from what was set in {@link MediaRoute2ProviderService}.
     *
     * @see Builder#Builder(String, String, String)
     */
    @NonNull
    public String getId() {
        if (mProviderId != null) {
            return MediaRouter2Utils.toUniqueId(mProviderId, mId);
        } else {
            return mId;
        }
    }

    /**
     * Gets the original id set by {@link Builder#Builder(String, String, String)}.
     * @hide
     */
    @NonNull
    public String getOriginalId() {
        return mId;
    }

    /**
     * Gets the client package name of the session
     */
    @NonNull
    public String getClientPackageName() {
        return mClientPackageName;
    }

    /**
     * Gets the route type of the session.
     * Routes that don't have the type can't be added to the session.
     */
    @NonNull
    public String getRouteType() {
        return mRouteType;
    }

    /**
     * Gets the provider id of the session.
     * @hide
     */
    @Nullable
    public String getProviderId() {
        return mProviderId;
    }

    /**
     * Gets the list of ids of selected routes for the session. It shouldn't be empty.
     */
    @NonNull
    public List<String> getSelectedRoutes() {
        return mSelectedRoutes;
    }

    /**
     * Gets the list of ids of selectable routes for the session.
     */
    @NonNull
    public List<String> getSelectableRoutes() {
        return mSelectableRoutes;
    }

    /**
     * Gets the list of ids of deselectable routes for the session.
     */
    @NonNull
    public List<String> getDeselectableRoutes() {
        return mDeselectableRoutes;
    }

    /**
     * Gets the list of ids of transferrable routes for the session.
     */
    @NonNull
    public List<String> getTransferrableRoutes() {
        return mTransferrableRoutes;
    }

    /**
     * Gets the control hints
     */
    @Nullable
    public Bundle getControlHints() {
        return mControlHints;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mClientPackageName);
        dest.writeString(mRouteType);
        dest.writeString(mProviderId);
        dest.writeStringList(mSelectedRoutes);
        dest.writeStringList(mSelectableRoutes);
        dest.writeStringList(mDeselectableRoutes);
        dest.writeStringList(mTransferrableRoutes);
        dest.writeBundle(mControlHints);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("RouteSessionInfo{ ")
                .append("sessionId=").append(mId)
                .append(", routeType=").append(mRouteType)
                .append(", selectedRoutes={")
                .append(String.join(",", mSelectedRoutes))
                .append("}")
                .append(", selectableRoutes={")
                .append(String.join(",", mSelectableRoutes))
                .append("}")
                .append(", deselectableRoutes={")
                .append(String.join(",", mDeselectableRoutes))
                .append("}")
                .append(", transferrableRoutes={")
                .append(String.join(",", mTransferrableRoutes))
                .append("}")
                .append(" }");
        return result.toString();
    }

    private List<String> convertToUniqueRouteIds(@NonNull List<String> routeIds) {
        if (routeIds == null) {
            Log.w(TAG, "routeIds is null. Returning an empty list");
            return Collections.emptyList();
        }

        // mProviderId can be null if not set. Return the original list for this case.
        if (mProviderId == null) {
            return routeIds;
        }

        List<String> result = new ArrayList<>();
        for (String routeId : routeIds) {
            result.add(MediaRouter2Utils.toUniqueId(mProviderId, routeId));
        }
        return result;
    }

    /**
     * Builder class for {@link RouteSessionInfo}.
     */
    public static final class Builder {
        final String mId;
        final String mClientPackageName;
        final String mRouteType;
        String mProviderId;
        final List<String> mSelectedRoutes;
        final List<String> mSelectableRoutes;
        final List<String> mDeselectableRoutes;
        final List<String> mTransferrableRoutes;
        Bundle mControlHints;

        /**
         * Constructor for builder to create {@link RouteSessionInfo}.
         * <p>
         * In order to ensure ID uniqueness in {@link MediaRouter2} side, the value of
         * {@link RouteSessionInfo#getId()} can be different from what was set in
         * {@link MediaRoute2ProviderService}.
         * </p>
         *
         * @see MediaRoute2Info#getId()
         */
        public Builder(@NonNull String id, @NonNull String clientPackageName,
                @NonNull String routeType) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be empty");
            }
            mId = id;
            mClientPackageName = Objects.requireNonNull(
                    clientPackageName, "clientPackageName must not be null");
            mRouteType = Objects.requireNonNull(routeType, "routeType must not be null");
            mSelectedRoutes = new ArrayList<>();
            mSelectableRoutes = new ArrayList<>();
            mDeselectableRoutes = new ArrayList<>();
            mTransferrableRoutes = new ArrayList<>();
        }

        /**
         * Constructor for builder to create {@link RouteSessionInfo} with
         * existing {@link RouteSessionInfo} instance.
         *
         * @param sessionInfo the existing instance to copy data from.
         */
        public Builder(@NonNull RouteSessionInfo sessionInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

            mId = sessionInfo.mId;
            mClientPackageName = sessionInfo.mClientPackageName;
            mRouteType = sessionInfo.mRouteType;
            mProviderId = sessionInfo.mProviderId;

            mSelectedRoutes = new ArrayList<>(sessionInfo.mSelectedRoutes);
            mSelectableRoutes = new ArrayList<>(sessionInfo.mSelectableRoutes);
            mDeselectableRoutes = new ArrayList<>(sessionInfo.mDeselectableRoutes);
            mTransferrableRoutes = new ArrayList<>(sessionInfo.mTransferrableRoutes);

            mControlHints = sessionInfo.mControlHints;
        }

        /**
         * Sets the provider ID of the session.
         * Also, calling this method will make all type of route IDs be unique by adding
         * {@code providerId:} as a prefix. So do NOT call this method twice on same instance.
         *
         * @hide
         */
        @NonNull
        public Builder setProviderId(@NonNull String providerId) {
            if (TextUtils.isEmpty(providerId)) {
                throw new IllegalArgumentException("providerId must not be empty");
            }
            mProviderId = providerId;
            return this;
        }

        /**
         * Clears the selected routes.
         */
        @NonNull
        public Builder clearSelectedRoutes() {
            mSelectedRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the selected routes.
         */
        @NonNull
        public Builder addSelectedRoute(@NonNull String routeId) {
            mSelectedRoutes.add(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Removes a route from the selected routes.
         */
        @NonNull
        public Builder removeSelectedRoute(@NonNull String routeId) {
            mSelectedRoutes.remove(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Clears the selectable routes.
         */
        @NonNull
        public Builder clearSelectableRoutes() {
            mSelectableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the selectable routes.
         */
        @NonNull
        public Builder addSelectableRoute(@NonNull String routeId) {
            mSelectableRoutes.add(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Removes a route from the selectable routes.
         */
        @NonNull
        public Builder removeSelectableRoute(@NonNull String routeId) {
            mSelectableRoutes.remove(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Clears the deselectable routes.
         */
        @NonNull
        public Builder clearDeselectableRoutes() {
            mDeselectableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the deselectable routes.
         */
        @NonNull
        public Builder addDeselectableRoute(@NonNull String routeId) {
            mDeselectableRoutes.add(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Removes a route from the deselectable routes.
         */
        @NonNull
        public Builder removeDeselectableRoute(@NonNull String routeId) {
            mDeselectableRoutes.remove(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Clears the transferrable routes.
         */
        @NonNull
        public Builder clearTransferrableRoutes() {
            mTransferrableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the transferrable routes.
         */
        @NonNull
        public Builder addTransferrableRoute(@NonNull String routeId) {
            mTransferrableRoutes.add(Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Removes a route from the transferrable routes.
         */
        @NonNull
        public Builder removeTransferrableRoute(@NonNull String routeId) {
            mTransferrableRoutes.remove(
                    Objects.requireNonNull(routeId, "routeId must not be null"));
            return this;
        }

        /**
         * Sets control hints.
         */
        @NonNull
        public Builder setControlHints(@Nullable Bundle controlHints) {
            mControlHints = controlHints;
            return this;
        }

        /**
         * Builds a route session info.
         */
        @NonNull
        public RouteSessionInfo build() {
            return new RouteSessionInfo(this);
        }
    }
}
