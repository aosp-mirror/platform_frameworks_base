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

#define LOG_TAG "Camera"
#include <utils/Log.h>

#include <utils/IServiceManager.h>
#include <utils/threads.h>
#include <utils/IMemory.h>
#include <ui/Surface.h>

#include <ui/Camera.h>
#include <ui/ICameraService.h>

namespace android {

// client singleton for camera service binder interface
Mutex Camera::mLock;
sp<ICameraService> Camera::mCameraService;
sp<Camera::DeathNotifier> Camera::mDeathNotifier;

// establish binder interface to camera service
const sp<ICameraService>& Camera::getCameraService()
{
    Mutex::Autolock _l(mLock);
    if (mCameraService.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.camera"));
            if (binder != 0)
                break;
            LOGW("CameraService not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (mDeathNotifier == NULL) {
            mDeathNotifier = new DeathNotifier();
        }
        binder->linkToDeath(mDeathNotifier);
        mCameraService = interface_cast<ICameraService>(binder);
    }
    LOGE_IF(mCameraService==0, "no CameraService!?");
    return mCameraService;
}

// ---------------------------------------------------------------------------

Camera::Camera()
      : mStatus(UNKNOWN_ERROR),
        mShutterCallback(0),
        mShutterCallbackCookie(0),
        mRawCallback(0),
        mRawCallbackCookie(0),
        mJpegCallback(0),
        mJpegCallbackCookie(0),
        mFrameCallback(0),
        mFrameCallbackCookie(0),
        mErrorCallback(0),
        mErrorCallbackCookie(0),
        mAutoFocusCallback(0),
        mAutoFocusCallbackCookie(0)
{
}

Camera::~Camera()
{
    disconnect();
}

sp<Camera> Camera::connect()
{
    sp<Camera> c = new Camera();
    const sp<ICameraService>& cs = getCameraService();
    if (cs != 0) {
        c->mCamera = cs->connect(c);
    }
    if (c->mCamera != 0) {
        c->mCamera->asBinder()->linkToDeath(c);
        c->mStatus = NO_ERROR;
    }
    return c;
}

void Camera::disconnect()
{
    if (mCamera != 0) {
        mErrorCallback = 0;
        mCamera->disconnect();
        mCamera = 0;
    }
}

// pass the buffered ISurface to the camera service
status_t Camera::setPreviewDisplay(const sp<Surface>& surface)
{
    if (surface == 0) {
        LOGE("app passed NULL surface");
        return NO_INIT;
    }
    return mCamera->setPreviewDisplay(surface->getISurface());
}

// start preview mode, must call setPreviewDisplay first
status_t Camera::startPreview()
{
    return mCamera->startPreview();
}

// stop preview mode
void Camera::stopPreview()
{
    mCamera->stopPreview();
}

status_t Camera::autoFocus()
{
    return mCamera->autoFocus();
}

// take a picture
status_t Camera::takePicture()
{
    return mCamera->takePicture();
}

// set preview/capture parameters - key/value pairs
status_t Camera::setParameters(const String8& params)
{
    return mCamera->setParameters(params);
}

// get preview/capture parameters - key/value pairs
String8 Camera::getParameters() const
{
    String8 params = mCamera->getParameters();
    return params;
}

void Camera::setAutoFocusCallback(autofocus_callback cb, void *cookie)
{
    mAutoFocusCallback = cb;
    mAutoFocusCallbackCookie = cookie;
}

void Camera::setShutterCallback(shutter_callback cb, void *cookie)
{
    mShutterCallback = cb;
    mShutterCallbackCookie = cookie;
}

void Camera::setRawCallback(frame_callback cb, void *cookie)
{
    mRawCallback = cb;
    mRawCallbackCookie = cookie;
}

void Camera::setJpegCallback(frame_callback cb, void *cookie)
{
    mJpegCallback = cb;
    mJpegCallbackCookie = cookie;
}

void Camera::setFrameCallback(frame_callback cb, void *cookie)
{
    mFrameCallback = cb;
    mFrameCallbackCookie = cookie;
    mCamera->setHasFrameCallback(cb != NULL);
}

void Camera::setErrorCallback(error_callback cb, void *cookie)
{
    mErrorCallback = cb;
    mErrorCallbackCookie = cookie;
}

void Camera::autoFocusCallback(bool focused)
{
    if (mAutoFocusCallback) {
        mAutoFocusCallback(focused, mAutoFocusCallbackCookie);
    }
}

void Camera::shutterCallback()
{
    if (mShutterCallback) {
        mShutterCallback(mShutterCallbackCookie);
    }
}

void Camera::rawCallback(const sp<IMemory>& picture)
{
    if (mRawCallback) {
        mRawCallback(picture, mRawCallbackCookie);
    }
}

// callback from camera service when image is ready
void Camera::jpegCallback(const sp<IMemory>& picture)
{
    if (mJpegCallback) {
        mJpegCallback(picture, mJpegCallbackCookie);
    }
}

// callback from camera service when video frame is ready
void Camera::frameCallback(const sp<IMemory>& frame)
{
    if (mFrameCallback) {
        mFrameCallback(frame, mFrameCallbackCookie);
    }
}

// callback from camera service when an error occurs in preview or takePicture
void Camera::errorCallback(status_t error)
{
    if (mErrorCallback) {
        mErrorCallback(error, mErrorCallbackCookie);
    }
}

void Camera::binderDied(const wp<IBinder>& who) {    
    LOGW("ICamera died");
    if (mErrorCallback) {
        mErrorCallback(DEAD_OBJECT, mErrorCallbackCookie);
    }
}

void Camera::DeathNotifier::binderDied(const wp<IBinder>& who) {    
    Mutex::Autolock _l(Camera::mLock);
    Camera::mCameraService.clear();
    LOGW("Camera server died!");
}

}; // namespace android

