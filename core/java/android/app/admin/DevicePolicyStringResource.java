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
    @NonNull private final @DevicePolicyResources.UpdatableStringId String mStringId;
    private final @StringRes int mCallingPackageResourceId;
    @NonNull private ParcelableResource mResource;

    /**
     * Creates an object containing the required information for updating an enterprise string
     * resource using {@link DevicePolicyManager#setStrings}.
     *
     * <p>It will be used to update the string defined by {@code stringId} to the string with ID
     * {@code callingPackageResourceId} in the calling package</p>
     *
     * @param stringId The ID of the string to update.
     * @param callingPackageResourceId The ID of the {@link StringRes} in the calling package to
     * use as an updated resource.
     *
     * @throws IllegalStateException if the resource with ID {@code callingPackageResourceId}
     * doesn't exist in the {@code context} package.
     */
    public DevicePolicyStringResource(
            @NonNull Context context,
            @NonNull @DevicePolicyResources.UpdatableStringId String stringId,
            @StringRes int callingPackageResourceId) {
        this(stringId, callingPackageResourceId, new ParcelableResource(
                context, callingPackageResourceId, ParcelableResource.RESOURCE_TYPE_STRING));
    }

    private DevicePolicyStringResource(
            @NonNull @DevicePolicyResources.UpdatableStringId String stringId,
            @StringRes int callingPackageResourceId,
            @NonNull ParcelableResource resource) {
        Objects.requireNonNull(stringId, "stringId must be provided.");
        Objects.requireNonNull(resource, "ParcelableResource must be provided.");

        this.mStringId = stringId;
        this.mCallingPackageResourceId = callingPackageResourceId;
        this.mResource = resource;
    }

    /**
     * Returns the ID of the string to update.
     */
    @DevicePolicyResources.UpdatableStringId
    @NonNull
    public String getStringId() {
        return mStringId;
    }

    /**
     * Returns the ID of the {@link StringRes} in the calling package to use as an updated
     * resource.
     */
    public int getCallingPackageResourceId() {
        return mCallingPackageResourceId;
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
                && mCallingPackageResourceId == other.mCallingPackageResourceId
                && mResource.equals(other.mResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStringId, mCallingPackageResourceId, mResource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mStringId);
        dest.writeInt(mCallingPackageResourceId);
        dest.writeTypedObject(mResource, flags);
    }

    public static final @NonNull Creator<DevicePolicyStringResource> CREATOR =
            new Creator<DevicePolicyStringResource>() {
        @Override
        public DevicePolicyStringResource createFromParcel(Parcel in) {
            String stringId = in.readString();
            int callingPackageResourceId = in.readInt();
            ParcelableResource resource = in.readTypedObject(ParcelableResource.CREATOR);

            return new DevicePolicyStringResource(stringId, callingPackageResourceId, resource);
        }

        @Override
        public DevicePolicyStringResource[] newArray(int size) {
            return new DevicePolicyStringResource[size];
        }
    };
}
