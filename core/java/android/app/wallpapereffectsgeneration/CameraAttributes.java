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

package android.app.wallpapereffectsgeneration;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Representing the position and other parameters of camera of a single frame.
 *
 * @hide
 */
@SystemApi
public final class CameraAttributes implements Parcelable {
    /**
     * The location of the anchor within the 3D scene.
     * Expecting 3 floats representing the x, y, z coordinates
     * of the anchor point.
     */
    @NonNull
    private float[] mAnchorPointInWorldSpace;
    /**
     * Where the anchor point should project to in the output image.
     * Expecting 2 floats representing the u,v coordinates of the
     * anchor point.
     */
    @NonNull
    private float[] mAnchorPointInOutputUvSpace;
    /**
     * Specifies the amount of yaw orbit rotation the camera should perform
     * around the anchor point in world space.
     */
    private float mCameraOrbitYawDegrees;
    /**
     * Specifies the amount of pitch orbit rotation the camera should perform
     * around the anchor point in world space.
     */
    private float mCameraOrbitPitchDegrees;
    /**
     * Specifies by how much the camera should be placed towards the anchor
     * point in world space, which is also called dolly distance.
     */
    private float mDollyDistanceInWorldSpace;
    /**
     * Specifies the vertical fov degrees of the virtual image.
     */
    private float mVerticalFovDegrees;
    /**
     * The frustum of near plane.
     */
    private float mFrustumNearInWorldSpace;
    /**
     * The frustum of far plane.
     */
    private float mFrustumFarInWorldSpace;

    private CameraAttributes(Parcel in) {
        this.mCameraOrbitYawDegrees = in.readFloat();
        this.mCameraOrbitPitchDegrees = in.readFloat();
        this.mDollyDistanceInWorldSpace = in.readFloat();
        this.mVerticalFovDegrees = in.readFloat();
        this.mFrustumNearInWorldSpace = in.readFloat();
        this.mFrustumFarInWorldSpace = in.readFloat();
        this.mAnchorPointInWorldSpace = in.createFloatArray();
        this.mAnchorPointInOutputUvSpace = in.createFloatArray();
    }

    private CameraAttributes(float[] anchorPointInWorldSpace, float[] anchorPointInOutputUvSpace,
            float cameraOrbitYawDegrees, float cameraOrbitPitchDegrees,
            float dollyDistanceInWorldSpace,
            float verticalFovDegrees, float frustumNearInWorldSpace, float frustumFarInWorldSpace) {
        mAnchorPointInWorldSpace = anchorPointInWorldSpace;
        mAnchorPointInOutputUvSpace = anchorPointInOutputUvSpace;
        mCameraOrbitYawDegrees = cameraOrbitYawDegrees;
        mCameraOrbitPitchDegrees = cameraOrbitPitchDegrees;
        mDollyDistanceInWorldSpace = dollyDistanceInWorldSpace;
        mVerticalFovDegrees = verticalFovDegrees;
        mFrustumNearInWorldSpace = frustumNearInWorldSpace;
        mFrustumFarInWorldSpace = frustumFarInWorldSpace;
    }

    /**
     * Get the location of the anchor within the 3D scene. The response float array contains
     * 3 floats representing the x, y, z coordinates
     */
    @NonNull
    public float[] getAnchorPointInWorldSpace() {
        return mAnchorPointInWorldSpace;
    }

    /**
     * Get where the anchor point should project to in the output image. The response float
     * array contains 2 floats representing the u,v coordinates of the anchor point.
     */
    @NonNull
    public float[] getAnchorPointInOutputUvSpace() {
        return mAnchorPointInOutputUvSpace;
    }

    /**
     * Get the camera yaw orbit rotation.
     */
    @FloatRange(from = -180.0f, to = 180.0f)
    public float getCameraOrbitYawDegrees() {
        return mCameraOrbitYawDegrees;
    }

    /**
     * Get the camera pitch orbit rotation.
     */
    @FloatRange(from = -90.0f, to = 90.0f)
    public float getCameraOrbitPitchDegrees() {
        return mCameraOrbitPitchDegrees;
    }

    /**
     * Get how many units the camera should be placed towards the anchor point in world space.
     */
    public float getDollyDistanceInWorldSpace() {
        return mDollyDistanceInWorldSpace;
    }

    /**
     * Get the camera vertical fov degrees.
     */
    @FloatRange(from = 0.0f, to = 180.0f, fromInclusive = false)
    public float getVerticalFovDegrees() {
        return mVerticalFovDegrees;
    }

    /**
     * Get the frustum in near plane.
     */
    @FloatRange(from = 0.0f)
    public float getFrustumNearInWorldSpace() {
        return mFrustumNearInWorldSpace;
    }

    /**
     * Get the frustum in far plane.
     */
    @FloatRange(from = 0.0f)
    public float getFrustumFarInWorldSpace() {
        return mFrustumFarInWorldSpace;
    }

    @NonNull
    public static final Creator<CameraAttributes> CREATOR = new Creator<CameraAttributes>() {
        @Override
        public CameraAttributes createFromParcel(Parcel in) {
            return new CameraAttributes(in);
        }

        @Override
        public CameraAttributes[] newArray(int size) {
            return new CameraAttributes[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeFloat(mCameraOrbitYawDegrees);
        out.writeFloat(mCameraOrbitPitchDegrees);
        out.writeFloat(mDollyDistanceInWorldSpace);
        out.writeFloat(mVerticalFovDegrees);
        out.writeFloat(mFrustumNearInWorldSpace);
        out.writeFloat(mFrustumFarInWorldSpace);
        out.writeFloatArray(mAnchorPointInWorldSpace);
        out.writeFloatArray(mAnchorPointInOutputUvSpace);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder for {@link CameraAttributes}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        @NonNull
        private float[] mAnchorPointInWorldSpace;
        @NonNull
        private float[] mAnchorPointInOutputUvSpace;
        private float mCameraOrbitYawDegrees;
        private float mCameraOrbitPitchDegrees;
        private float mDollyDistanceInWorldSpace;
        private float mVerticalFovDegrees;
        private float mFrustumNearInWorldSpace;
        private float mFrustumFarInWorldSpace;

        /**
         * Constructor with anchor point in world space and anchor point in output image
         * space.
         *
         * @param anchorPointInWorldSpace the location of the anchor within the 3D scene. The
         *  float array contains 3 floats representing the x, y, z coordinates.
         * @param anchorPointInOutputUvSpace where the anchor point should project to in the
         *  output image. The  float array contains 2 floats representing the u,v coordinates
         *  of the anchor point.
         *
         * @hide
         */
        @SystemApi
        public Builder(@NonNull @Size(3) float[] anchorPointInWorldSpace,
                @NonNull @Size(2) float[] anchorPointInOutputUvSpace) {
            mAnchorPointInWorldSpace = anchorPointInWorldSpace;
            mAnchorPointInOutputUvSpace = anchorPointInOutputUvSpace;
        }

        /**
         * Sets the camera orbit yaw rotation.
         */
        @NonNull
        public Builder setCameraOrbitYawDegrees(
                @FloatRange(from = -180.0f, to = 180.0f) float cameraOrbitYawDegrees) {
            mCameraOrbitYawDegrees = cameraOrbitYawDegrees;
            return this;
        }

        /**
         * Sets the camera orbit pitch rotation.
         */
        @NonNull
        public Builder setCameraOrbitPitchDegrees(
                @FloatRange(from = -90.0f, to = 90.0f) float cameraOrbitPitchDegrees) {
            mCameraOrbitPitchDegrees = cameraOrbitPitchDegrees;
            return this;
        }

        /**
         * Sets the camera dolly distance.
         */
        @NonNull
        public Builder setDollyDistanceInWorldSpace(float dollyDistanceInWorldSpace) {
            mDollyDistanceInWorldSpace = dollyDistanceInWorldSpace;
            return this;
        }

        /**
         * Sets the camera vertical fov degree.
         */
        @NonNull
        public Builder setVerticalFovDegrees(
                @FloatRange(from = 0.0f, to = 180.0f, fromInclusive = false)
                        float verticalFovDegrees) {
            mVerticalFovDegrees = verticalFovDegrees;
            return this;
        }

        /**
         * Sets the frustum in near plane.
         */
        @NonNull
        public Builder setFrustumNearInWorldSpace(
                @FloatRange(from = 0.0f) float frustumNearInWorldSpace) {
            mFrustumNearInWorldSpace = frustumNearInWorldSpace;
            return this;
        }

        /**
         * Sets the frustum in far plane.
         */
        @NonNull
        public Builder setFrustumFarInWorldSpace(
                @FloatRange(from = 0.0f) float frustumFarInWorldSpace) {
            mFrustumFarInWorldSpace = frustumFarInWorldSpace;
            return this;
        }

        /**
         * Builds a new {@link CameraAttributes} instance.
         */
        @NonNull
        public CameraAttributes build() {
            return new CameraAttributes(mAnchorPointInWorldSpace,
                    mAnchorPointInOutputUvSpace,
                    mCameraOrbitYawDegrees,
                    mCameraOrbitPitchDegrees,
                    mDollyDistanceInWorldSpace,
                    mVerticalFovDegrees,
                    mFrustumNearInWorldSpace,
                    mFrustumFarInWorldSpace);
        }
    }
}
