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

#ifndef ANDROID_HARDWARE_ICAMERA_H
#define ANDROID_HARDWARE_ICAMERA_H

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <binder/IMemory.h>
#include <utils/String8.h>
#include <camera/Camera.h>

namespace android {

class ICameraClient;
class ISurfaceTexture;
class Surface;

class ICamera: public IInterface
{
public:
    DECLARE_META_INTERFACE(Camera);

    virtual void            disconnect() = 0;

    // connect new client with existing camera remote
    virtual status_t        connect(const sp<ICameraClient>& client) = 0;

    // prevent other processes from using this ICamera interface
    virtual status_t        lock() = 0;

    // allow other processes to use this ICamera interface
    virtual status_t        unlock() = 0;

    // pass the buffered Surface to the camera service
    virtual status_t        setPreviewDisplay(const sp<Surface>& surface) = 0;

    // pass the buffered ISurfaceTexture to the camera service
    virtual status_t        setPreviewTexture(
            const sp<ISurfaceTexture>& surfaceTexture) = 0;

    // set the preview callback flag to affect how the received frames from
    // preview are handled.
    virtual void            setPreviewCallbackFlag(int flag) = 0;

    // start preview mode, must call setPreviewDisplay first
    virtual status_t        startPreview() = 0;

    // stop preview mode
    virtual void            stopPreview() = 0;

    // get preview state
    virtual bool            previewEnabled() = 0;

    // start recording mode
    virtual status_t        startRecording() = 0;

    // stop recording mode
    virtual void            stopRecording() = 0;

    // get recording state
    virtual bool            recordingEnabled() = 0;

    // release a recording frame
    virtual void            releaseRecordingFrame(const sp<IMemory>& mem) = 0;

    // auto focus
    virtual status_t        autoFocus() = 0;

    // cancel auto focus
    virtual status_t        cancelAutoFocus() = 0;

    /*
     * take a picture.
     * @param msgType the message type an application selectively turn on/off
     * on a photo-by-photo basis. The supported message types are:
     * CAMERA_MSG_SHUTTER, CAMERA_MSG_RAW_IMAGE, CAMERA_MSG_COMPRESSED_IMAGE,
     * and CAMERA_MSG_POSTVIEW_FRAME. Any other message types will be ignored.
     */
    virtual status_t        takePicture(int msgType) = 0;

    // set preview/capture parameters - key/value pairs
    virtual status_t        setParameters(const String8& params) = 0;

    // get preview/capture parameters - key/value pairs
    virtual String8         getParameters() const = 0;

    // send command to camera driver
    virtual status_t        sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) = 0;

    // tell the camera hal to store meta data or real YUV data in video buffers.
    virtual status_t        storeMetaDataInBuffers(bool enabled) = 0;
};

// ----------------------------------------------------------------------------

class BnCamera: public BnInterface<ICamera>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif
