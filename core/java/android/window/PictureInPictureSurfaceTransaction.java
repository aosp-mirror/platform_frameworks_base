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

    public final float mPositionX;
    public final float mPositionY;

    public final float[] mFloat9;

    // Though this can be determined by mFloat9, it's easier to set the value directly
    public final float mRotation;

    public final float mCornerRadius;

    private final Rect mWindowCrop = new Rect();

    public PictureInPictureSurfaceTransaction(Parcel in) {
        mPositionX = in.readFloat();
        mPositionY = in.readFloat();
        mFloat9 = new float[9];
        in.readFloatArray(mFloat9);
        mRotation = in.readFloat();
        mCornerRadius = in.readFloat();
        mWindowCrop.set(Objects.requireNonNull(in.readTypedObject(Rect.CREATOR)));
    }

    public PictureInPictureSurfaceTransaction(float positionX, float positionY,
            float[] float9, float rotation, float cornerRadius,
            @Nullable Rect windowCrop) {
        mPositionX = positionX;
        mPositionY = positionY;
        mFloat9 = Arrays.copyOf(float9, 9);
        mRotation = rotation;
        mCornerRadius = cornerRadius;
        if (windowCrop != null) {
            mWindowCrop.set(windowCrop);
        }
    }

    public PictureInPictureSurfaceTransaction(PictureInPictureSurfaceTransaction other) {
        this(other.mPositionX, other.mPositionY,
                other.mFloat9, other.mRotation, other.mCornerRadius, other.mWindowCrop);
    }

    /** @return {@link Matrix} from {@link #mFloat9} */
    public Matrix getMatrix() {
        final Matrix matrix = new Matrix();
        matrix.setValues(mFloat9);
        return matrix;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInPictureSurfaceTransaction)) return false;
        PictureInPictureSurfaceTransaction that = (PictureInPictureSurfaceTransaction) o;
        return Objects.equals(mPositionX, that.mPositionX)
                && Objects.equals(mPositionY, that.mPositionY)
                && Arrays.equals(mFloat9, that.mFloat9)
                && Objects.equals(mRotation, that.mRotation)
                && Objects.equals(mCornerRadius, that.mCornerRadius)
                && Objects.equals(mWindowCrop, that.mWindowCrop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPositionX, mPositionY, Arrays.hashCode(mFloat9),
                mRotation, mCornerRadius, mWindowCrop);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloat(mPositionX);
        out.writeFloat(mPositionY);
        out.writeFloatArray(mFloat9);
        out.writeFloat(mRotation);
        out.writeFloat(mCornerRadius);
        out.writeTypedObject(mWindowCrop, 0 /* flags */);
    }

    @Override
    public String toString() {
        final Matrix matrix = getMatrix();
        return "PictureInPictureSurfaceTransaction("
                + " posX=" + mPositionX
                + " posY=" + mPositionY
                + " matrix=" + matrix.toShortString()
                + " rotation=" + mRotation
                + " cornerRadius=" + mCornerRadius
                + " crop=" + mWindowCrop
                + ")";
    }

    /** Applies {@link PictureInPictureSurfaceTransaction} to a given leash. */
    public static void apply(@NonNull PictureInPictureSurfaceTransaction surfaceTransaction,
            @NonNull SurfaceControl surfaceControl,
            @NonNull SurfaceControl.Transaction tx) {
        final Matrix matrix = surfaceTransaction.getMatrix();
        tx.setMatrix(surfaceControl, matrix, new float[9])
                .setPosition(surfaceControl,
                        surfaceTransaction.mPositionX, surfaceTransaction.mPositionY)
                .setWindowCrop(surfaceControl, surfaceTransaction.mWindowCrop)
                .setCornerRadius(surfaceControl, surfaceTransaction.mCornerRadius);
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
}
