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

package android.accessibilityservice;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class describes the magnification config for {@link AccessibilityService} to control the
 * magnification.
 *
 * <p>
 * When the magnification config uses {@link #DEFAULT_MODE},
 * {@link AccessibilityService} will be able to control the activated magnifier on the display.
 * If there is no magnifier activated, it controls the last-activated magnification mode.
 * If there is no magnifier activated before, it controls full-screen magnifier by default.
 * </p>
 *
 * <p>
 * When the magnification config uses {@link #FULLSCREEN_MODE}. {@link AccessibilityService} will
 * be able to control full-screen magnifier on the display.
 * </p>
 *
 * <p>
 * When the magnification config uses {@link #WINDOW_MODE}. {@link AccessibilityService} will be
 * able to control the activated window magnifier on the display.
 * </p>
 *
 * <p>
 * If the other magnification configs, scale centerX and centerY, are not set by the
 * {@link Builder}, the configs should be current values or default values. And the center
 * position ordinarily is the center of the screen.
 * </p>
 */
public final class MagnificationConfig implements Parcelable {

    /** The controlling magnification mode. It controls the activated magnifier. */
    public static final int DEFAULT_MODE = 0;
    /** The controlling magnification mode. It controls fullscreen magnifier. */
    public static final int FULLSCREEN_MODE = 1;
    /** The controlling magnification mode. It controls window magnifier. */
    public static final int WINDOW_MODE = 2;

    @IntDef(prefix = {"MAGNIFICATION_MODE"}, value = {
            DEFAULT_MODE,
            FULLSCREEN_MODE,
            WINDOW_MODE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MAGNIFICATION_MODE {
    }

    private int mMode = DEFAULT_MODE;
    private float mScale = Float.NaN;
    private float mCenterX = Float.NaN;
    private float mCenterY = Float.NaN;

    private MagnificationConfig() {
        /* do nothing */
    }

    private MagnificationConfig(@NonNull Parcel parcel) {
        mMode = parcel.readInt();
        mScale = parcel.readFloat();
        mCenterX = parcel.readFloat();
        mCenterY = parcel.readFloat();
    }

    /**
     * Returns the magnification mode that is the current activated mode or the controlling mode of
     * the config.
     *
     * @return The magnification mode
     */
    public int getMode() {
        return mMode;
    }

    /**
     * Returns the magnification scale of the controlling magnifier
     *
     * @return the scale If the controlling magnifier is not activated, it returns 1 by default
     */
    public float getScale() {
        return mScale;
    }

    /**
     * Returns the screen-relative X coordinate of the center of the magnification viewport.
     *
     * @return the X coordinate. If the controlling magnifier is {@link #WINDOW_MODE} but not
     * enabled, it returns {@link Float#NaN}. If the controlling magnifier is {@link
     * #FULLSCREEN_MODE} but not enabled, it returns 0
     */
    public float getCenterX() {
        return mCenterX;
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the magnification viewport.
     *
     * @return the Y coordinate If the controlling magnifier is {@link #WINDOW_MODE} but not
     * enabled, it returns {@link Float#NaN}. If the controlling magnifier is {@link
     * #FULLSCREEN_MODE} but not enabled, it returns 0
     */
    public float getCenterY() {
        return mCenterY;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("MagnificationConfig[");
        stringBuilder.append("mode: ").append(getMode());
        stringBuilder.append(", ");
        stringBuilder.append("scale: ").append(getScale());
        stringBuilder.append(", ");
        stringBuilder.append("centerX: ").append(getCenterX());
        stringBuilder.append(", ");
        stringBuilder.append("centerY: ").append(getCenterY());
        stringBuilder.append("] ");
        return stringBuilder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mMode);
        parcel.writeFloat(mScale);
        parcel.writeFloat(mCenterX);
        parcel.writeFloat(mCenterY);
    }

    /**
     * Builder for creating {@link MagnificationConfig} objects.
     */
    public static final class Builder {

        private int mMode = DEFAULT_MODE;
        private float mScale = Float.NaN;
        private float mCenterX = Float.NaN;
        private float mCenterY = Float.NaN;

        /**
         * Creates a new Builder.
         */
        public Builder() {
        }

        /**
         * Sets the magnification mode.
         *
         * @param mode The magnification mode
         * @return This builder
         */
        @NonNull
        public MagnificationConfig.Builder setMode(@MAGNIFICATION_MODE int mode) {
            mMode = mode;
            return this;
        }

        /**
         * Sets the magnification scale.
         *
         * @param scale The magnification scale
         * @return This builder
         */
        @NonNull
        public MagnificationConfig.Builder setScale(float scale) {
            mScale = scale;
            return this;
        }

        /**
         * Sets the X coordinate of the center of the magnification viewport.
         *
         * @param centerX the screen-relative X coordinate around which to
         *                center and scale, or {@link Float#NaN} to leave unchanged
         * @return This builder
         */
        @NonNull
        public MagnificationConfig.Builder setCenterX(float centerX) {
            mCenterX = centerX;
            return this;
        }

        /**
         * Sets the Y coordinate of the center of the magnification viewport.
         *
         * @param centerY the screen-relative Y coordinate around which to
         *                center and scale, or {@link Float#NaN} to leave unchanged
         * @return This builder
         */
        @NonNull
        public MagnificationConfig.Builder setCenterY(float centerY) {
            mCenterY = centerY;
            return this;
        }

        /**
         * Builds and returns a {@link MagnificationConfig}
         */
        @NonNull
        public MagnificationConfig build() {
            MagnificationConfig magnificationConfig = new MagnificationConfig();
            magnificationConfig.mMode = mMode;
            magnificationConfig.mScale = mScale;
            magnificationConfig.mCenterX = mCenterX;
            magnificationConfig.mCenterY = mCenterY;
            return magnificationConfig;
        }
    }

    /**
     * @see Parcelable.Creator
     */
    public static final @NonNull Parcelable.Creator<MagnificationConfig> CREATOR =
            new Parcelable.Creator<MagnificationConfig>() {
                public MagnificationConfig createFromParcel(Parcel parcel) {
                    return new MagnificationConfig(parcel);
                }

                public MagnificationConfig[] newArray(int size) {
                    return new MagnificationConfig[size];
                }
            };
}
