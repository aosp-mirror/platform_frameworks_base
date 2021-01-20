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

package android.media.metrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Playback track change event.
 * @hide
 */
public final class TrackChangeEvent implements Parcelable {
    public static final int TRACK_STATE_OFF = 0;
    public static final int TRACK_STATE_ON = 1;

    public static final int TRACK_CHANGE_REASON_UNKNOWN = 0;
    public static final int TRACK_CHANGE_REASON_OTHER = 1;
    public static final int TRACK_CHANGE_REASON_INITIAL = 2;
    public static final int TRACK_CHANGE_REASON_MANUAL = 3;
    public static final int TRACK_CHANGE_REASON_ADAPTIVE = 4;

    public static final int TRACK_TYPE_AUDIO = 0;
    public static final int TRACK_TYPE_VIDEO = 1;
    public static final int TRACK_TYPE_TEXT = 2;

    private final int mState;
    private final int mReason;
    private final @Nullable String mContainerMimeType;
    private final @Nullable String mSampleMimeType;
    private final @Nullable String mCodecName;
    private final int mBitrate;
    private final long mTimeSincePlaybackCreatedMillis;
    private final int mType;
    private final @Nullable String mLanguage;
    private final @Nullable String mLanguageRegion;
    private final int mChannelCount;
    private final int mSampleRate;
    private final int mWidth;
    private final int mHeight;



    /** @hide */
    @IntDef(prefix = "TRACK_STATE_", value = {
        TRACK_STATE_OFF,
        TRACK_STATE_ON
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackState {}

    /** @hide */
    @IntDef(prefix = "TRACK_CHANGE_REASON_", value = {
        TRACK_CHANGE_REASON_UNKNOWN,
        TRACK_CHANGE_REASON_OTHER,
        TRACK_CHANGE_REASON_INITIAL,
        TRACK_CHANGE_REASON_MANUAL,
        TRACK_CHANGE_REASON_ADAPTIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackChangeReason {}

    /** @hide */
    @IntDef(prefix = "TRACK_TYPE_", value = {
        TRACK_TYPE_AUDIO,
        TRACK_TYPE_VIDEO,
        TRACK_TYPE_TEXT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackType {}

    public TrackChangeEvent(
            int state,
            int reason,
            @Nullable String containerMimeType,
            @Nullable String sampleMimeType,
            @Nullable String codecName,
            int bitrate,
            long timeSincePlaybackCreatedMillis,
            int type,
            @Nullable String language,
            @Nullable String languageRegion,
            int channelCount,
            int sampleRate,
            int width,
            int height) {
        this.mState = state;
        this.mReason = reason;
        this.mContainerMimeType = containerMimeType;
        this.mSampleMimeType = sampleMimeType;
        this.mCodecName = codecName;
        this.mBitrate = bitrate;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
        this.mType = type;
        this.mLanguage = language;
        this.mLanguageRegion = languageRegion;
        this.mChannelCount = channelCount;
        this.mSampleRate = sampleRate;
        this.mWidth = width;
        this.mHeight = height;
    }

    @TrackState
    public int getTrackState() {
        return mState;
    }

    @TrackChangeReason
    public int getTrackChangeReason() {
        return mReason;
    }

    public @Nullable String getContainerMimeType() {
        return mContainerMimeType;
    }

    public @Nullable String getSampleMimeType() {
        return mSampleMimeType;
    }

    public @Nullable String getCodecName() {
        return mCodecName;
    }

    public int getBitrate() {
        return mBitrate;
    }

    public long getTimeSincePlaybackCreatedMillis() {
        return mTimeSincePlaybackCreatedMillis;
    }

    @TrackType
    public int getTrackType() {
        return mType;
    }

    public @Nullable String getLanguage() {
        return mLanguage;
    }

    public @Nullable String getLanguageRegion() {
        return mLanguageRegion;
    }

    public int getChannelCount() {
        return mChannelCount;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        int flg = 0;
        if (mContainerMimeType != null) flg |= 0x4;
        if (mSampleMimeType != null) flg |= 0x8;
        if (mCodecName != null) flg |= 0x10;
        if (mLanguage != null) flg |= 0x100;
        if (mLanguageRegion != null) flg |= 0x200;
        dest.writeInt(flg);
        dest.writeInt(mState);
        dest.writeInt(mReason);
        if (mContainerMimeType != null) dest.writeString(mContainerMimeType);
        if (mSampleMimeType != null) dest.writeString(mSampleMimeType);
        if (mCodecName != null) dest.writeString(mCodecName);
        dest.writeInt(mBitrate);
        dest.writeLong(mTimeSincePlaybackCreatedMillis);
        dest.writeInt(mType);
        if (mLanguage != null) dest.writeString(mLanguage);
        if (mLanguageRegion != null) dest.writeString(mLanguageRegion);
        dest.writeInt(mChannelCount);
        dest.writeInt(mSampleRate);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ TrackChangeEvent(@NonNull Parcel in) {
        int flg = in.readInt();
        int state = in.readInt();
        int reason = in.readInt();
        String containerMimeType = (flg & 0x4) == 0 ? null : in.readString();
        String sampleMimeType = (flg & 0x8) == 0 ? null : in.readString();
        String codecName = (flg & 0x10) == 0 ? null : in.readString();
        int bitrate = in.readInt();
        long timeSincePlaybackCreatedMillis = in.readLong();
        int type = in.readInt();
        String language = (flg & 0x100) == 0 ? null : in.readString();
        String languageRegion = (flg & 0x200) == 0 ? null : in.readString();
        int channelCount = in.readInt();
        int sampleRate = in.readInt();
        int width = in.readInt();
        int height = in.readInt();

        this.mState = state;
        this.mReason = reason;
        this.mContainerMimeType = containerMimeType;
        this.mSampleMimeType = sampleMimeType;
        this.mCodecName = codecName;
        this.mBitrate = bitrate;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
        this.mType = type;
        this.mLanguage = language;
        this.mLanguageRegion = languageRegion;
        this.mChannelCount = channelCount;
        this.mSampleRate = sampleRate;
        this.mWidth = width;
        this.mHeight = height;
    }

    public static final @NonNull Parcelable.Creator<TrackChangeEvent> CREATOR =
            new Parcelable.Creator<TrackChangeEvent>() {
        @Override
        public TrackChangeEvent[] newArray(int size) {
            return new TrackChangeEvent[size];
        }

        @Override
        public TrackChangeEvent createFromParcel(@NonNull Parcel in) {
            return new TrackChangeEvent(in);
        }
    };



    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/media/java/android/media/metrics/TrackChangeEvent.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off

    @Override
    public String toString() {
        return "TrackChangeEvent { " +
                "state = " + mState + ", " +
                "reason = " + mReason + ", " +
                "containerMimeType = " + mContainerMimeType + ", " +
                "sampleMimeType = " + mSampleMimeType + ", " +
                "codecName = " + mCodecName + ", " +
                "bitrate = " + mBitrate + ", " +
                "timeSincePlaybackCreatedMillis = " + mTimeSincePlaybackCreatedMillis + ", " +
                "type = " + mType + ", " +
                "language = " + mLanguage + ", " +
                "languageRegion = " + mLanguageRegion + ", " +
                "channelCount = " + mChannelCount + ", " +
                "sampleRate = " + mSampleRate + ", " +
                "width = " + mWidth + ", " +
                "height = " + mHeight +
        " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackChangeEvent that = (TrackChangeEvent) o;
        return mState == that.mState
                && mReason == that.mReason
                && Objects.equals(mContainerMimeType, that.mContainerMimeType)
                && Objects.equals(mSampleMimeType, that.mSampleMimeType)
                && Objects.equals(mCodecName, that.mCodecName)
                && mBitrate == that.mBitrate
                && mTimeSincePlaybackCreatedMillis == that.mTimeSincePlaybackCreatedMillis
                && mType == that.mType
                && Objects.equals(mLanguage, that.mLanguage)
                && Objects.equals(mLanguageRegion, that.mLanguageRegion)
                && mChannelCount == that.mChannelCount
                && mSampleRate == that.mSampleRate
                && mWidth == that.mWidth
                && mHeight == that.mHeight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState, mReason, mContainerMimeType, mSampleMimeType, mCodecName,
                mBitrate, mTimeSincePlaybackCreatedMillis, mType, mLanguage, mLanguageRegion,
                mChannelCount, mSampleRate, mWidth, mHeight);
    }

    /**
     * A builder for {@link TrackChangeEvent}
     */
    public static final class Builder {
        // TODO: check track type for the setters.
        private int mState;
        private int mReason;
        private @Nullable String mContainerMimeType;
        private @Nullable String mSampleMimeType;
        private @Nullable String mCodecName;
        private int mBitrate;
        private long mTimeSincePlaybackCreatedMillis;
        private int mType;
        private @Nullable String mLanguage;
        private @Nullable String mLanguageRegion;
        private int mChannelCount;
        private int mSampleRate;
        private int mWidth;
        private int mHeight;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @hide
         */
        public Builder(int type) {
            mType = type;
        }

        public @NonNull Builder setTrackState(@TrackState int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mState = value;
            return this;
        }

        public @NonNull Builder setTrackChangeReason(@TrackChangeReason int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mReason = value;
            return this;
        }

        public @NonNull Builder setContainerMimeType(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mContainerMimeType = value;
            return this;
        }

        public @NonNull Builder setSampleMimeType(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mSampleMimeType = value;
            return this;
        }

        public @NonNull Builder setCodecName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mCodecName = value;
            return this;
        }

        public @NonNull Builder setBitrate(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mBitrate = value;
            return this;
        }

        public @NonNull Builder setTimeSincePlaybackCreatedMillis(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mTimeSincePlaybackCreatedMillis = value;
            return this;
        }

        public @NonNull Builder setTrackType(@TrackType int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80;
            mType = value;
            return this;
        }

        public @NonNull Builder setLanguage(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100;
            mLanguage = value;
            return this;
        }

        public @NonNull Builder setLanguageRegion(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200;
            mLanguageRegion = value;
            return this;
        }

        public @NonNull Builder setChannelCount(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x400;
            mChannelCount = value;
            return this;
        }

        public @NonNull Builder setSampleRate(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x800;
            mSampleRate = value;
            return this;
        }

        public @NonNull Builder setWidth(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1000;
            mWidth = value;
            return this;
        }

        public @NonNull Builder setHeight(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2000;
            mHeight = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull TrackChangeEvent build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4000; // Mark builder used

            TrackChangeEvent o = new TrackChangeEvent(
                    mState,
                    mReason,
                    mContainerMimeType,
                    mSampleMimeType,
                    mCodecName,
                    mBitrate,
                    mTimeSincePlaybackCreatedMillis,
                    mType,
                    mLanguage,
                    mLanguageRegion,
                    mChannelCount,
                    mSampleRate,
                    mWidth,
                    mHeight);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4000) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
