/*
 ** Copyright (C) 2008 The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 **
 ** limitations under the License.
 */

#ifndef ANDROID_MEDIARECORDER_H
#define ANDROID_MEDIARECORDER_H

#include <utils.h>
#include <media/IMediaPlayerClient.h>

namespace android {

class Surface;
class IMediaRecorder;
class ICamera;

typedef void (*media_completion_f)(status_t status, void *cookie);

/* Do not change these values without updating their counterparts
 * in java/android/android/media/MediaRecorder.java!
 */
enum audio_source {
    AUDIO_SOURCE_DEFAULT = 0,
    AUDIO_SOURCE_MIC = 1,
};

enum video_source {
    VIDEO_SOURCE_DEFAULT = 0,
    VIDEO_SOURCE_CAMERA = 1,
};

//Please update java/android/android/media/MediaRecorder.java if the following is updated.
enum output_format {
    OUTPUT_FORMAT_DEFAULT = 0,
    OUTPUT_FORMAT_THREE_GPP,
    OUTPUT_FORMAT_MPEG_4,
    OUTPUT_FORMAT_RAW_AMR,
    OUTPUT_FORMAT_LIST_END // must be last - used to validate format type
};

enum audio_encoder {
    AUDIO_ENCODER_DEFAULT = 0,
    AUDIO_ENCODER_AMR_NB = 1,
};

enum video_encoder {
    VIDEO_ENCODER_DEFAULT = 0,
    VIDEO_ENCODER_H263 = 1,
    VIDEO_ENCODER_H264 = 2,
    VIDEO_ENCODER_MPEG_4_SP = 3,
};

// Maximum frames per second is 24
#define MEDIA_RECORDER_MAX_FRAME_RATE         24

/*
 * The state machine of the media_recorder uses a set of different state names.
 * The mapping between the media_recorder and the pvauthorengine is shown below:
 *
 *    mediarecorder                        pvauthorengine
 * ----------------------------------------------------------------
 *    MEDIA_RECORDER_ERROR                 ERROR
 *    MEDIA_RECORDER_IDLE                  IDLE
 *    MEDIA_RECORDER_INITIALIZED           OPENED
 *    MEDIA_RECORDER_DATASOURCE_CONFIGURED
 *    MEDIA_RECORDER_PREPARED              INITIALIZED
 *    MEDIA_RECORDER_RECORDING             RECORDING
 */
enum media_recorder_states {
    MEDIA_RECORDER_ERROR                 =      0,
    MEDIA_RECORDER_IDLE                  = 1 << 0,
    MEDIA_RECORDER_INITIALIZED           = 1 << 1,
    MEDIA_RECORDER_DATASOURCE_CONFIGURED = 1 << 2,
    MEDIA_RECORDER_PREPARED              = 1 << 3,
    MEDIA_RECORDER_RECORDING             = 1 << 4,
};

// The "msg" code passed to the listener in notify.
enum {
    MEDIA_RECORDER_EVENT_ERROR                    = 1
};

enum {
    MEDIA_RECORDER_ERROR_UNKNOWN                  = 1
};

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class MediaRecorderListener: virtual public RefBase
{
public:
    virtual void notify(int msg, int ext1, int ext2) = 0;
};

class MediaRecorder : public BnMediaPlayerClient
{
public:
    MediaRecorder();
    ~MediaRecorder();

    status_t    initCheck();
    status_t    setCamera(const sp<ICamera>& camera);
    status_t    setPreviewSurface(const sp<Surface>& surface);
    status_t    setVideoSource(int vs);
    status_t    setAudioSource(int as);
    status_t    setOutputFormat(int of);
    status_t    setVideoEncoder(int ve);
    status_t    setAudioEncoder(int ae);
    status_t    setOutputFile(const char* path);
    status_t    setOutputFile(int fd, int64_t offset, int64_t length);
    status_t    setVideoSize(int width, int height);
    status_t    setVideoFrameRate(int frames_per_second);
    status_t    setListener(const sp<MediaRecorderListener>& listener);
    status_t    prepare();
    status_t    getMaxAmplitude(int* max);
    status_t    start();
    status_t    stop();
    status_t    reset();
    status_t    init();
    status_t    close();
    status_t    release();
    void        notify(int msg, int ext1, int ext2);

private:
    void                    doCleanUp();
    status_t                doReset();

    sp<IMediaRecorder>          mMediaRecorder;
    sp<MediaRecorderListener>   mListener;
    media_recorder_states       mCurrentState;
    bool                        mIsAudioSourceSet;
    bool                        mIsVideoSourceSet;
    bool                        mIsAudioEncoderSet;
    bool                        mIsVideoEncoderSet;
    bool                        mIsOutputFileSet;
    Mutex                       mLock;
    Mutex                       mNotifyLock;
};

};  // namespace android

#endif // ANDROID_MEDIARECORDER_H
