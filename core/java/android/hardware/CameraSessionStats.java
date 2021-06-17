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
package android.hardware;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
/**
 * The camera action state used for passing camera usage information from
 * camera service to camera service proxy .
 *
 * Include camera id, facing, state, client apk name, apiLevel, isNdk,
 * and session/stream statistics.
 *
 * @hide
 */
public class CameraSessionStats implements Parcelable {
    public static final int CAMERA_STATE_OPEN = 0;
    public static final int CAMERA_STATE_ACTIVE = 1;
    public static final int CAMERA_STATE_IDLE = 2;
    public static final int CAMERA_STATE_CLOSED = 3;

    /**
     * Values for notifyCameraState facing
     */
    public static final int CAMERA_FACING_BACK = 0;
    public static final int CAMERA_FACING_FRONT = 1;
    public static final int CAMERA_FACING_EXTERNAL = 2;

    /**
     * Values for notifyCameraState api level
     */
    public static final int CAMERA_API_LEVEL_1 = 1;
    public static final int CAMERA_API_LEVEL_2 = 2;

    private String mCameraId;
    private int mFacing;
    private int mNewCameraState;
    private String mClientName;
    private int mApiLevel;
    private boolean mIsNdk;
    private int mLatencyMs;
    private int mSessionType;
    private int mInternalReconfigure;
    private long mRequestCount;
    private long mResultErrorCount;
    private boolean mDeviceError;
    private ArrayList<CameraStreamStats> mStreamStats;

    public CameraSessionStats() {
        mFacing = -1;
        mNewCameraState = -1;
        mApiLevel = -1;
        mIsNdk = false;
        mLatencyMs = -1;
        mSessionType = -1;
        mInternalReconfigure = -1;
        mRequestCount = 0;
        mResultErrorCount = 0;
        mDeviceError = false;
        mStreamStats = new ArrayList<CameraStreamStats>();
    }

    public CameraSessionStats(String cameraId, int facing, int newCameraState,
            String clientName, int apiLevel, boolean isNdk, int creationDuration,
            int sessionType, int internalReconfigure) {
        mCameraId = cameraId;
        mFacing = facing;
        mNewCameraState = newCameraState;
        mClientName = clientName;
        mApiLevel = apiLevel;
        mIsNdk = isNdk;
        mLatencyMs = creationDuration;
        mSessionType = sessionType;
        mInternalReconfigure = internalReconfigure;
        mStreamStats = new ArrayList<CameraStreamStats>();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CameraSessionStats> CREATOR =
            new Parcelable.Creator<CameraSessionStats>() {
        @Override
        public CameraSessionStats createFromParcel(Parcel in) {
            return new CameraSessionStats(in);
        }

        @Override
        public CameraSessionStats[] newArray(int size) {
            return new CameraSessionStats[size];
        }
    };

    private CameraSessionStats(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCameraId);
        dest.writeInt(mFacing);
        dest.writeInt(mNewCameraState);
        dest.writeString(mClientName);
        dest.writeInt(mApiLevel);
        dest.writeBoolean(mIsNdk);
        dest.writeInt(mLatencyMs);
        dest.writeInt(mSessionType);
        dest.writeInt(mInternalReconfigure);
        dest.writeLong(mRequestCount);
        dest.writeLong(mResultErrorCount);
        dest.writeBoolean(mDeviceError);
        dest.writeTypedList(mStreamStats);
    }

    public void readFromParcel(Parcel in) {
        mCameraId = in.readString();
        mFacing = in.readInt();
        mNewCameraState = in.readInt();
        mClientName = in.readString();
        mApiLevel = in.readInt();
        mIsNdk = in.readBoolean();
        mLatencyMs = in.readInt();
        mSessionType = in.readInt();
        mInternalReconfigure = in.readInt();
        mRequestCount = in.readLong();
        mResultErrorCount = in.readLong();
        mDeviceError = in.readBoolean();

        ArrayList<CameraStreamStats> streamStats = new ArrayList<CameraStreamStats>();
        in.readTypedList(streamStats, CameraStreamStats.CREATOR);
        mStreamStats = streamStats;
    }

    public String getCameraId() {
        return mCameraId;
    }

    public int getFacing() {
        return mFacing;
    }

    public int getNewCameraState() {
        return mNewCameraState;
    }

    public String getClientName() {
        return mClientName;
    }

    public int getApiLevel() {
        return mApiLevel;
    }

    public boolean isNdk() {
        return mIsNdk;
    }

    public int getLatencyMs() {
        return mLatencyMs;
    }

    public int getSessionType() {
        return mSessionType;
    }

    public int getInternalReconfigureCount() {
        return mInternalReconfigure;
    }

    public long getRequestCount() {
        return mRequestCount;
    }

    public long getResultErrorCount() {
        return mResultErrorCount;
    }

    public boolean getDeviceErrorFlag() {
        return mDeviceError;
    }

    public List<CameraStreamStats> getStreamStats() {
        return mStreamStats;
    }
}
