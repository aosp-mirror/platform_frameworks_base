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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A {@link Parcelable} {@link ColorSpace}. In order to enable parceling, the ColorSpace
 * must be either a {@link ColorSpace.Named Named} ColorSpace or a {@link ColorSpace.Rgb} instance
 * that has an ICC parametric transfer function as returned by {@link Rgb#getTransferParameters()}.
 * TODO: Make public
 * @hide
 */
public final class ParcelableColorSpace extends ColorSpace implements Parcelable {
    private final ColorSpace mColorSpace;

    /**
     * Checks if the given ColorSpace is able to be parceled. A ColorSpace can only be
     * parceled if it is a {@link ColorSpace.Named Named} ColorSpace or a {@link ColorSpace.Rgb}
     * instance that has an ICC parametric transfer function as returned by
     * {@link Rgb#getTransferParameters()}
     */
    public static boolean isParcelable(@NonNull ColorSpace colorSpace) {
        if (colorSpace.getId() == ColorSpace.MIN_ID) {
            if (!(colorSpace instanceof ColorSpace.Rgb)) {
                return false;
            }
            ColorSpace.Rgb rgb = (ColorSpace.Rgb) colorSpace;
            if (rgb.getTransferParameters() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Constructs a new ParcelableColorSpace that wraps the provided ColorSpace.
     *
     * @param colorSpace The ColorSpace to wrap. The ColorSpace must be either named or be an
     *                   RGB ColorSpace with an ICC parametric transfer function.
     * @throws IllegalArgumentException If the provided ColorSpace does not satisfy the requirements
     * to be parceled. See {@link #isParcelable(ColorSpace)}.
     */
    public ParcelableColorSpace(@NonNull ColorSpace colorSpace) {
        super(colorSpace.getName(), colorSpace.getModel(), colorSpace.getId());
        mColorSpace = colorSpace;

        if (mColorSpace.getId() == ColorSpace.MIN_ID) {
            if (!(mColorSpace instanceof ColorSpace.Rgb)) {
                throw new IllegalArgumentException(
                        "Unable to parcel unknown ColorSpaces that are not ColorSpace.Rgb");
            }
            ColorSpace.Rgb rgb = (ColorSpace.Rgb) mColorSpace;
            if (rgb.getTransferParameters() == null) {
                throw new IllegalArgumentException("ColorSpace must use an ICC "
                        + "parametric transfer function to be parcelable");
            }
        }
    }

    public @NonNull ColorSpace getColorSpace() {
        return mColorSpace;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        final int id = mColorSpace.getId();
        dest.writeInt(id);
        if (id == ColorSpace.MIN_ID) {
            // Not a named color space. We have to actually write, like, stuff. And things. Ugh.
            // Cast is safe because this was asserted in the constructor
            ColorSpace.Rgb rgb = (ColorSpace.Rgb) mColorSpace;
            dest.writeString(rgb.getName());
            dest.writeFloatArray(rgb.getPrimaries());
            dest.writeFloatArray(rgb.getWhitePoint());
            ColorSpace.Rgb.TransferParameters transferParameters = rgb.getTransferParameters();
            dest.writeDouble(transferParameters.a);
            dest.writeDouble(transferParameters.b);
            dest.writeDouble(transferParameters.c);
            dest.writeDouble(transferParameters.d);
            dest.writeDouble(transferParameters.e);
            dest.writeDouble(transferParameters.f);
            dest.writeDouble(transferParameters.g);
        }
    }

    @NonNull
    public static final Parcelable.Creator<ParcelableColorSpace> CREATOR =
            new Parcelable.Creator<ParcelableColorSpace>() {

        public @NonNull ParcelableColorSpace createFromParcel(@NonNull Parcel in) {
            final int id = in.readInt();
            if (id == ColorSpace.MIN_ID) {
                String name = in.readString();
                float[] primaries = in.createFloatArray();
                float[] whitePoint = in.createFloatArray();
                double a = in.readDouble();
                double b = in.readDouble();
                double c = in.readDouble();
                double d = in.readDouble();
                double e = in.readDouble();
                double f = in.readDouble();
                double g = in.readDouble();
                ColorSpace.Rgb.TransferParameters function =
                        new ColorSpace.Rgb.TransferParameters(a, b, c, d, e, f, g);
                return new ParcelableColorSpace(
                        new ColorSpace.Rgb(name, primaries, whitePoint, function));
            } else {
                return new ParcelableColorSpace(ColorSpace.get(id));
            }
        }

        public ParcelableColorSpace[] newArray(int size) {
            return new ParcelableColorSpace[size];
        }
    };

    @Override
    public boolean isWideGamut() {
        return mColorSpace.isWideGamut();
    }

    @Override
    public float getMinValue(int component) {
        return mColorSpace.getMinValue(component);
    }

    @Override
    public float getMaxValue(int component) {
        return mColorSpace.getMaxValue(component);
    }

    @Override
    public @NonNull float[] toXyz(@NonNull float[] v) {
        return mColorSpace.toXyz(v);
    }

    @Override
    public @NonNull float[] fromXyz(@NonNull float[] v) {
        return mColorSpace.fromXyz(v);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParcelableColorSpace other = (ParcelableColorSpace) o;
        return mColorSpace.equals(other.mColorSpace);
    }

    @Override
    public int hashCode() {
        return mColorSpace.hashCode();
    }

    /** @hide */
    @Override
    long getNativeInstance() {
        return mColorSpace.getNativeInstance();
    }
}
