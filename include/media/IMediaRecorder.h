/*
 **
 ** Copyright 2008, The Android Open Source Project
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

#ifndef ANDROID_IMEDIARECORDER_H
#define ANDROID_IMEDIARECORDER_H

#include <binder/IInterface.h>

namespace android {

class Surface;
class ICamera;
class IMediaRecorderClient;

class IMediaRecorder: public IInterface
{
public:
    DECLARE_META_INTERFACE(MediaRecorder);

    virtual	status_t		setCamera(const sp<ICamera>& camera) = 0;
    virtual	status_t		setPreviewSurface(const sp<Surface>& surface) = 0;
    virtual	status_t		setVideoSource(int vs) = 0;
    virtual	status_t		setAudioSource(int as) = 0;
    virtual	status_t		setOutputFormat(int of) = 0;
    virtual	status_t		setVideoEncoder(int ve) = 0;
    virtual	status_t		setAudioEncoder(int ae) = 0;
    virtual	status_t		setOutputFile(const char* path) = 0;
    virtual	status_t		setOutputFile(int fd, int64_t offset, int64_t length) = 0;
    virtual	status_t		setOutputFileAuxiliary(int fd) = 0;
    virtual	status_t		setVideoSize(int width, int height) = 0;
    virtual	status_t		setVideoFrameRate(int frames_per_second) = 0;
    virtual     status_t                setParameters(const String8& params) = 0;
    virtual     status_t                setListener(const sp<IMediaRecorderClient>& listener) = 0;
    virtual	status_t		prepare() = 0;
    virtual	status_t		getMaxAmplitude(int* max) = 0;
    virtual	status_t		start() = 0;
    virtual	status_t		stop() = 0;
    virtual	status_t		reset() = 0;
    virtual status_t        init() = 0;
    virtual status_t        close() = 0;
    virtual	status_t		release() = 0;
};

// ----------------------------------------------------------------------------

class BnMediaRecorder: public BnInterface<IMediaRecorder>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif // ANDROID_IMEDIARECORDER_H
