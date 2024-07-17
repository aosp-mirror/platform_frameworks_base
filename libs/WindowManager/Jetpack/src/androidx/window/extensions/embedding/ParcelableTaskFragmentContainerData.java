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

package androidx.window.extensions.embedding;

import android.app.Activity;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * This class holds the Parcelable data of a {@link TaskFragmentContainer}.
 */
class ParcelableTaskFragmentContainerData implements Parcelable {

    /**
     * Client-created token that uniquely identifies the task fragment container instance.
     */
    @NonNull
    final IBinder mToken;

    /**
     * The tag specified in launch options. {@code null} if this taskFragment container is not an
     * overlay container.
     */
    @Nullable
    final String mOverlayTag;

    /**
     * The associated {@link Activity#getActivityToken()} of the overlay container.
     * Must be {@code null} for non-overlay container.
     * <p>
     * If an overlay container is associated with an activity, this overlay container will be
     * dismissed when the associated activity is destroyed. If the overlay container is visible,
     * activity will be launched on top of the overlay container and expanded to fill the parent
     * container.
     */
    @Nullable
    final IBinder mAssociatedActivityToken;

    /**
     * Bounds that were requested last via {@link android.window.WindowContainerTransaction}.
     */
    @NonNull
    final Rect mLastRequestedBounds;

    ParcelableTaskFragmentContainerData(@NonNull IBinder token, @Nullable String overlayTag,
            @Nullable IBinder associatedActivityToken) {
        mToken = token;
        mOverlayTag = overlayTag;
        mAssociatedActivityToken = associatedActivityToken;
        mLastRequestedBounds = new Rect();
    }

    private ParcelableTaskFragmentContainerData(Parcel in) {
        mToken = in.readStrongBinder();
        mOverlayTag = in.readString();
        mAssociatedActivityToken = in.readStrongBinder();
        mLastRequestedBounds = in.readTypedObject(Rect.CREATOR);
    }

    public static final Creator<ParcelableTaskFragmentContainerData> CREATOR = new Creator<>() {
        @Override
        public ParcelableTaskFragmentContainerData createFromParcel(Parcel in) {
            return new ParcelableTaskFragmentContainerData(in);
        }

        @Override
        public ParcelableTaskFragmentContainerData[] newArray(int size) {
            return new ParcelableTaskFragmentContainerData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeString(mOverlayTag);
        dest.writeStrongBinder(mAssociatedActivityToken);
        dest.writeTypedObject(mLastRequestedBounds, flags);
    }

}

