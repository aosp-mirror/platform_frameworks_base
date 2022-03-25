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

package android.media.musicrecognition;

import static android.media.AudioAttributes.CONTENT_TYPE_MUSIC;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Encapsulates parameters for making music recognition queries via {@link MusicRecognitionManager}.
 *
 * @hide
 */
@SystemApi
public final class RecognitionRequest implements Parcelable {
    @NonNull private final AudioAttributes mAudioAttributes;
    @NonNull private final AudioFormat mAudioFormat;
    private final int mCaptureSession;
    private final int mMaxAudioLengthSeconds;
    private final int mIgnoreBeginningFrames;

    private RecognitionRequest(Builder b) {
        mAudioAttributes = requireNonNull(b.mAudioAttributes);
        mAudioFormat = requireNonNull(b.mAudioFormat);
        mCaptureSession = b.mCaptureSession;
        mMaxAudioLengthSeconds = b.mMaxAudioLengthSeconds;
        mIgnoreBeginningFrames = b.mIgnoreBeginningFrames;
    }

    @NonNull
    public AudioAttributes getAudioAttributes() {
        return mAudioAttributes;
    }

    @NonNull
    public AudioFormat getAudioFormat() {
        return mAudioFormat;
    }

    public int getCaptureSession() {
        return mCaptureSession;
    }

    @SuppressWarnings("MethodNameUnits")
    public int getMaxAudioLengthSeconds() {
        return mMaxAudioLengthSeconds;
    }

    public int getIgnoreBeginningFrames() {
        return mIgnoreBeginningFrames;
    }

    /**
     * Builder for constructing StreamSearchRequest objects.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private AudioFormat mAudioFormat = new AudioFormat.Builder()
                .setSampleRate(16000)
                .setEncoding(ENCODING_PCM_16BIT)
                .build();
        private AudioAttributes mAudioAttributes = new AudioAttributes.Builder()
                .setContentType(CONTENT_TYPE_MUSIC)
                .build();
        private int mCaptureSession = MediaRecorder.AudioSource.MIC;
        private int mMaxAudioLengthSeconds = 24; // Max enforced in system server.
        private int mIgnoreBeginningFrames = 0;

        /** Attributes passed to the constructed {@link AudioRecord}. */
        @NonNull
        public Builder setAudioAttributes(@NonNull AudioAttributes audioAttributes) {
            mAudioAttributes = audioAttributes;
            return this;
        }

        /** AudioFormat passed to the constructed {@link AudioRecord}. */
        @NonNull
        public Builder setAudioFormat(@NonNull AudioFormat audioFormat) {
            mAudioFormat = audioFormat;
            return this;
        }

        /** Constant from {@link android.media.MediaRecorder.AudioSource}. */
        @NonNull
        public Builder setCaptureSession(int captureSession) {
            mCaptureSession = captureSession;
            return this;
        }

        /** Maximum number of seconds to stream from the audio source. */
        @NonNull
        public Builder setMaxAudioLengthSeconds(int maxAudioLengthSeconds) {
            mMaxAudioLengthSeconds = maxAudioLengthSeconds;
            return this;
        }

        /**
         * Number of frames to drop from the start of the stream
         * (if recording is PCM stereo, one frame is two samples).
         **/
        @NonNull
        public Builder setIgnoreBeginningFrames(int ignoreBeginningFrames) {
            mIgnoreBeginningFrames = ignoreBeginningFrames;
            return this;
        }

        /** Returns the constructed request. */
        @NonNull
        public RecognitionRequest build() {
            return new RecognitionRequest(this);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mAudioFormat, flags);
        dest.writeParcelable(mAudioAttributes, flags);
        dest.writeInt(mCaptureSession);
        dest.writeInt(mMaxAudioLengthSeconds);
        dest.writeInt(mIgnoreBeginningFrames);
    }

    private RecognitionRequest(Parcel in) {
        mAudioFormat = in.readParcelable(AudioFormat.class.getClassLoader(), android.media.AudioFormat.class);
        mAudioAttributes = in.readParcelable(AudioAttributes.class.getClassLoader(), android.media.AudioAttributes.class);
        mCaptureSession = in.readInt();
        mMaxAudioLengthSeconds = in.readInt();
        mIgnoreBeginningFrames = in.readInt();
    }

    @NonNull public static final Creator<RecognitionRequest> CREATOR =
            new Creator<RecognitionRequest>() {

        @Override
        public RecognitionRequest createFromParcel(Parcel p) {
            return new RecognitionRequest(p);
        }

        @Override
        public RecognitionRequest[] newArray(int size) {
            return new RecognitionRequest[size];
        }
    };
}
