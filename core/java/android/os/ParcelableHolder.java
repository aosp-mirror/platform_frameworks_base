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
import android.annotation.SystemApi;
import android.util.MathUtils;

/**
 * ParcelableHolder is a Parcelable which can contain another Parcelable.
 * The main use case of ParcelableHolder is to make a Parcelable extensible.
 * For example, an AOSP-defined Parcelable <code>AospDefinedParcelable</code>
 * is expected to be extended by device implementers for their value-add features.
 * Previously without ParcelableHolder, the device implementers had to
 * directly modify the Parcelable to add more fields:
 * <pre> {@code
 * parcelable AospDefinedParcelable {
 *   int a;
 *   String b;
 *   String x; // added by a device implementer
 *   int[] y; // added by a device implementer
 * }}</pre>
 *
 * This practice is very error-prone because the fields added by the device implementer
 * might have a conflict when the Parcelable is revisioned in the next releases of Android.
 *
 * Using ParcelableHolder, one can define an extension point in a Parcelable.
 * <pre> {@code
 * parcelable AospDefinedParcelable {
 *   int a;
 *   String b;
 *   ParcelableHolder extension;
 * }}</pre>
 * Then the device implementers can define their own Parcelable for their extension.
 *
 * <pre> {@code
 * parcelable OemDefinedParcelable {
 *   String x;
 *   int[] y;
 * }}</pre>
 * Finally, the new Parcelable can be attached to the original Parcelable via
 * the ParcelableHolder field.
 *
 * <pre> {@code
 * AospDefinedParcelable ap = ...;
 * OemDefinedParcelable op = new OemDefinedParcelable();
 * op.x = ...;
 * op.y = ...;
 * ap.extension.setParcelable(op);}</pre>
 *
 * <p class="note">ParcelableHolder is <strong>not</strong> thread-safe.</p>
 *
 * @hide
 */
@SystemApi
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
    private @Parcelable.Stability int mStability = Parcelable.PARCELABLE_STABILITY_LOCAL;

    public ParcelableHolder(@Parcelable.Stability int stability) {
        mStability = stability;
    }

    private ParcelableHolder() {

    }

    /**
     * {@link ParcelableHolder}'s stability is determined by the parcelable
     * which contains this ParcelableHolder.
     * For more detail refer to {@link Parcelable#getStability}.
     */
    @Override
    public @Parcelable.Stability int getStability() {
        return mStability;
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
     * (@link #setParcelable} and (@link #getParcelable} are not thread-safe.
     * @throws BadParcelableException if the parcelable's stability is more unstable
     *         ParcelableHolder.
     */
    public void setParcelable(@Nullable Parcelable p) {
        // A ParcelableHolder can only hold things at its stability or higher.
        if (p != null && this.getStability() > p.getStability()) {
            throw new BadParcelableException(
                "A ParcelableHolder can only hold things at its stability or higher. "
                + "The ParcelableHolder's stability is " + this.getStability()
                + ", but the parcelable's stability is " + p.getStability());
        }
        mParcelable = p;
        if (mParcel != null) {
            mParcel.recycle();
            mParcel = null;
        }
    }

    /**
     * Read a parcelable from ParcelableHolder.
     * (@link #setParcelable} and (@link #getParcelable} are not thread-safe.
     * @return the parcelable that was written by {@link #setParcelable} or {@link #readFromParcel},
     *         or {@code null} if the parcelable has not been written.
     * @throws BadParcelableException if T is different from the type written by
     *         (@link #setParcelable}.
     */
    @Nullable
    public <T extends Parcelable> T getParcelable(@NonNull Class<T> clazz) {
        if (mParcel == null) {
            if (mParcelable != null && !clazz.isInstance(mParcelable)) {
                throw new BadParcelableException(
                    "The ParcelableHolder has " + mParcelable.getClass().getName()
                    + ", but the requested type is " + clazz.getName());
            }
            return (T) mParcelable;
        }

        mParcel.setDataPosition(0);

        T parcelable = mParcel.readParcelable(clazz.getClassLoader());
        if (parcelable != null && !clazz.isInstance(parcelable)) {
            throw new BadParcelableException(
                    "The ParcelableHolder has " + parcelable.getClass().getName()
                    + ", but the requested type is " + clazz.getName());
        }
        mParcelable = parcelable;

        mParcel.recycle();
        mParcel = null;
        return parcelable;
    }

    /**
     * Read ParcelableHolder from a parcel.
     */
    public void readFromParcel(@NonNull Parcel parcel) {
        int wireStability = parcel.readInt();
        if (this.mStability != wireStability) {
            throw new IllegalArgumentException("Expected stability " + this.mStability
                                               + " but got " + wireStability);
        }

        mParcelable = null;

        int dataSize = parcel.readInt();
        if (dataSize < 0) {
            throw new IllegalArgumentException("dataSize from parcel is negative");
        } else if (dataSize == 0) {
            if (mParcel != null) {
                mParcel.recycle();
                mParcel = null;
            }
            return;
        }
        if (mParcel == null) {
            mParcel = Parcel.obtain();
        }
        mParcel.setDataPosition(0);
        mParcel.setDataSize(0);
        int dataStartPos = parcel.dataPosition();

        mParcel.appendFrom(parcel, dataStartPos, dataSize);
        parcel.setDataPosition(MathUtils.addOrThrow(dataStartPos, dataSize));
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(this.mStability);

        if (mParcel != null) {
            parcel.writeInt(mParcel.dataSize());
            parcel.appendFrom(mParcel, 0, mParcel.dataSize());
            return;
        }

        if (mParcelable == null) {
            parcel.writeInt(0);
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
    public int describeContents() {
        if (mParcel != null) {
            return mParcel.hasFileDescriptors() ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
        }
        if (mParcelable != null) {
            return mParcelable.describeContents();
        }
        return 0;
    }
}
