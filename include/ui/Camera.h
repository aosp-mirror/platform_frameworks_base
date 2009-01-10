/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2008 HTC Inc.
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

#include <ui/ICameraClient.h>

namespace android {

/*
 * A set of bit masks for specifying how the received frames from preview are
 * handled before the frame callback call.
 *
 * The least significant 3 bits of an "int" value are used for this purpose:
 *
 * ..... 0 0 0
 *       ^ ^ ^
 *       | | |---------> determine whether the callback is enabled or not
 *       | |-----------> determine whether the callback is one-shot or not
 *       |-------------> determine whether the frame is copied out or not
 *
 * For instance,
 * 1. 0x00 disables the callback. In this case, copy out and one shot bits
 *    are ignored.
 * 2. 0x01 enables a callback without copying out the recievied frames. A
 *    typical use case is the Camcorder application to avoid making costly
 *    frame copies.
 * 3. 0x05 is enabling a callback with frame copied out repeatedly. A typical
 *    use case is the Camera application.
 * 4. 0x07 is enabling a callback with frame copied out only once. A typical use
 *    case is the Barcode scanner application.
 */
#define FRAME_CALLBACK_FLAG_ENABLE_MASK              0x01
#define FRAME_CALLBACK_FLAG_ONE_SHOT_MASK            0x02
#define FRAME_CALLBACK_FLAG_COPY_OUT_MASK            0x04

// Typical use cases
#define FRAME_CALLBACK_FLAG_NOOP                     0x00
#define FRAME_CALLBACK_FLAG_CAMCORDER                0x01
#define FRAME_CALLBACK_FLAG_CAMERA                   0x05
#define FRAME_CALLBACK_FLAG_BARCODE_SCANNER          0x07

class ICameraService;
class ICamera;
class Surface;
class Mutex;
class String8;

typedef void (*shutter_callback)(void *cookie);
typedef void (*frame_callback)(const sp<IMemory>& mem, void *cookie);
typedef void (*autofocus_callback)(bool focused, void *cookie);
typedef void (*error_callback)(status_t err, void *cookie);

class Camera : public BnCameraClient, public IBinder::DeathRecipient
{
public:
            // construct a camera client from an existing remote
            Camera(const sp<ICamera>& camera);

    static  sp<Camera>  connect();
                        ~Camera();
            void        init();

            status_t    reconnect();
            void        disconnect();
            status_t    lock();
            status_t    unlock();

            status_t    getStatus() { return mStatus; }

            // pass the buffered ISurface to the camera service
            status_t    setPreviewDisplay(const sp<Surface>& surface);
            status_t    setPreviewDisplay(const sp<ISurface>& surface);

            // start preview mode, must call setPreviewDisplay first
            status_t    startPreview();

            // stop preview mode
            void        stopPreview();

            // get preview state
            bool        previewEnabled();

            // autoFocus - status returned from callback
            status_t    autoFocus();

            // take a picture - picture returned from callback
            status_t    takePicture();

            // set preview/capture parameters - key/value pairs
            status_t    setParameters(const String8& params);

            // get preview/capture parameters - key/value pairs
            String8     getParameters() const;

            void        setShutterCallback(shutter_callback cb, void *cookie);
            void        setRawCallback(frame_callback cb, void *cookie);
            void        setJpegCallback(frame_callback cb, void *cookie);

            void        setFrameCallback(frame_callback cb,
                            void *cookie,
                            int frame_callback_flag = FRAME_CALLBACK_FLAG_NOOP);

            void        setErrorCallback(error_callback cb, void *cookie);
            void        setAutoFocusCallback(autofocus_callback cb, void *cookie);
    // ICameraClient interface
    virtual void        shutterCallback();
    virtual void        rawCallback(const sp<IMemory>& picture);
    virtual void        jpegCallback(const sp<IMemory>& picture);
    virtual void        frameCallback(const sp<IMemory>& frame);
    virtual void        errorCallback(status_t error);
    virtual void        autoFocusCallback(bool focused);

    sp<ICamera>         remote();

private:
                        Camera();
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

            shutter_callback    mShutterCallback;
            void                *mShutterCallbackCookie;
            frame_callback      mRawCallback;
            void                *mRawCallbackCookie;
            frame_callback      mJpegCallback;
            void                *mJpegCallbackCookie;
            frame_callback      mFrameCallback;
            void                *mFrameCallbackCookie;
            error_callback      mErrorCallback;
            void                *mErrorCallbackCookie;
            autofocus_callback  mAutoFocusCallback;
            void                *mAutoFocusCallbackCookie;

            friend class DeathNotifier;

            static  Mutex               mLock;
            static  sp<ICameraService>  mCameraService;

};

}; // namespace android

#endif

