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

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * This class holds the Parcelable data of a {@link SplitContainer}.
 */
class ParcelableSplitContainerData implements Parcelable {

    /**
     * A reference to the target {@link SplitContainer} that owns the data. This will not be
     * parcelled and will be {@code null} when the data is created from a parcel.
     */
    @Nullable
    final SplitContainer mSplitContainer;

    @NonNull
    final IBinder mToken;

    @NonNull
    private final IBinder mPrimaryContainerToken;

    @NonNull
    private final IBinder mSecondaryContainerToken;

    // TODO(b/289875940): making this as non-null once the tag can be auto-generated from the rule.
    @Nullable
    final String mSplitRuleTag;

    /**
     * Whether the selection of which container is primary can be changed at runtime. Runtime
     * updates is currently possible only for {@link SplitPinContainer}
     *
     * @see SplitPinContainer
     */
    final boolean mIsPrimaryContainerMutable;

    ParcelableSplitContainerData(@NonNull SplitContainer splitContainer, @NonNull IBinder token,
            @NonNull IBinder primaryContainerToken, @NonNull IBinder secondaryContainerToken,
            @Nullable String splitRuleTag, boolean isPrimaryContainerMutable) {
        mSplitContainer = splitContainer;
        mToken = token;
        mPrimaryContainerToken = primaryContainerToken;
        mSecondaryContainerToken = secondaryContainerToken;
        mSplitRuleTag = splitRuleTag;
        mIsPrimaryContainerMutable = isPrimaryContainerMutable;
    }

    private ParcelableSplitContainerData(Parcel in) {
        mSplitContainer = null;
        mToken = in.readStrongBinder();
        mPrimaryContainerToken = in.readStrongBinder();
        mSecondaryContainerToken = in.readStrongBinder();
        mSplitRuleTag = in.readString();
        mIsPrimaryContainerMutable = in.readBoolean();
    }

    public static final Creator<ParcelableSplitContainerData> CREATOR = new Creator<>() {
        @Override
        public ParcelableSplitContainerData createFromParcel(Parcel in) {
            return new ParcelableSplitContainerData(in);
        }

        @Override
        public ParcelableSplitContainerData[] newArray(int size) {
            return new ParcelableSplitContainerData[size];
        }
    };

    @NonNull
    IBinder getPrimaryContainerToken() {
        return mSplitContainer != null ? mSplitContainer.getPrimaryContainer().getToken()
                : mPrimaryContainerToken;
    }

    @NonNull
    IBinder getSecondaryContainerToken() {
        return mSplitContainer != null ? mSplitContainer.getSecondaryContainer().getToken()
                : mSecondaryContainerToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeStrongBinder(getPrimaryContainerToken());
        dest.writeStrongBinder(getSecondaryContainerToken());
        dest.writeString(mSplitRuleTag);
        dest.writeBoolean(mIsPrimaryContainerMutable);
    }
}
