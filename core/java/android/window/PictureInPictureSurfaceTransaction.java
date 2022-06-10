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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a set of {@link android.view.SurfaceControl.Transaction} operations used to
 * operate on the {@link android.view.SurfaceControl} for picture-in-picture.
 *
 * @hide
 */
public final class PictureInPictureSurfaceTransaction implements Parcelable {
    private static final float NOT_SET = -1f;

    public final float mAlpha;
    public final PointF mPosition;

    public final float[] mFloat9;

    // Though this can be determined by mFloat9, it's easier to set the value directly
    public final float mRotation;

    public final float mCornerRadius;

    public final float mShadowRadius;

    private final Rect mWindowCrop;

    private boolean mShouldDisableCanAffectSystemUiFlags;

    private PictureInPictureSurfaceTransaction(Parcel in) {
        mAlpha = in.readFloat();
        mPosition = in.readTypedObject(PointF.CREATOR);
        mFloat9 = new float[9];
        in.readFloatArray(mFloat9);
        mRotation = in.readFloat();
        mCornerRadius = in.readFloat();
        mShadowRadius = in.readFloat();
        mWindowCrop = in.readTypedObject(Rect.CREATOR);
        mShouldDisableCanAffectSystemUiFlags = in.readBoolean();
    }

    private PictureInPictureSurfaceTransaction(float alpha, @Nullable PointF position,
            @Nullable float[] float9, float rotation, float cornerRadius, float shadowRadius,
            @Nullable Rect windowCrop) {
        mAlpha = alpha;
        mPosition = position;
        if (float9 == null) {
            mFloat9 = new float[9];
            Matrix.IDENTITY_MATRIX.getValues(mFloat9);
            mRotation = 0;
        } else {
            mFloat9 = Arrays.copyOf(float9, 9);
            mRotation = rotation;
        }
        mCornerRadius = cornerRadius;
        mShadowRadius = shadowRadius;
        mWindowCrop = (windowCrop == null) ? null : new Rect(windowCrop);
    }

    public PictureInPictureSurfaceTransaction(PictureInPictureSurfaceTransaction other) {
        this(other.mAlpha, other.mPosition,
                other.mFloat9, other.mRotation, other.mCornerRadius, other.mShadowRadius,
                other.mWindowCrop);
        mShouldDisableCanAffectSystemUiFlags = other.mShouldDisableCanAffectSystemUiFlags;
    }

    /** @return {@link Matrix} from {@link #mFloat9} */
    public Matrix getMatrix() {
        final Matrix matrix = new Matrix();
        matrix.setValues(mFloat9);
        return matrix;
    }

    /** @return {@code true} if this transaction contains setting corner radius. */
    public boolean hasCornerRadiusSet() {
        return mCornerRadius > 0;
    }

    /** @return {@code true} if this transaction contains setting shadow radius. */
    public boolean hasShadowRadiusSet() {
        return mShadowRadius > 0;
    }

    /** Sets the internal {@link #mShouldDisableCanAffectSystemUiFlags}. */
    public void setShouldDisableCanAffectSystemUiFlags(boolean shouldDisable) {
        mShouldDisableCanAffectSystemUiFlags = shouldDisable;
    }

    /** @return {@code true} if we should disable Task#setCanAffectSystemUiFlags. */
    public boolean getShouldDisableCanAffectSystemUiFlags() {
        return mShouldDisableCanAffectSystemUiFlags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInPictureSurfaceTransaction)) return false;
        PictureInPictureSurfaceTransaction that = (PictureInPictureSurfaceTransaction) o;
        return Objects.equals(mAlpha, that.mAlpha)
                && Objects.equals(mPosition, that.mPosition)
                && Arrays.equals(mFloat9, that.mFloat9)
                && Objects.equals(mRotation, that.mRotation)
                && Objects.equals(mCornerRadius, that.mCornerRadius)
                && Objects.equals(mShadowRadius, that.mShadowRadius)
                && Objects.equals(mWindowCrop, that.mWindowCrop)
                && mShouldDisableCanAffectSystemUiFlags
                == that.mShouldDisableCanAffectSystemUiFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAlpha, mPosition, Arrays.hashCode(mFloat9),
                mRotation, mCornerRadius, mShadowRadius, mWindowCrop,
                mShouldDisableCanAffectSystemUiFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloat(mAlpha);
        out.writeTypedObject(mPosition, 0 /* flags */);
        out.writeFloatArray(mFloat9);
        out.writeFloat(mRotation);
        out.writeFloat(mCornerRadius);
        out.writeFloat(mShadowRadius);
        out.writeTypedObject(mWindowCrop, 0 /* flags */);
        out.writeBoolean(mShouldDisableCanAffectSystemUiFlags);
    }

    @Override
    public String toString() {
        final Matrix matrix = getMatrix();
        return "PictureInPictureSurfaceTransaction("
                + " alpha=" + mAlpha
                + " position=" + mPosition
                + " matrix=" + matrix.toShortString()
                + " rotation=" + mRotation
                + " cornerRadius=" + mCornerRadius
                + " shadowRadius=" + mShadowRadius
                + " crop=" + mWindowCrop
                + " shouldDisableCanAffectSystemUiFlags" + mShouldDisableCanAffectSystemUiFlags
                + ")";
    }

    /** Applies {@link PictureInPictureSurfaceTransaction} to a given leash. */
    public static void apply(@NonNull PictureInPictureSurfaceTransaction surfaceTransaction,
            @NonNull SurfaceControl surfaceControl,
            @NonNull SurfaceControl.Transaction tx) {
        final Matrix matrix = surfaceTransaction.getMatrix();
        tx.setMatrix(surfaceControl, matrix, new float[9]);
        if (surfaceTransaction.mPosition != null) {
            tx.setPosition(surfaceControl,
                    surfaceTransaction.mPosition.x, surfaceTransaction.mPosition.y);
        }
        if (surfaceTransaction.mWindowCrop != null) {
            tx.setWindowCrop(surfaceControl, surfaceTransaction.mWindowCrop);
        }
        if (surfaceTransaction.hasCornerRadiusSet()) {
            tx.setCornerRadius(surfaceControl, surfaceTransaction.mCornerRadius);
        }
        if (surfaceTransaction.hasShadowRadiusSet()) {
            tx.setShadowRadius(surfaceControl, surfaceTransaction.mShadowRadius);
        }
        if (surfaceTransaction.mAlpha != NOT_SET) {
            tx.setAlpha(surfaceControl, surfaceTransaction.mAlpha);
        }
    }

    public static final @android.annotation.NonNull Creator<PictureInPictureSurfaceTransaction>
            CREATOR =
            new Creator<PictureInPictureSurfaceTransaction>() {
                public PictureInPictureSurfaceTransaction createFromParcel(Parcel in) {
                    return new PictureInPictureSurfaceTransaction(in);
                }
                public PictureInPictureSurfaceTransaction[] newArray(int size) {
                    return new PictureInPictureSurfaceTransaction[size];
                }
            };

    public static class Builder {
        private float mAlpha = NOT_SET;
        private PointF mPosition;
        private float[] mFloat9;
        private float mRotation;
        private float mCornerRadius = NOT_SET;
        private float mShadowRadius = NOT_SET;
        private Rect mWindowCrop;

        public Builder setAlpha(float alpha) {
            mAlpha = alpha;
            return this;
        }

        public Builder setPosition(float x, float y) {
            mPosition = new PointF(x, y);
            return this;
        }

        public Builder setTransform(@NonNull float[] float9, float rotation) {
            mFloat9 = Arrays.copyOf(float9, 9);
            mRotation = rotation;
            return this;
        }

        public Builder setCornerRadius(float cornerRadius) {
            mCornerRadius = cornerRadius;
            return this;
        }

        public Builder setShadowRadius(float shadowRadius) {
            mShadowRadius = shadowRadius;
            return this;
        }

        public Builder setWindowCrop(@NonNull Rect windowCrop) {
            mWindowCrop = new Rect(windowCrop);
            return this;
        }

        public PictureInPictureSurfaceTransaction build() {
            return new PictureInPictureSurfaceTransaction(mAlpha, mPosition,
                    mFloat9, mRotation, mCornerRadius, mShadowRadius, mWindowCrop);
        }
    }
}
