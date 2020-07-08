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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.MathUtils;

/**
 * Parcelable containing the other Parcelable object.
 * @hide
 */
public final class ParcelableHolder implements Parcelable {
    /**
     * This is set by {@link #setParcelable}.
     * {@link #mParcelable} and {@link #mParcel} are mutually exclusive
     * if {@link ParcelableHolder} contains value, otherwise, both are null.
     */
    private Parcelable mParcelable;
    /**
     * This is set by {@link #readFromParcel}.
     * {@link #mParcelable} and {@link #mParcel} are mutually exclusive
     * if {@link ParcelableHolder} contains value, otherwise, both are null.
     */
    private Parcel mParcel;
    private boolean mIsStable = false;

    public ParcelableHolder(boolean isStable) {
        mIsStable = isStable;
    }

    private ParcelableHolder() {

    }

    /**
     * {@link ParcelableHolder}'s stability is determined by the parcelable
     * which contains this ParcelableHolder.
     * For more detail refer to {@link Parcelable#isStable}.
     */
    @Override
    public boolean isStable() {
        return mIsStable;
    }

    @NonNull
    public static final Parcelable.Creator<ParcelableHolder> CREATOR =
            new Parcelable.Creator<ParcelableHolder>() {
                @NonNull
                @Override
                public ParcelableHolder createFromParcel(@NonNull Parcel parcel) {
                    ParcelableHolder parcelable = new ParcelableHolder();
                    parcelable.readFromParcel(parcel);
                    return parcelable;
                }

                @NonNull
                @Override
                public ParcelableHolder[] newArray(int size) {
                    return new ParcelableHolder[size];
                }
            };


    /**
     * Write a parcelable into ParcelableHolder, the previous parcelable will be removed.
     * @return {@code false} if the parcelable's stability is more unstable ParcelableHolder.
     */
    public synchronized boolean setParcelable(@Nullable Parcelable p) {
        if (p != null && this.isStable() && !p.isStable()) {
            return false;
        }
        mParcelable = p;
        if (mParcel != null) {
            mParcel.recycle();
            mParcel = null;
        }
        return true;
    }

    /**
     * @return the parcelable that was written by {@link #setParcelable} or {@link #readFromParcel},
     *         or {@code null} if the parcelable has not been written, or T is different from
     *         the type written by (@link #setParcelable}.
     */
    @Nullable
    public synchronized <T extends Parcelable> T getParcelable(@NonNull Class<T> clazz) {
        if (mParcel == null) {
            if (!clazz.isInstance(mParcelable)) {
                return null;
            }
            return (T) mParcelable;
        }

        mParcel.setDataPosition(0);

        T parcelable = mParcel.readParcelable(clazz.getClassLoader());
        if (!clazz.isInstance(parcelable)) {
            return null;
        }
        mParcelable = parcelable;

        mParcel.recycle();
        mParcel = null;
        return parcelable;
    }

    /**
     * Read ParcelableHolder from a parcel.
     */
    public synchronized void readFromParcel(@NonNull Parcel parcel) {
        this.mIsStable = parcel.readBoolean();

        mParcelable = null;

        if (mParcel == null) {
            mParcel = Parcel.obtain();
        }
        mParcel.setDataPosition(0);
        mParcel.setDataSize(0);

        int dataSize = parcel.readInt();
        if (dataSize < 0) {
            throw new IllegalArgumentException("dataSize from parcel is negative");
        }
        int dataStartPos = parcel.dataPosition();

        mParcel.appendFrom(parcel, dataStartPos, dataSize);
        parcel.setDataPosition(MathUtils.addOrThrow(dataStartPos, dataSize));
    }

    @Override
    public synchronized void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(this.mIsStable);

        if (mParcel != null) {
            parcel.writeInt(mParcel.dataSize());
            parcel.appendFrom(mParcel, 0, mParcel.dataSize());
            return;
        }

        int sizePos = parcel.dataPosition();
        parcel.writeInt(0);
        int dataStartPos = parcel.dataPosition();
        parcel.writeParcelable(mParcelable, 0);
        int dataSize = parcel.dataPosition() - dataStartPos;

        parcel.setDataPosition(sizePos);
        parcel.writeInt(dataSize);
        parcel.setDataPosition(MathUtils.addOrThrow(parcel.dataPosition(), dataSize));
    }

    @Override
    public synchronized int describeContents() {
        if (mParcel != null) {
            return mParcel.hasFileDescriptors() ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
        }
        if (mParcelable != null) {
            return mParcelable.describeContents();
        }
        return 0;
    }
}
