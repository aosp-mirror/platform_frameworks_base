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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Used to pass in the required information for updating an enterprise string resource using
 * {@link DevicePolicyManager#setStrings}.
 *
 * @hide
 */
@SystemApi
public final class DevicePolicyStringResource implements Parcelable {
    @NonNull private final String mStringId;
    private final @StringRes int mResourceIdInCallingPackage;
    @NonNull private ParcelableResource mResource;

    /**
     * Creates an object containing the required information for updating an enterprise string
     * resource using {@link DevicePolicyManager#setStrings}.
     *
     * <p>It will be used to update the string defined by {@code stringId} to the string with ID
     * {@code resourceIdInCallingPackage} in the calling package</p>
     *
     * @param stringId The ID of the string to update.
     * @param resourceIdInCallingPackage The ID of the {@link StringRes} in the calling package to
     * use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID {@code resourceIdInCallingPackage}
     * doesn't exist in the {@code context} package.
     */
    public DevicePolicyStringResource(
            @NonNull Context context,
            @NonNull String stringId,
            @StringRes int resourceIdInCallingPackage) {
        this(stringId, resourceIdInCallingPackage, new ParcelableResource(
                context, resourceIdInCallingPackage, ParcelableResource.RESOURCE_TYPE_STRING));
    }

    private DevicePolicyStringResource(
            @NonNull String stringId,
            @StringRes int resourceIdInCallingPackage,
            @NonNull ParcelableResource resource) {
        Objects.requireNonNull(stringId, "stringId must be provided.");
        Objects.requireNonNull(resource, "ParcelableResource must be provided.");

        this.mStringId = stringId;
        this.mResourceIdInCallingPackage = resourceIdInCallingPackage;
        this.mResource = resource;
    }

    /**
     * Returns the ID of the string to update.
     */
    @NonNull
    public String getStringId() {
        return mStringId;
    }

    /**
     * Returns the ID of the {@link StringRes} in the calling package to use as an updated
     * resource.
     */
    public int getResourceIdInCallingPackage() {
        return mResourceIdInCallingPackage;
    }

    /**
     * Returns the {@link ParcelableResource} of the string.
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
        DevicePolicyStringResource other = (DevicePolicyStringResource) o;
        return mStringId == other.mStringId
                && mResourceIdInCallingPackage == other.mResourceIdInCallingPackage
                && mResource.equals(other.mResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStringId, mResourceIdInCallingPackage, mResource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mStringId);
        dest.writeInt(mResourceIdInCallingPackage);
        dest.writeTypedObject(mResource, flags);
    }

    public static final @NonNull Creator<DevicePolicyStringResource> CREATOR =
            new Creator<DevicePolicyStringResource>() {
        @Override
        public DevicePolicyStringResource createFromParcel(Parcel in) {
            String stringId = in.readString();
            int resourceIdInCallingPackage = in.readInt();
            ParcelableResource resource = in.readTypedObject(ParcelableResource.CREATOR);

            return new DevicePolicyStringResource(stringId, resourceIdInCallingPackage, resource);
        }

        @Override
        public DevicePolicyStringResource[] newArray(int size) {
            return new DevicePolicyStringResource[size];
        }
    };
}
