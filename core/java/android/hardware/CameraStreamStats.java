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

import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * The camera stream statistics used for passing camera stream information from
 * camera service to camera service proxy.
 *
 * Include camera stream configuration, request/error counts, startup latency,
 * and latency/jitter histograms.
 *
 * @hide
 */
public class CameraStreamStats implements Parcelable {
    public static final int HISTOGRAM_TYPE_UNKNOWN = 0;
    public static final int HISTOGRAM_TYPE_CAPTURE_LATENCY = 1;

    private int mWidth;
    private int mHeight;
    private int mFormat;
    private float mMaxPreviewFps;
    private int mDataSpace;
    private long mUsage;
    private long mRequestCount;
    private long mErrorCount;
    private int mStartLatencyMs;
    private int mMaxHalBuffers;
    private int mMaxAppBuffers;
    private int mHistogramType;
    private float[] mHistogramBins;
    private long[] mHistogramCounts;
    private long mDynamicRangeProfile;
    private long mStreamUseCase;

    private static final String TAG = "CameraStreamStats";

    public CameraStreamStats() {
        mWidth = 0;
        mHeight = 0;
        mFormat = 0;
        mMaxPreviewFps = 0;
        mDataSpace = 0;
        mUsage = 0;
        mRequestCount = 0;
        mErrorCount = 0;
        mStartLatencyMs = 0;
        mMaxHalBuffers = 0;
        mMaxAppBuffers = 0;
        mHistogramType = HISTOGRAM_TYPE_UNKNOWN;
        mDynamicRangeProfile = DynamicRangeProfiles.STANDARD;
        mStreamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT;
    }

    public CameraStreamStats(int width, int height, int format, float maxPreviewFps,
            int dataSpace, long usage, long requestCount, long errorCount,
            int startLatencyMs, int maxHalBuffers, int maxAppBuffers, long dynamicRangeProfile,
            long streamUseCase) {
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mMaxPreviewFps = maxPreviewFps;
        mDataSpace = dataSpace;
        mUsage = usage;
        mRequestCount = requestCount;
        mErrorCount = errorCount;
        mStartLatencyMs = startLatencyMs;
        mMaxHalBuffers = maxHalBuffers;
        mMaxAppBuffers = maxAppBuffers;
        mHistogramType = HISTOGRAM_TYPE_UNKNOWN;
        mDynamicRangeProfile = dynamicRangeProfile;
        mStreamUseCase = streamUseCase;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CameraStreamStats> CREATOR =
            new Parcelable.Creator<CameraStreamStats>() {
        @Override
        public CameraStreamStats createFromParcel(Parcel in) {
            try {
                CameraStreamStats streamStats = new CameraStreamStats(in);
                return streamStats;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating CameraStreamStats from parcel", e);
                return null;
            }
        }

        @Override
        public CameraStreamStats[] newArray(int size) {
            return new CameraStreamStats[size];
        }
    };

    private CameraStreamStats(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeInt(mFormat);
        dest.writeFloat(mMaxPreviewFps);
        dest.writeInt(mDataSpace);
        dest.writeLong(mUsage);
        dest.writeLong(mRequestCount);
        dest.writeLong(mErrorCount);
        dest.writeInt(mStartLatencyMs);
        dest.writeInt(mMaxHalBuffers);
        dest.writeInt(mMaxAppBuffers);
        dest.writeInt(mHistogramType);
        dest.writeFloatArray(mHistogramBins);
        dest.writeLongArray(mHistogramCounts);
        dest.writeLong(mDynamicRangeProfile);
        dest.writeLong(mStreamUseCase);
    }

    public void readFromParcel(Parcel in) {
        mWidth = in.readInt();
        mHeight = in.readInt();
        mFormat = in.readInt();
        mMaxPreviewFps = in.readFloat();
        mDataSpace = in.readInt();
        mUsage = in.readLong();
        mRequestCount = in.readLong();
        mErrorCount = in.readLong();
        mStartLatencyMs = in.readInt();
        mMaxHalBuffers = in.readInt();
        mMaxAppBuffers = in.readInt();
        mHistogramType = in.readInt();
        mHistogramBins = in.createFloatArray();
        mHistogramCounts = in.createLongArray();
        mDynamicRangeProfile = in.readLong();
        mStreamUseCase = in.readLong();
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getFormat() {
        return mFormat;
    }

    public float getMaxPreviewFps() {
        return mMaxPreviewFps;
    }

    public int getDataSpace() {
        return mDataSpace;
    }

    public long getUsage() {
        return mUsage;
    }

    public long getRequestCount() {
        return mRequestCount;
    }

    public long getErrorCount() {
        return mErrorCount;
    }

    public int getStartLatencyMs() {
        return mStartLatencyMs;
    }

    public int getMaxHalBuffers() {
        return mMaxHalBuffers;
    }

    public int getMaxAppBuffers() {
        return mMaxAppBuffers;
    }

    public int getHistogramType() {
        return mHistogramType;
    }

    public float[] getHistogramBins() {
        return mHistogramBins;
    }

    public long[] getHistogramCounts() {
        return mHistogramCounts;
    }

    public long getDynamicRangeProfile() {
        return mDynamicRangeProfile;
    }

    public long getStreamUseCase() {
        return mStreamUseCase;
    }
}
