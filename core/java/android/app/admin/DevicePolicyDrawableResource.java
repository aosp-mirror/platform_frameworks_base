/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Used to pass in the required information for updating an enterprise drawable resource using
 * {@link DevicePolicyManager#setDrawables}.
 *
 * @hide
 */
@SystemApi
public final class DevicePolicyDrawableResource implements Parcelable {
    private final @DevicePolicyResources.UpdatableDrawableId int mDrawableId;
    private final @DevicePolicyResources.UpdatableDrawableStyle int mDrawableStyle;
    private final @DevicePolicyResources.UpdatableDrawableSource int mDrawableSource;
    private final @DrawableRes int mCallingPackageResourceId;
    @NonNull private ParcelableResource mResource;

    /**
     * Creates an object containing the required information for updating an enterprise drawable
     * resource using {@link DevicePolicyManager#setDrawables}.
     *
     * <p>It will be used to update the drawable defined by {@code drawableId} with style
     * {@code drawableStyle} located in source {@code drawableSource} to the drawable with ID
     * {@code callingPackageResourceId} in the calling package</p>
     *
     * @param drawableId The ID of the drawable to update.
     * @param drawableStyle The style of the drawable to update.
     * @param drawableSource The source of the drawable to update.
     * @param callingPackageResourceId The ID of the drawable resource in the calling package to
     *        use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID
     * {@code callingPackageResourceId} doesn't exist in the {@code context} package.
     */
    public DevicePolicyDrawableResource(
            @NonNull Context context,
            @DevicePolicyResources.UpdatableDrawableId int drawableId,
            @DevicePolicyResources.UpdatableDrawableStyle int drawableStyle,
            @DevicePolicyResources.UpdatableDrawableSource int drawableSource,
            @DrawableRes int callingPackageResourceId) {
        this(drawableId, drawableStyle, drawableSource, callingPackageResourceId,
                new ParcelableResource(context, callingPackageResourceId,
                        ParcelableResource.RESOURCE_TYPE_DRAWABLE));
    }

    private DevicePolicyDrawableResource(
            @DevicePolicyResources.UpdatableDrawableId int drawableId,
            @DevicePolicyResources.UpdatableDrawableStyle int drawableStyle,
            @DevicePolicyResources.UpdatableDrawableSource int drawableSource,
            @DrawableRes int callingPackageResourceId,
            @NonNull ParcelableResource resource) {
        this.mDrawableId = drawableId;
        this.mDrawableStyle = drawableStyle;
        this.mDrawableSource = drawableSource;
        this.mCallingPackageResourceId = callingPackageResourceId;
        this.mResource = resource;
    }

    /**
     * Creates an object containing the required information for updating an enterprise drawable
     * resource using {@link DevicePolicyManager#setDrawables}.
     * <p>It will be used to update the drawable defined by {@code drawableId} with style
     * {@code drawableStyle} to the drawable with ID {@code callingPackageResourceId} in the
     * calling package</p>
     *
     * @param drawableId The ID of the drawable to update.
     * @param drawableStyle The style of the drawable to update.
     * @param callingPackageResourceId The ID of the drawable resource in the calling package to
     *        use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID
     * {@code callingPackageResourceId} doesn't exist in the calling package.
     */
    public DevicePolicyDrawableResource(
            @NonNull Context context,
            @DevicePolicyResources.UpdatableDrawableId int drawableId,
            @DevicePolicyResources.UpdatableDrawableStyle int drawableStyle,
            @DrawableRes int callingPackageResourceId) {
       this(context, drawableId, drawableStyle, DevicePolicyResources.Drawable.Source.UNDEFINED,
               callingPackageResourceId);
    }

    /**
     * Returns the ID of the drawable to update.
     */
    @DevicePolicyResources.UpdatableDrawableId
    public int getDrawableId() {
        return mDrawableId;
    }

    /**
     * Returns the style of the drawable to update
     */
    @DevicePolicyResources.UpdatableDrawableStyle
    public int getDrawableStyle() {
        return mDrawableStyle;
    }

    /**
     * Returns the source of the drawable to update.
     */
    @DevicePolicyResources.UpdatableDrawableSource
    public int getDrawableSource() {
        return mDrawableSource;
    }

    /**
     * Returns the ID of the drawable resource in the calling package to use as an updated
     * resource.
     */
    @DrawableRes
    public int getCallingPackageResourceId() {
        return mCallingPackageResourceId;
    }

    /**
     * Returns the {@link ParcelableResource} of the drawable.
     *
     * @hide
     */
    @NonNull
    public ParcelableResource getResource() {
        return mResource;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DevicePolicyDrawableResource other = (DevicePolicyDrawableResource) o;
        return mDrawableId == other.mDrawableId
                && mDrawableStyle == other.mDrawableStyle
                && mDrawableSource == other.mDrawableSource
                && mCallingPackageResourceId == other.mCallingPackageResourceId
                && mResource.equals(other.mResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mDrawableId, mDrawableStyle, mDrawableSource, mCallingPackageResourceId, mResource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDrawableId);
        dest.writeInt(mDrawableStyle);
        dest.writeInt(mDrawableSource);
        dest.writeInt(mCallingPackageResourceId);
        dest.writeTypedObject(mResource, flags);
    }

    public static final @NonNull Creator<DevicePolicyDrawableResource> CREATOR =
            new Creator<DevicePolicyDrawableResource>() {
                @Override
                public DevicePolicyDrawableResource createFromParcel(Parcel in) {
                    int drawableId = in.readInt();
                    int drawableStyle = in.readInt();
                    int drawableSource = in.readInt();
                    int callingPackageResourceId = in.readInt();
                    ParcelableResource resource = in.readTypedObject(ParcelableResource.CREATOR);

                    return new DevicePolicyDrawableResource(
                            drawableId, drawableStyle, drawableSource, callingPackageResourceId,
                            resource);
                }

                @Override
                public DevicePolicyDrawableResource[] newArray(int size) {
                    return new DevicePolicyDrawableResource[size];
                }
            };
}
