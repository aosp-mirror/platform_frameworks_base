/*
 **
 ** Copyright 2008, HTC Inc.
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
 ** limitations under the License.
 */

#ifndef ANDROID_PVMEDIARECORDER_H
#define ANDROID_PVMEDIARECORDER_H

#include <media/mediarecorder.h>
#include <media/IMediaPlayerClient.h>

namespace android {

class ISurface;
class ICamera;
class AuthorDriverWrapper;

class PVMediaRecorder
{
public:
    PVMediaRecorder();
    ~PVMediaRecorder();

    status_t init();
    status_t setAudioSource(audio_source as);
    status_t setVideoSource(video_source vs);
    status_t setOutputFormat(output_format of);
    status_t setAudioEncoder(audio_encoder ae);
    status_t setVideoEncoder(video_encoder ve);
    status_t setVideoSize(int width, int height);
    status_t setVideoFrameRate(int frames_per_second);
    status_t setCamera(const sp<ICamera>& camera);
    status_t setPreviewSurface(const sp<ISurface>& surface);
    status_t setOutputFile(const char *path);
    status_t setOutputFile(int fd, int64_t offset, int64_t length);
    status_t setParameters(const String8& params);
    status_t setListener(const sp<IMediaPlayerClient>& listener);
    status_t prepare();
    status_t start();
    status_t stop();
    status_t close();
    status_t reset();
    status_t getMaxAmplitude(int *max);

private:
    status_t doStop();

    AuthorDriverWrapper*            mAuthorDriverWrapper;
};

}; // namespace android

#endif // ANDROID_PVMEDIARECORDER_H

