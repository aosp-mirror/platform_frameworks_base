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

package android.hardware.face;

import android.annotation.NonNull;
import android.hardware.biometrics.BiometricFaceConstants;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A container for data common to {@link FaceAuthenticationFrame} and {@link FaceEnrollFrame}.
 *
 * @hide
 */
public final class FaceDataFrame implements Parcelable {
    @BiometricFaceConstants.FaceAcquired private final int mAcquiredInfo;
    private final int mVendorCode;
    private final float mPan;
    private final float mTilt;
    private final float mDistance;
    private final boolean mIsCancellable;

    /**
     * A container for data common to {@link FaceAuthenticationFrame} and {@link FaceEnrollFrame}.
     *
     * @param acquiredInfo An integer corresponding to a known acquired message.
     * @param vendorCode An integer representing a custom vendor-specific message. Ignored unless
     *  {@code acquiredInfo} is {@code FACE_ACQUIRED_VENDOR}.
     * @param pan The horizontal pan of the detected face. Values in the range [-1, 1] indicate a
     *  good capture.
     * @param tilt The vertical tilt of the detected face. Values in the range [-1, 1] indicate a
     *  good capture.
     * @param distance The distance of the detected face from the device. Values in the range
     *  [-1, 1] indicate a good capture.
     * @param isCancellable Whether the ongoing face operation should be canceled.
     */
    public FaceDataFrame(
            @BiometricFaceConstants.FaceAcquired int acquiredInfo,
            int vendorCode,
            float pan,
            float tilt,
            float distance,
            boolean isCancellable) {
        mAcquiredInfo = acquiredInfo;
        mVendorCode = vendorCode;
        mPan = pan;
        mTilt = tilt;
        mDistance = distance;
        mIsCancellable = isCancellable;
    }

    /**
     * A container for data common to {@link FaceAuthenticationFrame} and {@link FaceEnrollFrame}.
     *
     * @param acquiredInfo An integer corresponding to a known acquired message.
     * @param vendorCode An integer representing a custom vendor-specific message. Ignored unless
     *  {@code acquiredInfo} is {@code FACE_ACQUIRED_VENDOR}.
     */
    public FaceDataFrame(@BiometricFaceConstants.FaceAcquired int acquiredInfo, int vendorCode) {
        mAcquiredInfo = acquiredInfo;
        mVendorCode = vendorCode;
        mPan = 0f;
        mTilt = 0f;
        mDistance = 0f;
        mIsCancellable = false;
    }

    /**
     * @return An integer corresponding to a known acquired message.
     *
     * @see android.hardware.biometrics.BiometricFaceConstants
     */
    @BiometricFaceConstants.FaceAcquired
    public int getAcquiredInfo() {
        return mAcquiredInfo;
    }

    /**
     * @return An integer representing a custom vendor-specific message. Ignored unless
     * {@code acquiredInfo} is {@link
     * android.hardware.biometrics.BiometricFaceConstants#FACE_ACQUIRED_VENDOR}.
     *
     * @see android.hardware.biometrics.BiometricFaceConstants
     */
    public int getVendorCode() {
        return mVendorCode;
    }

    /**
     * @return The horizontal pan of the detected face. Values in the range [-1, 1] indicate a good
     * capture.
     */
    public float getPan() {
        return mPan;
    }

    /**
     * @return The vertical tilt of the detected face. Values in the range [-1, 1] indicate a good
     * capture.
     */
    public float getTilt() {
        return mTilt;
    }

    /**
     * @return The distance of the detected face from the device. Values in the range [-1, 1]
     * indicate a good capture.
     */
    public float getDistance() {
        return mDistance;
    }

    /**
     * @return Whether the ongoing face operation should be canceled.
     */
    public boolean isCancellable() {
        return mIsCancellable;
    }

    private FaceDataFrame(@NonNull Parcel source) {
        mAcquiredInfo = source.readInt();
        mVendorCode = source.readInt();
        mPan = source.readFloat();
        mTilt = source.readFloat();
        mDistance = source.readFloat();
        mIsCancellable = source.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAcquiredInfo);
        dest.writeInt(mVendorCode);
        dest.writeFloat(mPan);
        dest.writeFloat(mTilt);
        dest.writeFloat(mDistance);
        dest.writeBoolean(mIsCancellable);
    }

    public static final Creator<FaceDataFrame> CREATOR = new Creator<FaceDataFrame>() {
        @Override
        public FaceDataFrame createFromParcel(Parcel source) {
            return new FaceDataFrame(source);
        }

        @Override
        public FaceDataFrame[] newArray(int size) {
            return new FaceDataFrame[size];
        }
    };
}
