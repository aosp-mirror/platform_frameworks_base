/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.VibrationEffect;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Representation of {@link VibrationEffectSegment} that plays a primitive vibration effect after a
 * specified delay and applying a given scale.
 *
 * @hide
 */
@TestApi
public final class PrimitiveSegment extends VibrationEffectSegment {
    private final int mPrimitiveId;
    private final float mScale;
    private final int mDelay;

    PrimitiveSegment(@NonNull Parcel in) {
        this(in.readInt(), in.readFloat(), in.readInt());
    }

    /** @hide */
    public PrimitiveSegment(int id, float scale, int delay) {
        mPrimitiveId = id;
        mScale = scale;
        mDelay = delay;
    }

    public int getPrimitiveId() {
        return mPrimitiveId;
    }

    public float getScale() {
        return mScale;
    }

    public int getDelay() {
        return mDelay;
    }

    @Override
    public long getDuration() {
        return -1;
    }

    @Override
    public boolean hasNonZeroAmplitude() {
        // Every primitive plays a vibration with a non-zero amplitude, even at scale == 0.
        return true;
    }

    @NonNull
    @Override
    public PrimitiveSegment resolve(int defaultAmplitude) {
        return this;
    }

    @NonNull
    @Override
    public PrimitiveSegment scale(float scaleFactor) {
        return new PrimitiveSegment(mPrimitiveId, VibrationEffect.scale(mScale, scaleFactor),
                mDelay);
    }

    @NonNull
    @Override
    public PrimitiveSegment applyEffectStrength(int effectStrength) {
        return this;
    }

    @Override
    public void validate() {
        Preconditions.checkArgumentInRange(mPrimitiveId, VibrationEffect.Composition.PRIMITIVE_NOOP,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK, "primitiveId");
        Preconditions.checkArgumentInRange(mScale, 0f, 1f, "scale");
        Preconditions.checkArgumentNonnegative(mDelay, "primitive delay should be >= 0");
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(PARCEL_TOKEN_PRIMITIVE);
        dest.writeInt(mPrimitiveId);
        dest.writeFloat(mScale);
        dest.writeInt(mDelay);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "Primitive{"
                + "primitive=" + VibrationEffect.Composition.primitiveToString(mPrimitiveId)
                + ", scale=" + mScale
                + ", delay=" + mDelay
                + '}';
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrimitiveSegment that = (PrimitiveSegment) o;
        return mPrimitiveId == that.mPrimitiveId
                && Float.compare(that.mScale, mScale) == 0
                && mDelay == that.mDelay;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPrimitiveId, mScale, mDelay);
    }

    @NonNull
    public static final Parcelable.Creator<PrimitiveSegment> CREATOR =
            new Parcelable.Creator<PrimitiveSegment>() {
                @Override
                public PrimitiveSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PrimitiveSegment(in);
                }

                @Override
                public PrimitiveSegment[] newArray(int size) {
                    return new PrimitiveSegment[size];
                }
            };
}
