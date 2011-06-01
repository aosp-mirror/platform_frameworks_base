/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_HARDWARE_ICAMERA_RECORDING_PROXY_H
#define ANDROID_HARDWARE_ICAMERA_RECORDING_PROXY_H

#include <binder/IInterface.h>
#include <utils/RefBase.h>

namespace android {

class ICameraRecordingProxyListener;
class IMemory;
class Parcel;

/*
 * The purpose of ICameraRecordingProxy and ICameraRecordingProxyListener is to
 * allow applications using the camera during recording.
 *
 * Camera service allows only one client at a time. Since camcorder application
 * needs to own the camera to do things like zoom, the media recorder cannot
 * access the camera directly during recording. So ICameraRecordingProxy is a
 * proxy of ICamera, which allows the media recorder to start/stop the recording
 * and release recording frames. ICameraRecordingProxyListener is an interface
 * that allows the recorder to receive video frames during recording.
 *
 * ICameraRecordingProxy
 *   startRecording()
 *   stopRecording()
 *   releaseRecordingFrame()
 *
 * ICameraRecordingProxyListener
 *   dataCallbackTimestamp()

 * The camcorder app opens the camera and starts the preview. The app passes
 * ICamera and ICameraRecordingProxy to the media recorder by
 * MediaRecorder::setCamera(). The recorder uses ICamera to setup the camera in
 * MediaRecorder::start(). After setup, the recorder disconnects from camera
 * service. The recorder calls ICameraRecordingProxy::startRecording() and
 * passes a ICameraRecordingProxyListener to the app. The app connects back to
 * camera service and starts the recording. The app owns the camera and can do
 * things like zoom. The media recorder receives the video frames from the
 * listener and releases them by ICameraRecordingProxy::releaseRecordingFrame.
 * The recorder calls ICameraRecordingProxy::stopRecording() to stop the
 * recording.
 *
 * The call sequences are as follows:
 * 1. The app: Camera.unlock().
 * 2. The app: MediaRecorder.setCamera().
 * 3. Start recording
 *    (1) The app: MediaRecorder.start().
 *    (2) The recorder: ICamera.unlock() and ICamera.disconnect().
 *    (3) The recorder: ICameraRecordingProxy.startRecording().
 *    (4) The app: ICamera.reconnect().
 *    (5) The app: ICamera.startRecording().
 * 4. During recording
 *    (1) The recorder: receive frames from ICameraRecordingProxyListener.dataCallbackTimestamp()
 *    (2) The recorder: release frames by ICameraRecordingProxy.releaseRecordingFrame().
 * 5. Stop recording
 *    (1) The app: MediaRecorder.stop()
 *    (2) The recorder: ICameraRecordingProxy.stopRecording().
 *    (3) The app: ICamera.stopRecording().
 */

class ICameraRecordingProxy: public IInterface
{
public:
    DECLARE_META_INTERFACE(CameraRecordingProxy);

    virtual status_t        startRecording(const sp<ICameraRecordingProxyListener>& listener) = 0;
    virtual void            stopRecording() = 0;
    virtual void            releaseRecordingFrame(const sp<IMemory>& mem) = 0;
};

// ----------------------------------------------------------------------------

class BnCameraRecordingProxy: public BnInterface<ICameraRecordingProxy>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif
