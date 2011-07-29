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

#ifndef ANDROID_HARDWARE_CAMERA_H
#define ANDROID_HARDWARE_CAMERA_H

#include <utils/Timers.h>
#include <gui/ISurfaceTexture.h>
#include <system/camera.h>
#include <camera/ICameraClient.h>
#include <camera/ICameraRecordingProxy.h>
#include <camera/ICameraRecordingProxyListener.h>

namespace android {

struct CameraInfo {
    /**
     * The direction that the camera faces to. It should be CAMERA_FACING_BACK
     * or CAMERA_FACING_FRONT.
     */
    int facing;

    /**
     * The orientation of the camera image. The value is the angle that the
     * camera image needs to be rotated clockwise so it shows correctly on the
     * display in its natural orientation. It should be 0, 90, 180, or 270.
     *
     * For example, suppose a device has a naturally tall screen. The
     * back-facing camera sensor is mounted in landscape. You are looking at
     * the screen. If the top side of the camera sensor is aligned with the
     * right edge of the screen in natural orientation, the value should be
     * 90. If the top side of a front-facing camera sensor is aligned with the
     * right of the screen, the value should be 270.
     */
    int orientation;
};

class ICameraService;
class ICamera;
class Surface;
class Mutex;
class String8;

// ref-counted object for callbacks
class CameraListener: virtual public RefBase
{
public:
    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2) = 0;
    virtual void postData(int32_t msgType, const sp<IMemory>& dataPtr,
                          camera_frame_metadata_t *metadata) = 0;
    virtual void postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) = 0;
};

class Camera : public BnCameraClient, public IBinder::DeathRecipient
{
public:
            // construct a camera client from an existing remote
    static  sp<Camera>  create(const sp<ICamera>& camera);
    static  int32_t     getNumberOfCameras();
    static  status_t    getCameraInfo(int cameraId,
                                      struct CameraInfo* cameraInfo);
    static  sp<Camera>  connect(int cameraId);
            virtual     ~Camera();
            void        init();

            status_t    reconnect();
            void        disconnect();
            status_t    lock();
            status_t    unlock();

            status_t    getStatus() { return mStatus; }

            // pass the buffered Surface to the camera service
            status_t    setPreviewDisplay(const sp<Surface>& surface);

            // pass the buffered ISurfaceTexture to the camera service
            status_t    setPreviewTexture(const sp<ISurfaceTexture>& surfaceTexture);

            // start preview mode, must call setPreviewDisplay first
            status_t    startPreview();

            // stop preview mode
            void        stopPreview();

            // get preview state
            bool        previewEnabled();

            // start recording mode, must call setPreviewDisplay first
            status_t    startRecording();

            // stop recording mode
            void        stopRecording();

            // get recording state
            bool        recordingEnabled();

            // release a recording frame
            void        releaseRecordingFrame(const sp<IMemory>& mem);

            // autoFocus - status returned from callback
            status_t    autoFocus();

            // cancel auto focus
            status_t    cancelAutoFocus();

            // take a picture - picture returned from callback
            status_t    takePicture(int msgType);

            // set preview/capture parameters - key/value pairs
            status_t    setParameters(const String8& params);

            // get preview/capture parameters - key/value pairs
            String8     getParameters() const;

            // send command to camera driver
            status_t    sendCommand(int32_t cmd, int32_t arg1, int32_t arg2);

            // tell camera hal to store meta data or real YUV in video buffers.
            status_t    storeMetaDataInBuffers(bool enabled);

            void        setListener(const sp<CameraListener>& listener);
            void        setRecordingProxyListener(const sp<ICameraRecordingProxyListener>& listener);
            void        setPreviewCallbackFlags(int preview_callback_flag);

            sp<ICameraRecordingProxy> getRecordingProxy();

    // ICameraClient interface
    virtual void        notifyCallback(int32_t msgType, int32_t ext, int32_t ext2);
    virtual void        dataCallback(int32_t msgType, const sp<IMemory>& dataPtr,
                                     camera_frame_metadata_t *metadata);
    virtual void        dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

    sp<ICamera>         remote();

    class RecordingProxy : public BnCameraRecordingProxy
    {
    public:
        RecordingProxy(const sp<Camera>& camera);

        // ICameraRecordingProxy interface
        virtual status_t startRecording(const sp<ICameraRecordingProxyListener>& listener);
        virtual void stopRecording();
        virtual void releaseRecordingFrame(const sp<IMemory>& mem);

    private:
        sp<Camera>         mCamera;
    };

private:
                        Camera();
                        Camera(const Camera&);
                        Camera& operator=(const Camera);
                        virtual void binderDied(const wp<IBinder>& who);

            class DeathNotifier: public IBinder::DeathRecipient
            {
            public:
                DeathNotifier() {
                }

                virtual void binderDied(const wp<IBinder>& who);
            };

            static sp<DeathNotifier> mDeathNotifier;

            // helper function to obtain camera service handle
            static const sp<ICameraService>& getCameraService();

            sp<ICamera>         mCamera;
            status_t            mStatus;

            sp<CameraListener>  mListener;
            sp<ICameraRecordingProxyListener>  mRecordingProxyListener;

            friend class DeathNotifier;

            static  Mutex               mLock;
            static  sp<ICameraService>  mCameraService;
};

}; // namespace android

#endif
