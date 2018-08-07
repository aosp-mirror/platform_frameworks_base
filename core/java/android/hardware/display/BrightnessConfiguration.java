/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.display;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/** @hide */
@SystemApi
@TestApi
public final class BrightnessConfiguration implements Parcelable {
    private final float[] mLux;
    private final float[] mNits;
    private final String mDescription;

    private BrightnessConfiguration(float[] lux, float[] nits, String description) {
        mLux = lux;
        mNits = nits;
        mDescription = description;
    }

    /**
     * Gets the base brightness as curve.
     *
     * The curve is returned as a pair of float arrays, the first representing all of the lux
     * points of the brightness curve and the second representing all of the nits values of the
     * brightness curve.
     *
     * @return the control points for the brightness curve.
     */
    public Pair<float[], float[]> getCurve() {
        return Pair.create(Arrays.copyOf(mLux, mLux.length), Arrays.copyOf(mNits, mNits.length));
    }

    /**
     * Returns description string.
     * @hide
     */
    public String getDescription() {
        return mDescription;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloatArray(mLux);
        dest.writeFloatArray(mNits);
        dest.writeString(mDescription);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BrightnessConfiguration{[");
        final int size = mLux.length;
        for (int i = 0; i < size; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("(").append(mLux[i]).append(", ").append(mNits[i]).append(")");
        }
        sb.append("], '");
        if (mDescription != null) {
            sb.append(mDescription);
        }
        sb.append("'}");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = result * 31 + Arrays.hashCode(mLux);
        result = result * 31 + Arrays.hashCode(mNits);
        if (mDescription != null) {
            result = result * 31 + mDescription.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof BrightnessConfiguration)) {
            return false;
        }
        final BrightnessConfiguration other = (BrightnessConfiguration) o;
        return Arrays.equals(mLux, other.mLux) && Arrays.equals(mNits, other.mNits)
                && Objects.equals(mDescription, other.mDescription);
    }

    public static final Creator<BrightnessConfiguration> CREATOR =
            new Creator<BrightnessConfiguration>() {
        public BrightnessConfiguration createFromParcel(Parcel in) {
            float[] lux = in.createFloatArray();
            float[] nits = in.createFloatArray();
            Builder builder = new Builder(lux, nits);
            builder.setDescription(in.readString());
            return builder.build();
        }

        public BrightnessConfiguration[] newArray(int size) {
            return new BrightnessConfiguration[size];
        }
    };

    /**
     * A builder class for {@link BrightnessConfiguration}s.
     */
    public static class Builder {
        private float[] mCurveLux;
        private float[] mCurveNits;
        private String mDescription;

        /**
         * STOPSHIP remove when app has stopped using this.
         * @hide
         */
        public Builder() {
        }

        /**
         * Constructs the builder with the control points for the brightness curve.
         *
         * Brightness curves must have strictly increasing ambient brightness values in lux and
         * monotonically increasing display brightness values in nits. In addition, the initial
         * control point must be 0 lux.
         *
         * @throws IllegalArgumentException if the initial control point is not at 0 lux.
         * @throws IllegalArgumentException if the lux levels are not strictly increasing.
         * @throws IllegalArgumentException if the nit levels are not monotonically increasing.
         */
        public Builder(float[] lux, float[] nits) {
            setCurve(lux, nits);
        }

        /**
         * Sets the control points for the brightness curve.
         *
         * Brightness curves must have strictly increasing ambient brightness values in lux and
         * monotonically increasing display brightness values in nits. In addition, the initial
         * control point must be 0 lux.
         *
         * @throws IllegalArgumentException if the initial control point is not at 0 lux.
         * @throws IllegalArgumentException if the lux levels are not strictly increasing.
         * @throws IllegalArgumentException if the nit levels are not monotonically increasing.
         *
         * STOPSHIP remove when app has stopped using this.
         * @hide
         */
        public Builder setCurve(float[] lux, float[] nits) {
            Preconditions.checkNotNull(lux);
            Preconditions.checkNotNull(nits);
            if (lux.length == 0 || nits.length == 0) {
                throw new IllegalArgumentException("Lux and nits arrays must not be empty");
            }
            if (lux.length != nits.length) {
                throw new IllegalArgumentException("Lux and nits arrays must be the same length");
            }
            if (lux[0] != 0) {
                throw new IllegalArgumentException("Initial control point must be for 0 lux");
            }
            Preconditions.checkArrayElementsInRange(lux, 0, Float.MAX_VALUE, "lux");
            Preconditions.checkArrayElementsInRange(nits, 0, Float.MAX_VALUE, "nits");
            checkMonotonic(lux, true/*strictly increasing*/, "lux");
            checkMonotonic(nits, false /*strictly increasing*/, "nits");
            mCurveLux = lux;
            mCurveNits = nits;
            return this;
        }

        /**
         * Set description of the brightness curve.
         *
         * @param description brief text describing the curve pushed. It maybe truncated
         *                    and will not be displayed in the UI
         */
        public Builder setDescription(@Nullable String description) {
            mDescription = description;
            return this;
        }

        /**
         * Builds the {@link BrightnessConfiguration}.
         *
         * A brightness curve <b>must</b> be set before calling this.
         */
        public BrightnessConfiguration build() {
            if (mCurveLux == null || mCurveNits == null) {
                throw new IllegalStateException("A curve must be set!");
            }
            return new BrightnessConfiguration(mCurveLux, mCurveNits, mDescription);
        }

        private static void checkMonotonic(float[] vals, boolean strictlyIncreasing, String name) {
            if (vals.length <= 1) {
                return;
            }
            float prev = vals[0];
            for (int i = 1; i < vals.length; i++) {
                if (prev > vals[i] || prev == vals[i] && strictlyIncreasing) {
                    String condition = strictlyIncreasing ? "strictly increasing" : "monotonic";
                    throw new IllegalArgumentException(name + " values must be " + condition);
                }
                prev = vals[i];
            }
        }
    }
}
