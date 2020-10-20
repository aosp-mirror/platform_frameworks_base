/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.permission;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * This class contains information about how a runtime permission
 * is to be presented in the UI. A single runtime permission
 * presented to the user may correspond to multiple platform defined
 * permissions, e.g. the location permission may control both the
 * coarse and fine platform permissions.
 *
 * @hide
 */
@SystemApi
public final class RuntimePermissionPresentationInfo implements Parcelable {
    private static final int FLAG_GRANTED = 1 << 0;
    private static final int FLAG_STANDARD = 1 << 1;

    private final @NonNull CharSequence mLabel;
    private final int mFlags;

    /**
     * Creates a new instance.
     *
     * @param label The permission label.
     * @param granted Whether the permission is granted.
     * @param standard Whether this is a platform-defined permission.
     */
    public RuntimePermissionPresentationInfo(@NonNull CharSequence label,
            boolean granted, boolean standard) {
        Preconditions.checkNotNull(label);

        mLabel = label;
        int flags = 0;
        if (granted) {
            flags |= FLAG_GRANTED;
        }
        if (standard) {
            flags |= FLAG_STANDARD;
        }
        mFlags = flags;
    }

    /**
     * @return Whether the permission is granted.
     */
    public boolean isGranted() {
        return (mFlags & FLAG_GRANTED) != 0;
    }

    /**
     * @return Whether the permission is platform-defined.
     */
    public boolean isStandard() {
        return (mFlags & FLAG_STANDARD) != 0;
    }

    /**
     * Gets the permission label.
     *
     * @return The label.
     */
    public @NonNull CharSequence getLabel() {
        return mLabel;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeCharSequence(mLabel);
        parcel.writeInt(mFlags);
    }

    public static final @NonNull Creator<RuntimePermissionPresentationInfo> CREATOR =
            new Creator<RuntimePermissionPresentationInfo>() {
        public RuntimePermissionPresentationInfo createFromParcel(Parcel source) {
            CharSequence label = source.readCharSequence();
            int flags = source.readInt();

            return new RuntimePermissionPresentationInfo(label, (flags & FLAG_GRANTED) != 0,
                    (flags & FLAG_STANDARD) != 0);
        }

        public RuntimePermissionPresentationInfo[] newArray(int size) {
            return new RuntimePermissionPresentationInfo[size];
        }
    };
}
