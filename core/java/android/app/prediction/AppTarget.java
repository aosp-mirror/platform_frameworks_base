/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app.prediction;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.ShortcutInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * A representation of a launchable target.
 *
 * @hide
 */
@SystemApi
public final class AppTarget implements Parcelable {

    private final AppTargetId mId;
    private final String mPackageName;
    private final String mClassName;
    private final UserHandle mUser;

    private final ShortcutInfo mShortcutInfo;

    private final int mRank;

    /**
     * @deprecated use the Builder class
     * @hide
     */
    @Deprecated
    public AppTarget(@NonNull AppTargetId id, @NonNull String packageName,
            @Nullable String className, @NonNull UserHandle user) {
        mId = id;
        mShortcutInfo = null;

        mPackageName = Objects.requireNonNull(packageName);
        mClassName = className;
        mUser = Objects.requireNonNull(user);
        mRank = 0;
    }

    /**
     * @deprecated use the Builder class
     * @hide
     */
    @Deprecated
    public AppTarget(@NonNull AppTargetId id, @NonNull ShortcutInfo shortcutInfo,
            @Nullable String className) {
        mId = id;
        mShortcutInfo = Objects.requireNonNull(shortcutInfo);

        mPackageName = mShortcutInfo.getPackage();
        mUser = mShortcutInfo.getUserHandle();
        mClassName = className;
        mRank = 0;
    }

    private AppTarget(AppTargetId id, String packageName, UserHandle user,
            ShortcutInfo shortcutInfo, String className, int rank) {
        mId = id;
        mShortcutInfo = shortcutInfo;
        mPackageName = packageName;
        mClassName = className;
        mUser = user;
        mRank = rank;
    }

    private AppTarget(Parcel parcel) {
        mId = parcel.readTypedObject(AppTargetId.CREATOR);
        mShortcutInfo = parcel.readTypedObject(ShortcutInfo.CREATOR);
        if (mShortcutInfo == null) {
            mPackageName = parcel.readString();
            mUser = UserHandle.of(parcel.readInt());
        } else {
            mPackageName = mShortcutInfo.getPackage();
            mUser = mShortcutInfo.getUserHandle();
        }
        mClassName = parcel.readString();
        mRank = parcel.readInt();
    }

    /**
     * Returns the target id.
     */
    @NonNull
    public AppTargetId getId() {
        return mId;
    }

    /**
     * Returns the class name for the app target.
     */
    @Nullable
    public String getClassName() {
        return mClassName;
    }

    /**
     * Returns the package name for the app target.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the user for the app target.
     */
    @NonNull
    public UserHandle getUser() {
        return mUser;
    }

    /**
     * Returns the shortcut info for the target.
     */
    @Nullable
    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    /**
     * Returns the rank for the target. Rank of an AppTarget is a non-negative integer that
     * represents the importance of this target compared to other candidate targets. A smaller value
     * means higher importance in the list.
     */
    public @IntRange(from = 0) int getRank() {
        return mRank;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        AppTarget other = (AppTarget) o;
        boolean sameClassName = (mClassName == null && other.mClassName == null)
                || (mClassName != null && mClassName.equals(other.mClassName));
        boolean sameShortcutInfo = (mShortcutInfo == null && other.mShortcutInfo == null)
                || (mShortcutInfo != null && other.mShortcutInfo != null
                        && mShortcutInfo.getId() == other.mShortcutInfo.getId());
        return mId.equals(other.mId)
                && mPackageName.equals(other.mPackageName)
                && sameClassName
                && mUser.equals(other.mUser)
                && sameShortcutInfo
                && mRank == other.mRank;
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(mId, mPackageName, mClassName, mUser);
        if (mShortcutInfo != null) {
            hashCode = 31 * hashCode + mShortcutInfo.getId().hashCode();
        }
        hashCode = 31 * hashCode + mRank;
        return hashCode;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mId, flags);
        dest.writeTypedObject(mShortcutInfo, flags);
        if (mShortcutInfo == null) {
            dest.writeString(mPackageName);
            dest.writeInt(mUser.getIdentifier());
        }
        dest.writeString(mClassName);
        dest.writeInt(mRank);
    }

    /**
     * A builder for app targets.
     * @hide
     */
    @SystemApi
    public static final class Builder {

        @NonNull
        private final AppTargetId mId;

        private String mPackageName;
        private UserHandle mUser;
        private ShortcutInfo mShortcutInfo;

        private String mClassName;
        private int mRank;

        /**
         * @deprecated Use the other Builder constructors.
         * @hide
         * @removed
         */
        @Deprecated
        @SystemApi
        public Builder(@NonNull AppTargetId id) {
            mId = id;
        }

        /**
         * @param id A unique id for this launchable target.
         * @param packageName PackageName of the target.
         * @param user The UserHandle of the user which this target belongs to.
         * @hide
         */
        @SystemApi
        public Builder(@NonNull AppTargetId id, @NonNull String packageName,
                @NonNull UserHandle user) {
            mId = Objects.requireNonNull(id);
            mPackageName = Objects.requireNonNull(packageName);
            mUser = Objects.requireNonNull(user);
        }

        /**
         * @param id A unique id for this launchable target.
         * @param info The ShortcutInfo that represents this launchable target.
         * @hide
         */
        @SystemApi
        public Builder(@NonNull AppTargetId id, @NonNull ShortcutInfo info) {
            mId = Objects.requireNonNull(id);
            mShortcutInfo = Objects.requireNonNull(info);
            mPackageName = info.getPackage();
            mUser = info.getUserHandle();
        }

        /**
         * @deprecated Use the appropriate constructor.
         * @removed
         */
        @NonNull
        @Deprecated
        public Builder setTarget(@NonNull String packageName, @NonNull UserHandle user) {
            if (mPackageName != null) {
                throw new IllegalArgumentException("Target is already set");
            }
            mPackageName = Objects.requireNonNull(packageName);
            mUser = Objects.requireNonNull(user);
            return this;
        }

        /**
         * @deprecated Use the appropriate constructor.
         * @removed
         */
        @NonNull
        @Deprecated
        public Builder setTarget(@NonNull ShortcutInfo info) {
            setTarget(info.getPackage(), info.getUserHandle());
            mShortcutInfo = Objects.requireNonNull(info);
            return this;
        }

        /**
         * Sets the className for the target.
         */
        @NonNull
        public Builder setClassName(@NonNull String className) {
            mClassName = Objects.requireNonNull(className);
            return this;
        }

        /**
         * Sets the rank of the target.
         */
        @NonNull
        public Builder setRank(@IntRange(from = 0) int rank) {
            if (rank < 0) {
                throw new IllegalArgumentException("rank cannot be a negative value");
            }
            mRank = rank;
            return this;
        }

        /**
         * Builds a new AppTarget instance.
         *
         * @throws IllegalStateException if no target is set
         * @see #setTarget(ShortcutInfo)
         * @see #setTarget(String, UserHandle)
         */
        @NonNull
        public AppTarget build() {
            if (mPackageName == null) {
                throw new IllegalStateException("No target is set");
            }
            return new AppTarget(mId, mPackageName, mUser, mShortcutInfo, mClassName, mRank);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AppTarget> CREATOR =
            new Parcelable.Creator<AppTarget>() {
                public AppTarget createFromParcel(Parcel parcel) {
                    return new AppTarget(parcel);
                }

                public AppTarget[] newArray(int size) {
                    return new AppTarget[size];
                }
            };
}
