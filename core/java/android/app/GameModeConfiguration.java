/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import androidx.annotation.FloatRange;
import androidx.annotation.IntRange;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.Preconditions;

/**
 * GameModeConfiguration is the game's platform configuration for a game mode.
 * <p>
 * Only the game modes that are enabled by OEMs will have an active configuration, whereas game
 * modes opted in by the game will not.
 *
 * @hide
 */
@Immutable
@SystemApi
public final class GameModeConfiguration implements Parcelable {
    // Default value indicating that no FPS override will be applied as game intervention, or
    // default to the current display mode's frame rate.
    public static final int FPS_OVERRIDE_NONE = 0;

    public static final @NonNull Creator<GameModeConfiguration> CREATOR = new Creator<>() {
        @Override
        public GameModeConfiguration createFromParcel(Parcel in) {
            return new GameModeConfiguration(in);
        }

        @Override
        public GameModeConfiguration[] newArray(int size) {
            return new GameModeConfiguration[size];
        }
    };

    /**
     * Builder for {@link GameModeConfiguration}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        /** Constructs a new Builder for a game mode’s configuration. */
        public Builder() {
        }

        /** Constructs a new builder by copying from an existing game mode configuration. */
        public Builder(@NonNull GameModeConfiguration configuration) {
            mFpsOverride = configuration.mFpsOverride;
            mScalingFactor = configuration.mScalingFactor;
        }

        /**
         * Sets the scaling factor used for game resolution downscaling.
         * <br>
         *
         * @param scalingFactor the desired scaling factor ranged from 0.1 to 1.0 inclusively
         * @throws IllegalArgumentException if the scaling factor is not in range of [0.1, 1.0]
         */
        @NonNull
        public GameModeConfiguration.Builder setScalingFactor(
                @FloatRange(from = 0.1, to = 1.0) float scalingFactor) {
            Preconditions.checkArgument(scalingFactor >= 0.1 && scalingFactor <= 1.0,
                    "Scaling factor should fall between 0.1 and 1.0 (inclusive)");
            mScalingFactor = scalingFactor;
            return this;
        }

        /**
         * Sets the FPS override used for game frame rate throttling.
         * <br>
         * The list of valid throttled frame rates can be queried by
         * <ol>
         * <li>Obtain display modes by calling {@link Display#getSupportedModes}
         * <li>For each mode, get valid FPS by getting the divisor of the
         * {@link Display.Mode#getRefreshRate()} that is >= 30,
         * e.g. when Display.Mode#getRefreshRate() is 120 Hz, the valid FPS
         * of this mode is 120, 60, 40, 30
         * <li>Aggregate the valid FPS of each mode to get the full list
         * </ol>
         * <br>
         *
         * @param fpsOverride the desired non-negative FPS override value, default to
         *                    {@link #FPS_OVERRIDE_NONE}.
         * @throws IllegalArgumentException if the provided value is negative
         */
        @NonNull
        public GameModeConfiguration.Builder setFpsOverride(@IntRange(from = 0) int fpsOverride) {
            Preconditions.checkArgument(fpsOverride >= 0,
                    "FPS override should be non-negative");
            mFpsOverride = fpsOverride;
            return this;
        }

        /**
         * Builds a GameModeConfiguration.
         */
        @NonNull
        public GameModeConfiguration build() {
            return new GameModeConfiguration(mScalingFactor, mFpsOverride);
        }

        ;
        private float mScalingFactor;
        private int mFpsOverride;
    }

    GameModeConfiguration(float scalingFactor, int fpsOverride) {
        this.mScalingFactor = scalingFactor;
        this.mFpsOverride = fpsOverride;
    }

    GameModeConfiguration(Parcel in) {
        this.mScalingFactor = in.readFloat();
        this.mFpsOverride = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(mScalingFactor);
        dest.writeInt(mFpsOverride);
    }

    /**
     * Gets the scaling factor used for game resolution downscaling.
     */
    public float getScalingFactor() {
        return mScalingFactor;
    }

    /**
     * Gets the FPS override used for frame rate throttling.
     */
    public int getFpsOverride() {
        return mFpsOverride;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof GameModeConfiguration)) {
            return false;
        }
        GameModeConfiguration config = (GameModeConfiguration) obj;
        return config.mFpsOverride == this.mFpsOverride
                && config.mScalingFactor == this.mScalingFactor;
    }

    @Override
    public int hashCode() {
        int result = 7;
        result = 31 * result + mFpsOverride;
        result = 31 * result + Float.floatToIntBits(mScalingFactor);
        return result;
    }

    private final float mScalingFactor;
    private final int mFpsOverride;
}
