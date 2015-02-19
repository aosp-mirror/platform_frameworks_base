/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * @hide
 */
@SystemApi
public class TvStreamConfig implements Parcelable {
    static final String TAG = TvStreamConfig.class.getSimpleName();

    public final static int STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE = 1;
    public final static int STREAM_TYPE_BUFFER_PRODUCER = 2;

    private int mStreamId;
    private int mType;
    private int mMaxWidth;
    private int mMaxHeight;
    /**
     * Generations are incremented once framework receives STREAM_CONFIGURATION_CHANGED event from
     * HAL module. Framework should throw away outdated configurations and get new configurations
     * via tv_input_device::get_stream_configurations().
     */
    private int mGeneration;

    public static final Parcelable.Creator<TvStreamConfig> CREATOR =
            new Parcelable.Creator<TvStreamConfig>() {
        @Override
        public TvStreamConfig createFromParcel(Parcel source) {
            try {
                return new Builder().
                        streamId(source.readInt()).
                        type(source.readInt()).
                        maxWidth(source.readInt()).
                        maxHeight(source.readInt()).
                        generation(source.readInt()).build();
            } catch (Exception e) {
                Log.e(TAG, "Exception creating TvStreamConfig from parcel", e);
                return null;
            }
        }

        @Override
        public TvStreamConfig[] newArray(int size) {
            return new TvStreamConfig[size];
        }
    };

    private TvStreamConfig() {}

    public int getStreamId() {
        return mStreamId;
    }

    public int getType() {
        return mType;
    }

    public int getMaxWidth() {
        return mMaxWidth;
    }

    public int getMaxHeight() {
        return mMaxHeight;
    }

    public int getGeneration() {
        return mGeneration;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(128);
        b.append("TvStreamConfig {");
        b.append("mStreamId=").append(mStreamId).append(";");
        b.append("mType=").append(mType).append(";");
        b.append("mGeneration=").append(mGeneration).append("}");
        return b.toString();
    }

    // Parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStreamId);
        dest.writeInt(mType);
        dest.writeInt(mMaxWidth);
        dest.writeInt(mMaxHeight);
        dest.writeInt(mGeneration);
    }

    /**
     * A helper class for creating a TvStreamConfig object.
     */
    public static final class Builder {
        private Integer mStreamId;
        private Integer mType;
        private Integer mMaxWidth;
        private Integer mMaxHeight;
        private Integer mGeneration;

        public Builder() {
        }

        public Builder streamId(int streamId) {
            mStreamId = streamId;
            return this;
        }

        public Builder type(int type) {
            mType = type;
            return this;
        }

        public Builder maxWidth(int maxWidth) {
            mMaxWidth = maxWidth;
            return this;
        }

        public Builder maxHeight(int maxHeight) {
            mMaxHeight = maxHeight;
            return this;
        }

        public Builder generation(int generation) {
            mGeneration = generation;
            return this;
        }

        public TvStreamConfig build() {
            if (mStreamId == null || mType == null || mMaxWidth == null || mMaxHeight == null
                    || mGeneration == null) {
                throw new UnsupportedOperationException();
            }

            TvStreamConfig config = new TvStreamConfig();
            config.mStreamId = mStreamId;
            config.mType = mType;
            config.mMaxWidth = mMaxWidth;
            config.mMaxHeight = mMaxHeight;
            config.mGeneration = mGeneration;
            return config;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (!(obj instanceof TvStreamConfig)) return false;

        TvStreamConfig config = (TvStreamConfig) obj;
        return config.mGeneration == mGeneration
            && config.mStreamId == mStreamId
            && config.mType == mType
            && config.mMaxWidth == mMaxWidth
            && config.mMaxHeight == mMaxHeight;
    }
}
