/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.companion.virtual;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import java.util.Objects;

/**
 * Specifies an exemption from the current default activity launch policy of a virtual device.
 *
 * <p>Note that changing the virtual device's activity launch policy will clear all current
 * exemptions.</p>
 *
 * @see VirtualDeviceParams#POLICY_TYPE_ACTIVITY
 * @see VirtualDeviceManager.VirtualDevice#setDevicePolicy
 * @see VirtualDeviceManager.VirtualDevice#addActivityPolicyExemption(ActivityPolicyExemption)
 * @see VirtualDeviceManager.VirtualDevice#removeActivityPolicyExemption(ActivityPolicyExemption)
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ACTIVITY_CONTROL_API)
@SystemApi
public final class ActivityPolicyExemption implements Parcelable {

    private final @Nullable ComponentName mComponentName;
    private final @Nullable String mPackageName;
    private final int mDisplayId;

    private ActivityPolicyExemption(@Nullable ComponentName componentName,
            @Nullable String packageName, int displayId) {
        mComponentName = componentName;
        mPackageName = packageName;
        mDisplayId = displayId;
    }

    private ActivityPolicyExemption(@NonNull Parcel parcel) {
        mComponentName = parcel.readTypedObject(ComponentName.CREATOR);
        mPackageName = parcel.readString8();
        mDisplayId = parcel.readInt();
    }

    /**
     * Returns the exempt component name if this is a component level exemption, {@code null}
     * otherwise.
     *
     * @see Builder#setComponentName(ComponentName)
     */
    public @Nullable ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the exempt package name if this is a package level exemption, {@code null} otherwise.
     *
     * @see Builder#setPackageName(String)
     */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the display ID relevant for this exemption if it is specific to a single display,
     * {@link Display#INVALID_DISPLAY} otherwise.
     *
     * @see Builder#setDisplayId(int)
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mComponentName, flags);
        dest.writeString8(mPackageName);
        dest.writeInt(mDisplayId);
    }

    @NonNull
    public static final Parcelable.Creator<ActivityPolicyExemption> CREATOR =
            new Parcelable.Creator<>() {
                public ActivityPolicyExemption createFromParcel(Parcel in) {
                    return new ActivityPolicyExemption(in);
                }

                public ActivityPolicyExemption[] newArray(int size) {
                    return new ActivityPolicyExemption[size];
                }
            };

    /**
     * Builder for {@link ActivityPolicyExemption}.
     */
    @FlaggedApi(Flags.FLAG_ACTIVITY_CONTROL_API)
    public static final class Builder {

        private @Nullable ComponentName mComponentName;
        private @Nullable String mPackageName;
        private int mDisplayId = Display.INVALID_DISPLAY;

        /**
         * Specifies a component level exemption from the current default activity launch policy.
         *
         * <p>If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} allows activity
         * launches by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_DEFAULT}),
         * then the specified component will be blocked from launching.
         * If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} blocks activity launches
         * by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM}), then the
         * specified component will be allowed to launch.</p>
         *
         * <p>Setting a component name will clear any previously set package name.</p>
         */
        public @NonNull Builder setComponentName(@NonNull ComponentName componentName) {
            mComponentName = Objects.requireNonNull(componentName);
            mPackageName = null;
            return this;
        }

        /**
         * Specifies a package level exemption from the current default activity launch policy.
         *
         * <p>If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} allows activity
         * launches by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_DEFAULT}),
         * then all activities from the specified package will be blocked from launching.
         * If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} blocks activity launches
         * by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM}), then all
         * activities from the specified package will be allowed to launch.</p>
         *
         * <p>Package level exemptions are independent of component level exemptions created via
         * {@link #setComponentName(ComponentName)}, i.e. removing a package exemption will not
         * remove any existing component exemptions, even if the component belongs to that package.
         * </p>
         *
         * <p>Setting a package name will clear any previously set component name.</p>
         */
        public @NonNull Builder setPackageName(@NonNull String packageName) {
            mComponentName = null;
            mPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Makes this exemption specific to the display with the given ID. If unset, or set to
         * {@link Display#INVALID_DISPLAY}, then the exemption is applied to all displays that
         * belong to the virtual device.
         *
         * @param displayId  the ID of the display, for which to apply the exemption. The display
         *   must belong to the virtual device.
         */
        public @NonNull Builder setDisplayId(int displayId) {
            mDisplayId = displayId;
            return this;
        }

        /**
         * Builds the {@link ActivityPolicyExemption} instance.
         *
         * @throws IllegalArgumentException if neither the component name nor the package name are
         *   set.
         */
        @NonNull
        public ActivityPolicyExemption build() {
            if ((mComponentName == null) == (mPackageName == null)) {
                throw new IllegalArgumentException(
                        "Either component name or package name must be set");
            }
            return new ActivityPolicyExemption(mComponentName, mPackageName, mDisplayId);
        }
    }
}
