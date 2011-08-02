/*
**
** Copyright (C) 2008, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "Camera"
#include <utils/Log.h>
#include <utils/threads.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/IMemory.h>

#include <camera/Camera.h>
#include <camera/ICameraRecordingProxyListener.h>
#include <camera/ICameraService.h>

#include <surfaceflinger/Surface.h>

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
{
    init();
}

// construct a camera client from an existing camera remote
sp<Camera> Camera::create(const sp<ICamera>& camera)
{
     LOGV("create");
     if (camera == 0) {
         LOGE("camera remote is a NULL pointer");
         return 0;
     }

    sp<Camera> c = new Camera();
    if (camera->connect(c) == NO_ERROR) {
        c->mStatus = NO_ERROR;
        c->mCamera = camera;
        camera->asBinder()->linkToDeath(c);
        return c;
    }
    return 0;
}

void Camera::init()
{
    mStatus = UNKNOWN_ERROR;
}

Camera::~Camera()
{
    // We don't need to call disconnect() here because if the CameraService
    // thinks we are the owner of the hardware, it will hold a (strong)
    // reference to us, and we can't possibly be here. We also don't want to
    // call disconnect() here if we are in the same process as mediaserver,
    // because we may be invoked by CameraService::Client::connect() and will
    // deadlock if we call any method of ICamera here.
}

int32_t Camera::getNumberOfCameras()
{
    const sp<ICameraService>& cs = getCameraService();
    if (cs == 0) return 0;
    return cs->getNumberOfCameras();
}

status_t Camera::getCameraInfo(int cameraId,
                               struct CameraInfo* cameraInfo) {
    const sp<ICameraService>& cs = getCameraService();
    if (cs == 0) return UNKNOWN_ERROR;
    return cs->getCameraInfo(cameraId, cameraInfo);
}

sp<Camera> Camera::connect(int cameraId)
{
    LOGV("connect");
    sp<Camera> c = new Camera();
    const sp<ICameraService>& cs = getCameraService();
    if (cs != 0) {
        c->mCamera = cs->connect(c, cameraId);
    }
    if (c->mCamera != 0) {
        c->mCamera->asBinder()->linkToDeath(c);
        c->mStatus = NO_ERROR;
    } else {
        c.clear();
    }
    return c;
}

void Camera::disconnect()
{
    LOGV("disconnect");
    if (mCamera != 0) {
        mCamera->disconnect();
        mCamera->asBinder()->unlinkToDeath(this);
        mCamera = 0;
    }
}

status_t Camera::reconnect()
{
    LOGV("reconnect");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->connect(this);
}

sp<ICamera> Camera::remote()
{
    return mCamera;
}

status_t Camera::lock()
{
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->lock();
}

status_t Camera::unlock()
{
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->unlock();
}

// pass the buffered Surface to the camera service
status_t Camera::setPreviewDisplay(const sp<Surface>& surface)
{
    LOGV("setPreviewDisplay(%p)", surface.get());
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    if (surface != 0) {
        return c->setPreviewDisplay(surface);
    } else {
        LOGD("app passed NULL surface");
        return c->setPreviewDisplay(0);
    }
}

// pass the buffered ISurfaceTexture to the camera service
status_t Camera::setPreviewTexture(const sp<ISurfaceTexture>& surfaceTexture)
{
    LOGV("setPreviewTexture(%p)", surfaceTexture.get());
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    if (surfaceTexture != 0) {
        return c->setPreviewTexture(surfaceTexture);
    } else {
        LOGD("app passed NULL surface");
        return c->setPreviewTexture(0);
    }
}

// start preview mode
status_t Camera::startPreview()
{
    LOGV("startPreview");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->startPreview();
}

status_t Camera::storeMetaDataInBuffers(bool enabled)
{
    LOGV("storeMetaDataInBuffers: %s",
            enabled? "true": "false");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->storeMetaDataInBuffers(enabled);
}

// start recording mode, must call setPreviewDisplay first
status_t Camera::startRecording()
{
    LOGV("startRecording");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->startRecording();
}

// stop preview mode
void Camera::stopPreview()
{
    LOGV("stopPreview");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->stopPreview();
}

// stop recording mode
void Camera::stopRecording()
{
    LOGV("stopRecording");
    {
        Mutex::Autolock _l(mLock);
        mRecordingProxyListener.clear();
    }
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->stopRecording();
}

// release a recording frame
void Camera::releaseRecordingFrame(const sp<IMemory>& mem)
{
    LOGV("releaseRecordingFrame");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->releaseRecordingFrame(mem);
}

// get preview state
bool Camera::previewEnabled()
{
    LOGV("previewEnabled");
    sp <ICamera> c = mCamera;
    if (c == 0) return false;
    return c->previewEnabled();
}

// get recording state
bool Camera::recordingEnabled()
{
    LOGV("recordingEnabled");
    sp <ICamera> c = mCamera;
    if (c == 0) return false;
    return c->recordingEnabled();
}

status_t Camera::autoFocus()
{
    LOGV("autoFocus");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->autoFocus();
}

status_t Camera::cancelAutoFocus()
{
    LOGV("cancelAutoFocus");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->cancelAutoFocus();
}

// take a picture
status_t Camera::takePicture(int msgType)
{
    LOGV("takePicture: 0x%x", msgType);
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->takePicture(msgType);
}

// set preview/capture parameters - key/value pairs
status_t Camera::setParameters(const String8& params)
{
    LOGV("setParameters");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->setParameters(params);
}

// get preview/capture parameters - key/value pairs
String8 Camera::getParameters() const
{
    LOGV("getParameters");
    String8 params;
    sp <ICamera> c = mCamera;
    if (c != 0) params = mCamera->getParameters();
    return params;
}

// send command to camera driver
status_t Camera::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
{
    LOGV("sendCommand");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->sendCommand(cmd, arg1, arg2);
}

void Camera::setListener(const sp<CameraListener>& listener)
{
    Mutex::Autolock _l(mLock);
    mListener = listener;
}

void Camera::setRecordingProxyListener(const sp<ICameraRecordingProxyListener>& listener)
{
    Mutex::Autolock _l(mLock);
    mRecordingProxyListener = listener;
}

void Camera::setPreviewCallbackFlags(int flag)
{
    LOGV("setPreviewCallbackFlags");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    mCamera->setPreviewCallbackFlag(flag);
}

// callback from camera service
void Camera::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2)
{
    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->notify(msgType, ext1, ext2);
    }
}

// callback from camera service when frame or image is ready
void Camera::dataCallback(int32_t msgType, const sp<IMemory>& dataPtr,
                          camera_frame_metadata_t *metadata)
{
    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->postData(msgType, dataPtr, metadata);
    }
}

// callback from camera service when timestamped frame is ready
void Camera::dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr)
{
    // If recording proxy listener is registered, forward the frame and return.
    // The other listener (mListener) is ignored because the receiver needs to
    // call releaseRecordingFrame.
    sp<ICameraRecordingProxyListener> proxylistener;
    {
        Mutex::Autolock _l(mLock);
        proxylistener = mRecordingProxyListener;
    }
    if (proxylistener != NULL) {
        proxylistener->dataCallbackTimestamp(timestamp, msgType, dataPtr);
        return;
    }

    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->postDataTimestamp(timestamp, msgType, dataPtr);
    } else {
        LOGW("No listener was set. Drop a recording frame.");
        releaseRecordingFrame(dataPtr);
    }
}

void Camera::binderDied(const wp<IBinder>& who) {
    LOGW("ICamera died");
    notifyCallback(CAMERA_MSG_ERROR, CAMERA_ERROR_SERVER_DIED, 0);
}

void Camera::DeathNotifier::binderDied(const wp<IBinder>& who) {
    LOGV("binderDied");
    Mutex::Autolock _l(Camera::mLock);
    Camera::mCameraService.clear();
    LOGW("Camera server died!");
}

sp<ICameraRecordingProxy> Camera::getRecordingProxy() {
    LOGV("getProxy");
    return new RecordingProxy(this);
}

status_t Camera::RecordingProxy::startRecording(const sp<ICameraRecordingProxyListener>& listener)
{
    LOGV("RecordingProxy::startRecording");
    mCamera->setRecordingProxyListener(listener);
    mCamera->reconnect();
    return mCamera->startRecording();
}

void Camera::RecordingProxy::stopRecording()
{
    LOGV("RecordingProxy::stopRecording");
    mCamera->stopRecording();
}

void Camera::RecordingProxy::releaseRecordingFrame(const sp<IMemory>& mem)
{
    LOGV("RecordingProxy::releaseRecordingFrame");
    mCamera->releaseRecordingFrame(mem);
}

Camera::RecordingProxy::RecordingProxy(const sp<Camera>& camera)
{
    mCamera = camera;
}

}; // namespace android
