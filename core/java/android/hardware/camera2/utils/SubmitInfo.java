/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.hardware.camera2.utils;

import android.os.Parcel;
import android.os.Parcelable;
import android.hardware.camera2.ICameraDeviceUser;

/**
 * The status information returned for a successful capture request submission.
 *
 * Includes the request ID for the newly submitted capture request, and the
 * last frame number of either the previous repeating request (for repeating
 * requests), or of the request(s) just submitted (for single-shot capture).
 *
 * @hide
 */
public class SubmitInfo implements Parcelable {

    private int mRequestId;
    private long mLastFrameNumber;

    public SubmitInfo() {
        mRequestId = -1;
        mLastFrameNumber = ICameraDeviceUser.NO_IN_FLIGHT_REPEATING_FRAMES;
    }

    public SubmitInfo(int requestId, long lastFrameNumber) {
        mRequestId = requestId;
        mLastFrameNumber = lastFrameNumber;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SubmitInfo> CREATOR =
            new Parcelable.Creator<SubmitInfo>() {
        @Override
        public SubmitInfo createFromParcel(Parcel in) {
            return new SubmitInfo(in);
        }

        @Override
        public SubmitInfo[] newArray(int size) {
            return new SubmitInfo[size];
        }
    };

    private SubmitInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRequestId);
        dest.writeLong(mLastFrameNumber);
    }

    public void readFromParcel(Parcel in) {
        mRequestId = in.readInt();
        mLastFrameNumber = in.readLong();
    }

    /**
     * Return the request ID for the submitted capture request/burst.
     *
     * This is used to track the completion status of the requested captures,
     * and to cancel repeating requests.
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Return the last frame number for the submitted capture request/burst.
     *
     * For a repeating request, this is the last frame number of the _prior_
     * repeating request, to indicate when to fire the sequence completion callback
     * for the prior repeating request.
     *
     * For a single-shot capture, this is the last frame number of _this_
     * burst, to indicate when to fire the sequence completion callback for the request itself.
     *
     * For a repeating request, may be NO_IN_FLIGHT_REPEATING_FRAMES, if no
     * instances of a prior repeating request were actually issued to the camera device.
     */
    public long getLastFrameNumber() {
        return mLastFrameNumber;
    }

}
