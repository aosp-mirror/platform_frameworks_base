/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.app.search;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * A representation of a searchable item info.
 *
 * @hide
 */
@SystemApi
public final class SearchTarget implements Parcelable {


    @NonNull
    private final int mResultType;

    /**
     * Constant to express how the group of {@link SearchTarget} should be laid out.
     */
    @NonNull
    private final String mLayoutType;

    @NonNull
    private final String mId;

    @Nullable
    private String mParentId;

    private final float mScore;

    private final boolean mShouldHide;

    @NonNull
    private final String mPackageName;
    @NonNull
    private final UserHandle mUserHandle;
    @Nullable
    private final SearchAction mSearchAction;
    @Nullable
    private final ShortcutInfo mShortcutInfo;
    @Nullable
    private final AppWidgetProviderInfo mAppWidgetProviderInfo;
    @Nullable
    private final Uri mSliceUri;
    @Nullable
    private final Bundle mExtras;

    private SearchTarget(Parcel parcel) {
        mResultType = parcel.readInt();
        mLayoutType = parcel.readString();

        mId = parcel.readString();
        mParentId = parcel.readString();
        mScore = parcel.readFloat();
        mShouldHide = parcel.readBoolean();

        mPackageName = parcel.readString();
        mUserHandle = UserHandle.of(parcel.readInt());
        mSearchAction = parcel.readTypedObject(SearchAction.CREATOR);
        mShortcutInfo = parcel.readTypedObject(ShortcutInfo.CREATOR);
        mAppWidgetProviderInfo = parcel.readTypedObject(AppWidgetProviderInfo.CREATOR);
        mSliceUri = parcel.readTypedObject(Uri.CREATOR);
        mExtras = parcel.readBundle(getClass().getClassLoader());
    }

    private SearchTarget(
            int resultType,
            @NonNull String layoutType,
            @NonNull String id,
            @Nullable String parentId,
            float score, boolean shouldHide,
            @NonNull String packageName,
            @NonNull UserHandle userHandle,
            @Nullable SearchAction action,
            @Nullable ShortcutInfo shortcutInfo,
            @Nullable Uri sliceUri,
            @Nullable AppWidgetProviderInfo appWidgetProviderInfo,
            @Nullable Bundle extras) {
        mResultType = resultType;
        mLayoutType = Objects.requireNonNull(layoutType);
        mId = Objects.requireNonNull(id);
        mParentId = parentId;
        mScore = score;
        mShouldHide = shouldHide;
        mPackageName = Objects.requireNonNull(packageName);
        mUserHandle = Objects.requireNonNull(userHandle);
        mSearchAction = action;
        mShortcutInfo = shortcutInfo;
        mAppWidgetProviderInfo = appWidgetProviderInfo;
        mSliceUri = sliceUri;
        mExtras = extras;

        int published = 0;
        if (mSearchAction != null) published++;
        if (mShortcutInfo != null) published++;
        if (mAppWidgetProviderInfo != null) published++;
        if (mSliceUri != null) published++;
        if (published > 1) {
            throw new IllegalStateException("Only one of SearchAction, ShortcutInfo,"
                    + " AppWidgetProviderInfo, SliceUri can be assigned in a SearchTarget.");
        }
    }

    /**
     * Retrieves the result type.
     */
    public int getResultType() {
        return mResultType;
    }

    /**
     * Retrieves the layout type.
     */
    @NonNull
    public String getLayoutType() {
        return mLayoutType;
    }

    /**
     * Retrieves the id of the target.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Retrieves the parent id of the target.
     */
    @NonNull
    public String getParentId() {
        return mParentId;
    }

    /**
     * Retrieves the score of the target.
     */
    public float getScore() {
        return mScore;
    }

    /**
     * TODO: add comment
     */
    public boolean shouldHide() {
        return mShouldHide;
    }

    /**
     * Retrieves the package name of the target.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Retrieves the user handle of the target.
     */
    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * Retrieves the shortcut info of the target.
     */
    @Nullable
    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    /**
     * Return widget provider info.
     */
    @Nullable
    public AppWidgetProviderInfo getAppWidgetProviderInfo() {
        return mAppWidgetProviderInfo;
    }

    /**
     * Return slice uri.
     */
    @Nullable
    public Uri getSliceUri() {
        return mSliceUri;
    }

    /**
     * Return search action.
     */
    @Nullable
    public SearchAction getSearchAction() {
        return mSearchAction;
    }

    /**
     * Return extra bundle.
     */
    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mResultType);
        parcel.writeString(mLayoutType);
        parcel.writeString(mId);
        parcel.writeString(mParentId);
        parcel.writeFloat(mScore);
        parcel.writeBoolean(mShouldHide);
        parcel.writeString(mPackageName);
        parcel.writeInt(mUserHandle.getIdentifier());
        parcel.writeTypedObject(mSearchAction, flags);
        parcel.writeTypedObject(mShortcutInfo, flags);
        parcel.writeTypedObject(mAppWidgetProviderInfo, flags);
        parcel.writeTypedObject(mSliceUri, flags);
        parcel.writeBundle(mExtras);
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Parcelable.Creator<SearchTarget> CREATOR =
            new Parcelable.Creator<SearchTarget>() {
                public SearchTarget createFromParcel(Parcel parcel) {
                    return new SearchTarget(parcel);
                }

                public SearchTarget[] newArray(int size) {
                    return new SearchTarget[size];
                }
            };

    /**
     * A builder for search target object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private int mResultType;
        @NonNull
        private String mLayoutType;
        @NonNull
        private String mId;
        @Nullable
        private String mParentId;
        private float mScore;
        private boolean mShouldHide;
        @NonNull
        private String mPackageName;
        @NonNull
        private UserHandle mUserHandle;
        @Nullable
        private SearchAction mSearchAction;
        @Nullable
        private ShortcutInfo mShortcutInfo;
        @Nullable
        private Uri mSliceUri;
        @Nullable
        private AppWidgetProviderInfo mAppWidgetProviderInfo;
        @Nullable
        private Bundle mExtras;

        public Builder(int resultType,
                @NonNull String layoutType,
                @NonNull String id) {
            mId = id;
            mLayoutType = Objects.requireNonNull(layoutType);
            mResultType = resultType;
            mScore = 1f;
            mShouldHide = false;
        }

        /**
         * Sets the parent id.
         */
        @NonNull
        public Builder setParentId(@NonNull String parentId) {
            mParentId = Objects.requireNonNull(parentId);
            return this;
        }

        /**
         * Sets the package name.
         */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            mPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Sets the user handle.
         */
        @NonNull
        public Builder setUserHandle(@NonNull UserHandle userHandle) {
            mUserHandle = Objects.requireNonNull(userHandle);
            return this;
        }

        /**
         * Sets the shortcut info.
         */
        @NonNull
        public Builder setShortcutInfo(@NonNull ShortcutInfo shortcutInfo) {
            mShortcutInfo = Objects.requireNonNull(shortcutInfo);
            if (mPackageName != null && !mPackageName.equals(shortcutInfo.getPackage())) {
                throw new IllegalStateException("SearchTarget packageName is different from "
                        + "shortcut's packageName");
            }
            mPackageName = shortcutInfo.getPackage();
            return this;
        }

        /**
         * Sets the app widget provider info.
         */
        @NonNull
        public Builder setAppWidgetProviderInfo(
                @NonNull AppWidgetProviderInfo appWidgetProviderInfo) {
            mAppWidgetProviderInfo = Objects.requireNonNull(appWidgetProviderInfo);
            if (mPackageName != null
                    && !mPackageName.equals(appWidgetProviderInfo.provider.getPackageName())) {
                throw new IllegalStateException("SearchTarget packageName is different from "
                        + "appWidgetProviderInfo's packageName");
            }
            return this;
        }

        /**
         * Sets the slice URI.
         */
        @NonNull
        public Builder setSliceUri(@NonNull Uri sliceUri) {
            // TODO: add packageName check
            mSliceUri = sliceUri;
            return this;
        }

        /**
         * TODO: add comment
         */
        @NonNull
        public Builder setSearchAction(@Nullable SearchAction remoteAction) {
            // TODO: add packageName check
            mSearchAction = remoteAction;
            return this;
        }

        /**
         * TODO: add comment
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * TODO: add comment
         */
        @NonNull
        public Builder setScore(float score) {
            mScore = score;
            return this;
        }

        /**
         * TODO: add comment
         */
        @NonNull
        public Builder setShouldHide(boolean shouldHide) {
            mShouldHide = shouldHide;
            return this;
        }

        /**
         * Builds a new SearchTarget instance.
         *
         * @throws IllegalStateException if no target is set
         */
        @NonNull
        public SearchTarget build() {
            return new SearchTarget(mResultType, mLayoutType, mId, mParentId, mScore, mShouldHide,
                    mPackageName, mUserHandle,
                    mSearchAction, mShortcutInfo, mSliceUri, mAppWidgetProviderInfo,
                    mExtras);
        }
    }
}
