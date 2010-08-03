/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef STAGEFRIGHT_RECORDER_H_

#define STAGEFRIGHT_RECORDER_H_

#include <media/MediaRecorderBase.h>
#include <utils/String8.h>

namespace android {

class Camera;
struct MediaSource;
struct MediaWriter;
struct AudioSource;
class MediaProfiles;

struct StagefrightRecorder : public MediaRecorderBase {
    StagefrightRecorder();
    virtual ~StagefrightRecorder();

    virtual status_t init();
    virtual status_t setAudioSource(audio_source as);
    virtual status_t setVideoSource(video_source vs);
    virtual status_t setOutputFormat(output_format of);
    virtual status_t setAudioEncoder(audio_encoder ae);
    virtual status_t setVideoEncoder(video_encoder ve);
    virtual status_t setVideoSize(int width, int height);
    virtual status_t setVideoFrameRate(int frames_per_second);
    virtual status_t setCamera(const sp<ICamera>& camera);
    virtual status_t setPreviewSurface(const sp<ISurface>& surface);
    virtual status_t setOutputFile(const char *path);
    virtual status_t setOutputFile(int fd, int64_t offset, int64_t length);
    virtual status_t setParameters(const String8& params);
    virtual status_t setListener(const sp<IMediaRecorderClient>& listener);
    virtual status_t prepare();
    virtual status_t start();
    virtual status_t pause();
    virtual status_t stop();
    virtual status_t close();
    virtual status_t reset();
    virtual status_t getMaxAmplitude(int *max);
    virtual status_t dump(int fd, const Vector<String16>& args) const;

private:
    enum CameraFlags {
        FLAGS_SET_CAMERA = 1L << 0,
        FLAGS_HOT_CAMERA = 1L << 1,
    };

    sp<Camera> mCamera;
    sp<ISurface> mPreviewSurface;
    sp<IMediaRecorderClient> mListener;
    sp<MediaWriter> mWriter;
    sp<AudioSource> mAudioSourceNode;

    audio_source mAudioSource;
    video_source mVideoSource;
    output_format mOutputFormat;
    audio_encoder mAudioEncoder;
    video_encoder mVideoEncoder;
    bool mUse64BitFileOffset;
    int32_t mVideoWidth, mVideoHeight;
    int32_t mFrameRate;
    int32_t mVideoBitRate;
    int32_t mAudioBitRate;
    int32_t mAudioChannels;
    int32_t mSampleRate;
    int32_t mInterleaveDurationUs;
    int32_t mIFramesIntervalSec;
    int32_t mCameraId;
    int32_t mVideoEncoderProfile;
    int32_t mVideoEncoderLevel;
    int32_t mMovieTimeScale;
    int32_t mVideoTimeScale;
    int32_t mAudioTimeScale;
    int64_t mMaxFileSizeBytes;
    int64_t mMaxFileDurationUs;
    int64_t mTrackEveryTimeDurationUs;

    bool mCaptureTimeLapse;
    bool mUseStillCameraForTimeLapse;
    int64_t mTimeBetweenTimeLapseFrameCaptureUs;

    String8 mParams;
    int mOutputFd;
    int32_t mFlags;

    MediaProfiles *mEncoderProfiles;

    status_t startMPEG4Recording();
    status_t startAMRRecording();
    status_t startAACRecording();
    sp<MediaSource> createAudioSource();
    status_t setupCameraSource();
    status_t setupAudioEncoder(const sp<MediaWriter>& writer);
    status_t setupVideoEncoder(const sp<MediaWriter>& writer);

    // Encoding parameter handling utilities
    status_t setParameter(const String8 &key, const String8 &value);
    status_t setParamAudioEncodingBitRate(int32_t bitRate);
    status_t setParamAudioNumberOfChannels(int32_t channles);
    status_t setParamAudioSamplingRate(int32_t sampleRate);
    status_t setParamAudioTimeScale(int32_t timeScale);
    status_t setParamTimeLapseEnable(int32_t timeLapseEnable);
    status_t setParamUseStillCameraForTimeLapse(int32_t useStillCamera);
    status_t setParamTimeBetweenTimeLapseFrameCapture(int64_t timeUs);
    status_t setParamVideoEncodingBitRate(int32_t bitRate);
    status_t setParamVideoIFramesInterval(int32_t seconds);
    status_t setParamVideoEncoderProfile(int32_t profile);
    status_t setParamVideoEncoderLevel(int32_t level);
    status_t setParamVideoCameraId(int32_t cameraId);
    status_t setParamVideoTimeScale(int32_t timeScale);
    status_t setParamTrackTimeStatus(int64_t timeDurationUs);
    status_t setParamInterleaveDuration(int32_t durationUs);
    status_t setParam64BitFileOffset(bool use64BitFileOffset);
    status_t setParamMaxFileDurationUs(int64_t timeUs);
    status_t setParamMaxFileSizeBytes(int64_t bytes);
    status_t setParamMovieTimeScale(int32_t timeScale);
    void clipVideoBitRate();
    void clipVideoFrameRate();
    void clipVideoFrameWidth();
    void clipVideoFrameHeight();

    StagefrightRecorder(const StagefrightRecorder &);
    StagefrightRecorder &operator=(const StagefrightRecorder &);
};

}  // namespace android

#endif  // STAGEFRIGHT_RECORDER_H_

