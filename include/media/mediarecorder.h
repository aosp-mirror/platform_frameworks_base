/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef MEDIARECORDER_H
#define MEDIARECORDER_H

#include <utils.h>
#include <ui/SurfaceComposerClient.h>

namespace android {

class AuthorDriverWrapper;

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

enum output_format {
    OUTPUT_FORMAT_DEFAULT = 0,
    OUTPUT_FORMAT_THREE_GPP = 1,
    OUTPUT_FORMAT_MPEG_4 = 2,
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

/*
 * The state machine of the media_recorder uses a set of different state names.
 * The mapping between the media_recorder and the pvauthorengine is shown below:
 * 
 *    mediarecorder                        pvauthorengine
 * ----------------------------------------------------------------
 *    MEDIA_RECORDER_ERROR                 ERROR
 *    MEDIA_RECORDER_IDLE                  IDLE
 *    MEDIA_RECORDER_INITIALIZED           OPENED
 *    MEDIA_RECORDER_PREPARING
 *    MEDIA_RECORDER_PREPARED              INITIALIZED
 *    MEDIA_RECORDER_RECORDING             RECORDING
 */
enum media_recorder_states {
    MEDIA_RECORDER_ERROR =         0,
    MEDIA_RECORDER_IDLE =          1 << 0,
    MEDIA_RECORDER_INITIALIZED =   1 << 1,
    MEDIA_RECORDER_PREPARING =     1 << 2,
    MEDIA_RECORDER_PREPARED =      1 << 3,
    MEDIA_RECORDER_RECORDING =     1 << 4,
};

class MediaRecorder
{
public:
    MediaRecorder();
    ~MediaRecorder();

    status_t init();

    status_t setAudioSource(audio_source as);
    status_t setVideoSource(video_source vs);
    status_t setOutputFormat(output_format of);
    status_t setAudioEncoder(audio_encoder ae);
    status_t setVideoEncoder(video_encoder ve);
    status_t setVideoSize(int width, int height);
    status_t setVideoFrameRate(int frames_per_second);
    status_t setPreviewSurface(const sp<Surface>& surface);

    status_t setOutputFile(const char *path);
    // XXX metadata setup

    status_t prepare();
    status_t start();
    status_t stop();
    status_t reset();
    status_t getIfOutputFormatSpecified();

    status_t getMaxAmplitude(int *max);

private:
    AuthorDriverWrapper            *mAuthorDriverWrapper;
    bool                           mOutputFormatSpecified;
    media_recorder_states          mCurrentState;

};

}; // namespace android

#endif // MEDIAPLAYER_H

