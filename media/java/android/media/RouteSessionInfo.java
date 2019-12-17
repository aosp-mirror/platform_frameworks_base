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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

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

    final int mSessionId;
    final String mPackageName;
    final String mControlCategory;
    final List<String> mSelectedRoutes;
    final List<String> mDeselectableRoutes;
    final List<String> mGroupableRoutes;
    final List<String> mTransferrableRoutes;

    RouteSessionInfo(@NonNull Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null.");

        mSessionId = builder.mSessionId;
        mPackageName = builder.mPackageName;
        mControlCategory = builder.mControlCategory;

        mSelectedRoutes = Collections.unmodifiableList(builder.mSelectedRoutes);
        mDeselectableRoutes = Collections.unmodifiableList(builder.mDeselectableRoutes);
        mGroupableRoutes = Collections.unmodifiableList(builder.mGroupableRoutes);
        mTransferrableRoutes = Collections.unmodifiableList(builder.mTransferrableRoutes);
    }

    RouteSessionInfo(@NonNull Parcel src) {
        Objects.requireNonNull(src, "src must not be null.");

        mSessionId = src.readInt();
        mPackageName = ensureString(src.readString());
        mControlCategory = ensureString(src.readString());

        mSelectedRoutes = ensureList(src.createStringArrayList());
        mDeselectableRoutes = ensureList(src.createStringArrayList());
        mGroupableRoutes = ensureList(src.createStringArrayList());
        mTransferrableRoutes = ensureList(src.createStringArrayList());
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
     */
    public boolean isValid() {
        return !TextUtils.isEmpty(mPackageName)
                && !TextUtils.isEmpty(mControlCategory)
                && mSelectedRoutes.size() > 0;
    }

    /**
     * Gets the id of the session
     */
    @NonNull
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Gets the control category of the session.
     * Routes that don't support the category can't be added to the session.
     */
    @NonNull
    public String getControlCategory() {
        return mControlCategory;
    }

    /**
     * Gets the list of ids of selected routes for the session. It shouldn't be empty.
     */
    @NonNull
    public List<String> getSelectedRoutes() {
        return mSelectedRoutes;
    }

    /**
     * Gets the list of ids of deselectable routes for the session.
     */
    @NonNull
    public List<String> getDeselectableRoutes() {
        return mDeselectableRoutes;
    }

    /**
     * Gets the list of ids of groupable routes for the session.
     */
    @NonNull
    public List<String> getGroupableRoutes() {
        return mGroupableRoutes;
    }

    /**
     * Gets the list of ids of transferrable routes for the session.
     */
    @NonNull
    public List<String> getTransferrableRoutes() {
        return mTransferrableRoutes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSessionId);
        dest.writeString(mPackageName);
        dest.writeString(mControlCategory);
        dest.writeStringList(mSelectedRoutes);
        dest.writeStringList(mDeselectableRoutes);
        dest.writeStringList(mGroupableRoutes);
        dest.writeStringList(mTransferrableRoutes);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("RouteSessionInfo{ ")
                .append("sessionId=").append(mSessionId)
                .append(", selectedRoutes={");
        for (int i = 0; i < mSelectedRoutes.size(); i++) {
            if (i > 0) result.append(", ");
            result.append(mSelectedRoutes.get(i));
        }
        result.append("}");
        result.append(" }");
        return result.toString();
    }

    /**
     * Builder class for {@link RouteSessionInfo}.
     */
    public static final class Builder {
        final String mPackageName;
        final int mSessionId;
        final String mControlCategory;
        final List<String> mSelectedRoutes;
        final List<String> mDeselectableRoutes;
        final List<String> mGroupableRoutes;
        final List<String> mTransferrableRoutes;

        public Builder(int sessionId, @NonNull String packageName,
                @NonNull String controlCategory) {
            mSessionId = sessionId;
            mPackageName = Objects.requireNonNull(packageName, "packageName must not be null");
            mControlCategory = Objects.requireNonNull(controlCategory,
                    "controlCategory must not be null");

            mSelectedRoutes = new ArrayList<>();
            mDeselectableRoutes = new ArrayList<>();
            mGroupableRoutes = new ArrayList<>();
            mTransferrableRoutes = new ArrayList<>();
        }

        public Builder(RouteSessionInfo sessionInfo) {
            mSessionId = sessionInfo.mSessionId;
            mPackageName = sessionInfo.mPackageName;
            mControlCategory = sessionInfo.mControlCategory;

            mSelectedRoutes = new ArrayList<>(sessionInfo.mSelectedRoutes);
            mDeselectableRoutes = new ArrayList<>(sessionInfo.mDeselectableRoutes);
            mGroupableRoutes = new ArrayList<>(sessionInfo.mGroupableRoutes);
            mTransferrableRoutes = new ArrayList<>(sessionInfo.mTransferrableRoutes);
        }

        /**
         * Adds a selected route
         */
        @NonNull
        public Builder addSelectedRoute(String routeId) {
            mSelectedRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a selected route
         */
        @NonNull
        public Builder removeSelectedRoute(String routeId) {
            mSelectedRoutes.remove(routeId);
            return this;
        }

        /**
         * Adds a deselectable route
         */
        @NonNull
        public Builder addDeselectableRoute(String routeId) {
            mDeselectableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a deselecable route
         */
        @NonNull
        public Builder removeDeselectableRoute(String routeId) {
            mDeselectableRoutes.remove(routeId);
            return this;
        }

        /**
         * Adds a groupable route
         */
        @NonNull
        public Builder addGroupableRoute(String routeId) {
            mGroupableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a groupable route
         */
        @NonNull
        public Builder removeGroupableRoute(String routeId) {
            mGroupableRoutes.remove(routeId);
            return this;
        }

        /**
         * Adds a transferrable route
         */
        @NonNull
        public Builder addTransferrableRoute(String routeId) {
            mTransferrableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a transferrable route
         */
        @NonNull
        public Builder removeTransferrableRoute(String routeId) {
            mTransferrableRoutes.remove(routeId);
            return this;
        }

        /**
         * Builds a route session info
         */
        @NonNull
        public RouteSessionInfo build() {
            return new RouteSessionInfo(this);
        }
    }
}
