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
    @NonNull private final String mDrawableId;
    @NonNull private final String mDrawableStyle;
    @NonNull private final String mDrawableSource;
    private final @DrawableRes int mResourceIdInCallingPackage;
    @NonNull private ParcelableResource mResource;

    /**
     * Creates an object containing the required information for updating an enterprise drawable
     * resource using {@link DevicePolicyManager#setDrawables}.
     *
     * <p>It will be used to update the drawable defined by {@code drawableId} with style
     * {@code drawableStyle} located in source {@code drawableSource} to the drawable with ID
     * {@code resourceIdInCallingPackage} in the calling package</p>
     *
     * @param drawableId The ID of the drawable to update.
     * @param drawableStyle The style of the drawable to update.
     * @param drawableSource The source of the drawable to update.
     * @param resourceIdInCallingPackage The ID of the drawable resource in the calling package to
     *        use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID
     * {@code resourceIdInCallingPackage} doesn't exist in the {@code context} package.
     */
    public DevicePolicyDrawableResource(
            @NonNull Context context,
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull String drawableSource,
            @DrawableRes int resourceIdInCallingPackage) {
        this(drawableId, drawableStyle, drawableSource, resourceIdInCallingPackage,
                new ParcelableResource(context, resourceIdInCallingPackage,
                        ParcelableResource.RESOURCE_TYPE_DRAWABLE));
    }

    private DevicePolicyDrawableResource(
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @NonNull String drawableSource,
            @DrawableRes int resourceIdInCallingPackage,
            @NonNull ParcelableResource resource) {

        Objects.requireNonNull(drawableId);
        Objects.requireNonNull(drawableStyle);
        Objects.requireNonNull(drawableSource);
        Objects.requireNonNull(resource);

        this.mDrawableId = drawableId;
        this.mDrawableStyle = drawableStyle;
        this.mDrawableSource = drawableSource;
        this.mResourceIdInCallingPackage = resourceIdInCallingPackage;
        this.mResource = resource;
    }

    /**
     * Creates an object containing the required information for updating an enterprise drawable
     * resource using {@link DevicePolicyManager#setDrawables}.
     * <p>It will be used to update the drawable defined by {@code drawableId} with style
     * {@code drawableStyle} to the drawable with ID {@code resourceIdInCallingPackage} in the
     * calling package</p>
     *
     * @param drawableId The ID of the drawable to update.
     * @param drawableStyle The style of the drawable to update.
     * @param resourceIdInCallingPackage The ID of the drawable resource in the calling package to
     *        use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID
     * {@code resourceIdInCallingPackage} doesn't exist in the calling package.
     */
    public DevicePolicyDrawableResource(
            @NonNull Context context,
            @NonNull String drawableId,
            @NonNull String drawableStyle,
            @DrawableRes int resourceIdInCallingPackage) {
       this(context, drawableId, drawableStyle, DevicePolicyResources.UNDEFINED,
               resourceIdInCallingPackage);
    }

    /**
     * Returns the ID of the drawable to update.
     */
    @NonNull
    public String getDrawableId() {
        return mDrawableId;
    }

    /**
     * Returns the style of the drawable to update
     */
    @NonNull
    public String getDrawableStyle() {
        return mDrawableStyle;
    }

    /**
     * Returns the source of the drawable to update.
     */
    @NonNull
    public String getDrawableSource() {
        return mDrawableSource;
    }

    /**
     * Returns the ID of the drawable resource in the calling package to use as an updated
     * resource.
     */
    @DrawableRes
    public int getResourceIdInCallingPackage() {
        return mResourceIdInCallingPackage;
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
        return mDrawableId.equals(other.mDrawableId)
                && mDrawableStyle.equals(other.mDrawableStyle)
                && mDrawableSource.equals(other.mDrawableSource)
                && mResourceIdInCallingPackage == other.mResourceIdInCallingPackage
                && mResource.equals(other.mResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDrawableId, mDrawableStyle, mDrawableSource,
                mResourceIdInCallingPackage, mResource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDrawableId);
        dest.writeString(mDrawableStyle);
        dest.writeString(mDrawableSource);
        dest.writeInt(mResourceIdInCallingPackage);
        dest.writeTypedObject(mResource, flags);
    }

    public static final @NonNull Creator<DevicePolicyDrawableResource> CREATOR =
            new Creator<DevicePolicyDrawableResource>() {
                @Override
                public DevicePolicyDrawableResource createFromParcel(Parcel in) {
                    String drawableId = in.readString();
                    String drawableStyle = in.readString();
                    String drawableSource = in.readString();
                    int resourceIdInCallingPackage = in.readInt();
                    ParcelableResource resource = in.readTypedObject(ParcelableResource.CREATOR);

                    return new DevicePolicyDrawableResource(
                            drawableId, drawableStyle, drawableSource, resourceIdInCallingPackage,
                            resource);
                }

                @Override
                public DevicePolicyDrawableResource[] newArray(int size) {
                    return new DevicePolicyDrawableResource[size];
                }
            };
}
