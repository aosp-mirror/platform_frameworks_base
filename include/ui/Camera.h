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

#include <ui/ICameraClient.h>

namespace android {

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
    static  sp<Camera>  connect();
                        ~Camera();

            void        disconnect();

            status_t    getStatus() { return mStatus; }

            // pass the buffered ISurface to the camera service
            status_t    setPreviewDisplay(const sp<Surface>& surface);

            // start preview mode, must call setPreviewDisplay first
            status_t    startPreview();

            // stop preview mode
            void        stopPreview();

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
            void        setFrameCallback(frame_callback cb, void *cookie);
            void        setErrorCallback(error_callback cb, void *cookie);
            void        setAutoFocusCallback(autofocus_callback cb, void *cookie);
    // ICameraClient interface
    virtual void        shutterCallback();
    virtual void        rawCallback(const sp<IMemory>& picture);
    virtual void        jpegCallback(const sp<IMemory>& picture);
    virtual void        frameCallback(const sp<IMemory>& frame);
    virtual void        errorCallback(status_t error);
    virtual void        autoFocusCallback(bool focused);

    
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

