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
#include <camera/CameraParameters.h>
#include <utils/String8.h>

#include <system/audio.h>

namespace android {

class Camera;
class ICameraRecordingProxy;
class CameraSource;
class CameraSourceTimeLapse;
class MediaSourceSplitter;
struct MediaSource;
struct MediaWriter;
class MetaData;
struct AudioSource;
class MediaProfiles;
class ISurfaceTexture;
class SurfaceMediaSource;

struct StagefrightRecorder : public MediaRecorderBase {
    StagefrightRecorder();
    virtual ~StagefrightRecorder();

    virtual status_t init();
    virtual status_t setAudioSource(audio_source_t as);
    virtual status_t setVideoSource(video_source vs);
    virtual status_t setOutputFormat(output_format of);
    virtual status_t setAudioEncoder(audio_encoder ae);
    virtual status_t setVideoEncoder(video_encoder ve);
    virtual status_t setVideoSize(int width, int height);
    virtual status_t setVideoFrameRate(int frames_per_second);
    virtual status_t setCamera(const sp<ICamera>& camera, const sp<ICameraRecordingProxy>& proxy);
    virtual status_t setPreviewSurface(const sp<Surface>& surface);
    virtual status_t setOutputFile(const char *path);
    virtual status_t setOutputFile(int fd, int64_t offset, int64_t length);
    virtual status_t setOutputFileAuxiliary(int fd);
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
    // Querying a SurfaceMediaSourcer
    virtual sp<ISurfaceTexture> querySurfaceMediaSource() const;

private:
    sp<ICamera> mCamera;
    sp<ICameraRecordingProxy> mCameraProxy;
    sp<Surface> mPreviewSurface;
    sp<IMediaRecorderClient> mListener;
    sp<MediaWriter> mWriter, mWriterAux;
    int mOutputFd, mOutputFdAux;
    sp<AudioSource> mAudioSourceNode;

    audio_source_t mAudioSource;
    video_source mVideoSource;
    output_format mOutputFormat;
    audio_encoder mAudioEncoder;
    video_encoder mVideoEncoder;
    bool mUse64BitFileOffset;
    int32_t mVideoWidth, mVideoHeight;
    int32_t mAuxVideoWidth, mAuxVideoHeight;
    int32_t mFrameRate;
    int32_t mVideoBitRate, mAuxVideoBitRate;
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
    int32_t mRotationDegrees;  // Clockwise
    int32_t mLatitudex10000;
    int32_t mLongitudex10000;
    int32_t mStartTimeOffsetMs;

    bool mCaptureTimeLapse;
    int64_t mTimeBetweenTimeLapseFrameCaptureUs;
    bool mCaptureAuxVideo;
    sp<MediaSourceSplitter> mCameraSourceSplitter;
    sp<CameraSourceTimeLapse> mCameraSourceTimeLapse;


    String8 mParams;

    bool mIsMetaDataStoredInVideoBuffers;
    MediaProfiles *mEncoderProfiles;

    bool mStarted;
    // Needed when GLFrames are encoded.
    // An <ISurfaceTexture> pointer
    // will be sent to the client side using which the
    // frame buffers will be queued and dequeued
    sp<SurfaceMediaSource> mSurfaceMediaSource;

    status_t setupMPEG4Recording(
        bool useSplitCameraSource,
        int outputFd,
        int32_t videoWidth, int32_t videoHeight,
        int32_t videoBitRate,
        int32_t *totalBitRate,
        sp<MediaWriter> *mediaWriter);
    void setupMPEG4MetaData(int64_t startTimeUs, int32_t totalBitRate,
        sp<MetaData> *meta);
    status_t startMPEG4Recording();
    status_t startAMRRecording();
    status_t startAACRecording();
    status_t startRawAudioRecording();
    status_t startRTPRecording();
    status_t startMPEG2TSRecording();
    sp<MediaSource> createAudioSource();
    status_t checkVideoEncoderCapabilities();
    status_t checkAudioEncoderCapabilities();
    // Generic MediaSource set-up. Returns the appropriate
    // source (CameraSource or SurfaceMediaSource)
    // depending on the videosource type
    status_t setupMediaSource(sp<MediaSource> *mediaSource);
    status_t setupCameraSource(sp<CameraSource> *cameraSource);
    // setup the surfacemediasource for the encoder
    status_t setupSurfaceMediaSource();

    status_t setupAudioEncoder(const sp<MediaWriter>& writer);
    status_t setupVideoEncoder(
            sp<MediaSource> cameraSource,
            int32_t videoBitRate,
            sp<MediaSource> *source);

    // Encoding parameter handling utilities
    status_t setParameter(const String8 &key, const String8 &value);
    status_t setParamAudioEncodingBitRate(int32_t bitRate);
    status_t setParamAudioNumberOfChannels(int32_t channles);
    status_t setParamAudioSamplingRate(int32_t sampleRate);
    status_t setParamAudioTimeScale(int32_t timeScale);
    status_t setParamTimeLapseEnable(int32_t timeLapseEnable);
    status_t setParamTimeBetweenTimeLapseFrameCapture(int64_t timeUs);
    status_t setParamAuxVideoHeight(int32_t height);
    status_t setParamAuxVideoWidth(int32_t width);
    status_t setParamAuxVideoEncodingBitRate(int32_t bitRate);
    status_t setParamVideoEncodingBitRate(int32_t bitRate);
    status_t setParamVideoIFramesInterval(int32_t seconds);
    status_t setParamVideoEncoderProfile(int32_t profile);
    status_t setParamVideoEncoderLevel(int32_t level);
    status_t setParamVideoCameraId(int32_t cameraId);
    status_t setParamVideoTimeScale(int32_t timeScale);
    status_t setParamVideoRotation(int32_t degrees);
    status_t setParamTrackTimeStatus(int64_t timeDurationUs);
    status_t setParamInterleaveDuration(int32_t durationUs);
    status_t setParam64BitFileOffset(bool use64BitFileOffset);
    status_t setParamMaxFileDurationUs(int64_t timeUs);
    status_t setParamMaxFileSizeBytes(int64_t bytes);
    status_t setParamMovieTimeScale(int32_t timeScale);
    status_t setParamGeoDataLongitude(int32_t longitudex10000);
    status_t setParamGeoDataLatitude(int32_t latitudex10000);
    void clipVideoBitRate();
    void clipVideoFrameRate();
    void clipVideoFrameWidth();
    void clipVideoFrameHeight();
    void clipAudioBitRate();
    void clipAudioSampleRate();
    void clipNumberOfAudioChannels();
    void setDefaultProfileIfNecessary();


    StagefrightRecorder(const StagefrightRecorder &);
    StagefrightRecorder &operator=(const StagefrightRecorder &);
};

}  // namespace android

#endif  // STAGEFRIGHT_RECORDER_H_
