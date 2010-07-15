/*
**
** Copyright (C) 2008, The Android Open Source Project
** Copyright (C) 2008 HTC Inc.
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

#define LOG_TAG "CameraService"

#include <stdio.h>
#include <sys/types.h>
#include <pthread.h>

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <cutils/atomic.h>
#include <hardware/hardware.h>
#include <media/AudioSystem.h>
#include <media/mediaplayer.h>
#include <surfaceflinger/ISurface.h>
#include <ui/Overlay.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include "CameraService.h"

namespace android {

// ----------------------------------------------------------------------------
// Logging support -- this is for debugging only
// Use "adb shell dumpsys media.camera -v 1" to change it.
static volatile int32_t gLogLevel = 0;

#define LOG1(...) LOGD_IF(gLogLevel >= 1, __VA_ARGS__);
#define LOG2(...) LOGD_IF(gLogLevel >= 2, __VA_ARGS__);

static void setLogLevel(int level) {
    android_atomic_write(level, &gLogLevel);
}

// ----------------------------------------------------------------------------

static int getCallingPid() {
    return IPCThreadState::self()->getCallingPid();
}

static int getCallingUid() {
    return IPCThreadState::self()->getCallingUid();
}

// ----------------------------------------------------------------------------

// This is ugly and only safe if we never re-create the CameraService, but
// should be ok for now.
static CameraService *gCameraService;

CameraService::CameraService()
:mSoundRef(0)
{
    LOGI("CameraService started (pid=%d)", getpid());

    mNumberOfCameras = HAL_getNumberOfCameras();
    if (mNumberOfCameras > MAX_CAMERAS) {
        LOGE("Number of cameras(%d) > MAX_CAMERAS(%d).",
             mNumberOfCameras, MAX_CAMERAS);
        mNumberOfCameras = MAX_CAMERAS;
    }

    for (int i = 0; i < mNumberOfCameras; i++) {
        setCameraFree(i);
    }

    gCameraService = this;
}

CameraService::~CameraService() {
    for (int i = 0; i < mNumberOfCameras; i++) {
        if (mBusy[i]) {
            LOGE("camera %d is still in use in destructor!", i);
        }
    }

    gCameraService = NULL;
}

int32_t CameraService::getNumberOfCameras() {
    return mNumberOfCameras;
}

status_t CameraService::getCameraInfo(int cameraId,
                                      struct CameraInfo* cameraInfo) {
    if (cameraId < 0 || cameraId >= mNumberOfCameras) {
        return BAD_VALUE;
    }

    HAL_getCameraInfo(cameraId, cameraInfo);
    return OK;
}

sp<ICamera> CameraService::connect(
        const sp<ICameraClient>& cameraClient, int cameraId) {
    int callingPid = getCallingPid();
    LOG1("CameraService::connect E (pid %d, id %d)", callingPid, cameraId);

    sp<Client> client;
    if (cameraId < 0 || cameraId >= mNumberOfCameras) {
        LOGE("CameraService::connect X (pid %d) rejected (invalid cameraId %d).",
            callingPid, cameraId);
        return NULL;
    }

    Mutex::Autolock lock(mServiceLock);
    if (mClient[cameraId] != 0) {
        client = mClient[cameraId].promote();
        if (client != 0) {
            if (cameraClient->asBinder() == client->getCameraClient()->asBinder()) {
                LOG1("CameraService::connect X (pid %d) (the same client)",
                    callingPid);
                return client;
            } else {
                LOGW("CameraService::connect X (pid %d) rejected (existing client).",
                    callingPid);
                return NULL;
            }
        }
        mClient[cameraId].clear();
    }

    if (mBusy[cameraId]) {
        LOGW("CameraService::connect X (pid %d) rejected"
             " (camera %d is still busy).", callingPid, cameraId);
        return NULL;
    }

    client = new Client(this, cameraClient, cameraId, callingPid);
    mClient[cameraId] = client;
    LOG1("CameraService::connect X");
    return client;
}

void CameraService::removeClient(const sp<ICameraClient>& cameraClient) {
    int callingPid = getCallingPid();
    LOG1("CameraService::removeClient E (pid %d)", callingPid);

    for (int i = 0; i < mNumberOfCameras; i++) {
        // Declare this before the lock to make absolutely sure the
        // destructor won't be called with the lock held.
        sp<Client> client;

        Mutex::Autolock lock(mServiceLock);

        // This happens when we have already disconnected (or this is
        // just another unused camera).
        if (mClient[i] == 0) continue;

        // Promote mClient. It can fail if we are called from this path:
        // Client::~Client() -> disconnect() -> removeClient().
        client = mClient[i].promote();

        if (client == 0) {
            mClient[i].clear();
            continue;
        }

        if (cameraClient->asBinder() == client->getCameraClient()->asBinder()) {
            // Found our camera, clear and leave.
            LOG1("removeClient: clear camera %d", i);
            mClient[i].clear();
            break;
        }
    }

    LOG1("CameraService::removeClient X (pid %d)", callingPid);
}

sp<CameraService::Client> CameraService::getClientById(int cameraId) {
    if (cameraId < 0 || cameraId >= mNumberOfCameras) return NULL;
    return mClient[cameraId].promote();
}

void CameraService::instantiate() {
    defaultServiceManager()->addService(String16("media.camera"),
        new CameraService());
}

status_t CameraService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    // Permission checks
    switch (code) {
        case BnCameraService::CONNECT:
            const int pid = getCallingPid();
            const int self_pid = getpid();
            if (pid != self_pid) {
                // we're called from a different process, do the real check
                if (!checkCallingPermission(
                        String16("android.permission.CAMERA"))) {
                    const int uid = getCallingUid();
                    LOGE("Permission Denial: "
                         "can't use the camera pid=%d, uid=%d", pid, uid);
                    return PERMISSION_DENIED;
                }
            }
            break;
    }

    return BnCameraService::onTransact(code, data, reply, flags);
}

// The reason we need this busy bit is a new CameraService::connect() request
// may come in while the previous Client's destructor has not been run or is
// still running. If the last strong reference of the previous Client is gone
// but the destructor has not been finished, we should not allow the new Client
// to be created because we need to wait for the previous Client to tear down
// the hardware first.
void CameraService::setCameraBusy(int cameraId) {
    android_atomic_write(1, &mBusy[cameraId]);
}

void CameraService::setCameraFree(int cameraId) {
    android_atomic_write(0, &mBusy[cameraId]);
}

// We share the media players for shutter and recording sound for all clients.
// A reference count is kept to determine when we will actually release the
// media players.

static MediaPlayer* newMediaPlayer(const char *file) {
    MediaPlayer* mp = new MediaPlayer();
    if (mp->setDataSource(file, NULL) == NO_ERROR) {
        mp->setAudioStreamType(AudioSystem::ENFORCED_AUDIBLE);
        mp->prepare();
    } else {
        LOGE("Failed to load CameraService sounds: %s", file);
        return NULL;
    }
    return mp;
}

void CameraService::loadSound() {
    Mutex::Autolock lock(mSoundLock);
    LOG1("CameraService::loadSound ref=%d", mSoundRef);
    if (mSoundRef++) return;

    mSoundPlayer[SOUND_SHUTTER] = newMediaPlayer("/system/media/audio/ui/camera_click.ogg");
    mSoundPlayer[SOUND_RECORDING] = newMediaPlayer("/system/media/audio/ui/VideoRecord.ogg");
}

void CameraService::releaseSound() {
    Mutex::Autolock lock(mSoundLock);
    LOG1("CameraService::releaseSound ref=%d", mSoundRef);
    if (--mSoundRef) return;

    for (int i = 0; i < NUM_SOUNDS; i++) {
        if (mSoundPlayer[i] != 0) {
            mSoundPlayer[i]->disconnect();
            mSoundPlayer[i].clear();
        }
    }
}

void CameraService::playSound(sound_kind kind) {
    LOG1("playSound(%d)", kind);
    Mutex::Autolock lock(mSoundLock);
    sp<MediaPlayer> player = mSoundPlayer[kind];
    if (player != 0) {
        // do not play the sound if stream volume is 0
        // (typically because ringer mode is silent).
        int index;
        AudioSystem::getStreamVolumeIndex(AudioSystem::ENFORCED_AUDIBLE, &index);
        if (index != 0) {
            player->seekTo(0);
            player->start();
        }
    }
}

// ----------------------------------------------------------------------------

CameraService::Client::Client(const sp<CameraService>& cameraService,
        const sp<ICameraClient>& cameraClient, int cameraId, int clientPid) {
    int callingPid = getCallingPid();
    LOG1("Client::Client E (pid %d)", callingPid);

    mCameraService = cameraService;
    mCameraClient = cameraClient;
    mCameraId = cameraId;
    mClientPid = clientPid;

    mHardware = HAL_openCameraHardware(cameraId);
    mUseOverlay = mHardware->useOverlay();
    mMsgEnabled = 0;

    mHardware->setCallbacks(notifyCallback,
                            dataCallback,
                            dataCallbackTimestamp,
                            (void *)cameraId);

    // Enable zoom, error, and focus messages by default
    enableMsgType(CAMERA_MSG_ERROR |
                  CAMERA_MSG_ZOOM |
                  CAMERA_MSG_FOCUS);
    mOverlayW = 0;
    mOverlayH = 0;

    // Callback is disabled by default
    mPreviewCallbackFlag = FRAME_CALLBACK_FLAG_NOOP;
    mOrientation = 0;
    cameraService->setCameraBusy(cameraId);
    cameraService->loadSound();
    LOG1("Client::Client X (pid %d)", callingPid);
}

static void *unregister_surface(void *arg) {
    ISurface *surface = (ISurface *)arg;
    surface->unregisterBuffers();
    IPCThreadState::self()->flushCommands();
    return NULL;
}

// tear down the client
CameraService::Client::~Client() {
    int callingPid = getCallingPid();
    LOG1("Client::~Client E (pid %d, this %p)", callingPid, this);

    if (mSurface != 0 && !mUseOverlay) {
        pthread_t thr;
        // We unregister the buffers in a different thread because binder does
        // not let us make sychronous transactions in a binder destructor (that
        // is, upon our reaching a refcount of zero.)
        pthread_create(&thr,
                       NULL,  // attr
                       unregister_surface,
                       mSurface.get());
        pthread_join(thr, NULL);
    }

    // set mClientPid to let disconnet() tear down the hardware
    mClientPid = callingPid;
    disconnect();
    mCameraService->releaseSound();
    LOG1("Client::~Client X (pid %d, this %p)", callingPid, this);
}

// ----------------------------------------------------------------------------

status_t CameraService::Client::checkPid() const {
    int callingPid = getCallingPid();
    if (callingPid == mClientPid) return NO_ERROR;

    LOGW("attempt to use a locked camera from a different process"
         " (old pid %d, new pid %d)", mClientPid, callingPid);
    return EBUSY;
}

status_t CameraService::Client::checkPidAndHardware() const {
    status_t result = checkPid();
    if (result != NO_ERROR) return result;
    if (mHardware == 0) {
        LOGE("attempt to use a camera after disconnect() (pid %d)", getCallingPid());
        return INVALID_OPERATION;
    }
    return NO_ERROR;
}

status_t CameraService::Client::lock() {
    int callingPid = getCallingPid();
    LOG1("lock (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    // lock camera to this client if the the camera is unlocked
    if (mClientPid == 0) {
        mClientPid = callingPid;
        return NO_ERROR;
    }

    // returns NO_ERROR if the client already owns the camera, EBUSY otherwise
    return checkPid();
}

status_t CameraService::Client::unlock() {
    int callingPid = getCallingPid();
    LOG1("unlock (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    // allow anyone to use camera (after they lock the camera)
    status_t result = checkPid();
    if (result == NO_ERROR) {
        mClientPid = 0;
        LOG1("clear mCameraClient (pid %d)", callingPid);
        // we need to remove the reference to ICameraClient so that when the app
        // goes away, the reference count goes to 0.
        mCameraClient.clear();
    }
    return result;
}

// connect a new client to the camera
status_t CameraService::Client::connect(const sp<ICameraClient>& client) {
    int callingPid = getCallingPid();
    LOG1("connect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    if (mClientPid != 0 && checkPid() != NO_ERROR) {
        LOGW("Tried to connect to a locked camera (old pid %d, new pid %d)",
                mClientPid, callingPid);
        return EBUSY;
    }

    if (mCameraClient != 0 && (client->asBinder() == mCameraClient->asBinder())) {
        LOG1("Connect to the same client");
        return NO_ERROR;
    }

    mPreviewCallbackFlag = FRAME_CALLBACK_FLAG_NOOP;
    mClientPid = callingPid;
    mCameraClient = client;

    LOG1("connect X (pid %d)", callingPid);
    return NO_ERROR;
}

void CameraService::Client::disconnect() {
    int callingPid = getCallingPid();
    LOG1("disconnect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    if (checkPid() != NO_ERROR) {
        LOGW("different client - don't disconnect");
        return;
    }

    if (mClientPid <= 0) {
        LOG1("camera is unlocked (mClientPid = %d), don't tear down hardware", mClientPid);
        return;
    }

    // Make sure disconnect() is done once and once only, whether it is called
    // from the user directly, or called by the destructor.
    if (mHardware == 0) return;

    LOG1("hardware teardown");
    // Before destroying mHardware, we must make sure it's in the
    // idle state.
    // Turn off all messages.
    disableMsgType(CAMERA_MSG_ALL_MSGS);
    mHardware->stopPreview();
    mHardware->cancelPicture();
    // Release the hardware resources.
    mHardware->release();
    // Release the held overlay resources.
    if (mUseOverlay) {
        mOverlayRef = 0;
    }
    mHardware.clear();

    mCameraService->removeClient(mCameraClient);
    mCameraService->setCameraFree(mCameraId);

    LOG1("disconnect X (pid %d)", callingPid);
}

// ----------------------------------------------------------------------------

// set the ISurface that the preview will use
status_t CameraService::Client::setPreviewDisplay(const sp<ISurface>& surface) {
    LOG1("setPreviewDisplay(%p) (pid %d)", surface.get(), getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    result = NO_ERROR;

    // return if no change in surface.
    // asBinder() is safe on NULL (returns NULL)
    if (surface->asBinder() == mSurface->asBinder()) {
        return result;
    }

    if (mSurface != 0) {
        LOG1("clearing old preview surface %p", mSurface.get());
        if (mUseOverlay) {
            // Force the destruction of any previous overlay
            sp<Overlay> dummy;
            mHardware->setOverlay(dummy);
        } else {
            mSurface->unregisterBuffers();
        }
    }
    mSurface = surface;
    mOverlayRef = 0;
    // If preview has been already started, set overlay or register preview
    // buffers now.
    if (mHardware->previewEnabled()) {
        if (mUseOverlay) {
            result = setOverlay();
        } else if (mSurface != 0) {
            result = registerPreviewBuffers();
        }
    }

    return result;
}

status_t CameraService::Client::registerPreviewBuffers() {
    int w, h;
    CameraParameters params(mHardware->getParameters());
    params.getPreviewSize(&w, &h);

    // FIXME: don't use a hardcoded format here.
    ISurface::BufferHeap buffers(w, h, w, h,
                                 HAL_PIXEL_FORMAT_YCrCb_420_SP,
                                 mOrientation,
                                 0,
                                 mHardware->getPreviewHeap());

    status_t result = mSurface->registerBuffers(buffers);
    if (result != NO_ERROR) {
        LOGE("registerBuffers failed with status %d", result);
    }
    return result;
}

status_t CameraService::Client::setOverlay() {
    int w, h;
    CameraParameters params(mHardware->getParameters());
    params.getPreviewSize(&w, &h);

    if (w != mOverlayW || h != mOverlayH) {
        // Force the destruction of any previous overlay
        sp<Overlay> dummy;
        mHardware->setOverlay(dummy);
        mOverlayRef = 0;
    }

    status_t result = NO_ERROR;
    if (mSurface == 0) {
        result = mHardware->setOverlay(NULL);
    } else {
        if (mOverlayRef == 0) {
            // FIXME:
            // Surfaceflinger may hold onto the previous overlay reference for some
            // time after we try to destroy it. retry a few times. In the future, we
            // should make the destroy call block, or possibly specify that we can
            // wait in the createOverlay call if the previous overlay is in the
            // process of being destroyed.
            for (int retry = 0; retry < 50; ++retry) {
                mOverlayRef = mSurface->createOverlay(w, h, OVERLAY_FORMAT_DEFAULT,
                                                      mOrientation);
                if (mOverlayRef != 0) break;
                LOGW("Overlay create failed - retrying");
                usleep(20000);
            }
            if (mOverlayRef == 0) {
                LOGE("Overlay Creation Failed!");
                return -EINVAL;
            }
            result = mHardware->setOverlay(new Overlay(mOverlayRef));
        }
    }
    if (result != NO_ERROR) {
        LOGE("mHardware->setOverlay() failed with status %d\n", result);
        return result;
    }

    mOverlayW = w;
    mOverlayH = h;

    return result;
}

// set the preview callback flag to affect how the received frames from
// preview are handled.
void CameraService::Client::setPreviewCallbackFlag(int callback_flag) {
    LOG1("setPreviewCallbackFlag(%d) (pid %d)", callback_flag, getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    mPreviewCallbackFlag = callback_flag;

    // If we don't use overlay, we always need the preview frame for display.
    // If we do use overlay, we only need the preview frame if the user
    // wants the data.
    if (mUseOverlay) {
        if(mPreviewCallbackFlag & FRAME_CALLBACK_FLAG_ENABLE_MASK) {
            enableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        } else {
            disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        }
    }
}

// start preview mode
status_t CameraService::Client::startPreview() {
    LOG1("startPreview (pid %d)", getCallingPid());
    return startCameraMode(CAMERA_PREVIEW_MODE);
}

// start recording mode
status_t CameraService::Client::startRecording() {
    LOG1("startRecording (pid %d)", getCallingPid());
    return startCameraMode(CAMERA_RECORDING_MODE);
}

// start preview or recording
status_t CameraService::Client::startCameraMode(camera_mode mode) {
    LOG1("startCameraMode(%d)", mode);
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    switch(mode) {
        case CAMERA_PREVIEW_MODE:
            if (mSurface == 0) {
                LOG1("mSurface is not set yet.");
                // still able to start preview in this case.
            }
            return startPreviewMode();
        case CAMERA_RECORDING_MODE:
            if (mSurface == 0) {
                LOGE("mSurface must be set before startRecordingMode.");
                return INVALID_OPERATION;
            }
            return startRecordingMode();
        default:
            return UNKNOWN_ERROR;
    }
}

status_t CameraService::Client::startPreviewMode() {
    LOG1("startPreviewMode");
    status_t result = NO_ERROR;

    // if preview has been enabled, nothing needs to be done
    if (mHardware->previewEnabled()) {
        return NO_ERROR;
    }

    if (mUseOverlay) {
        // If preview display has been set, set overlay now.
        if (mSurface != 0) {
            result = setOverlay();
        }
        if (result != NO_ERROR) return result;
        result = mHardware->startPreview();
    } else {
        enableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        result = mHardware->startPreview();
        if (result != NO_ERROR) return result;
        // If preview display has been set, register preview buffers now.
        if (mSurface != 0) {
           // Unregister here because the surface may be previously registered
           // with the raw (snapshot) heap.
           mSurface->unregisterBuffers();
           result = registerPreviewBuffers();
        }
    }
    return result;
}

status_t CameraService::Client::startRecordingMode() {
    LOG1("startRecordingMode");
    status_t result = NO_ERROR;

    // if recording has been enabled, nothing needs to be done
    if (mHardware->recordingEnabled()) {
        return NO_ERROR;
    }

    // if preview has not been started, start preview first
    if (!mHardware->previewEnabled()) {
        result = startPreviewMode();
        if (result != NO_ERROR) {
            return result;
        }
    }

    // start recording mode
    enableMsgType(CAMERA_MSG_VIDEO_FRAME);
    mCameraService->playSound(SOUND_RECORDING);
    result = mHardware->startRecording();
    if (result != NO_ERROR) {
        LOGE("mHardware->startRecording() failed with status %d", result);
    }
    return result;
}

// stop preview mode
void CameraService::Client::stopPreview() {
    LOG1("stopPreview (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    mHardware->stopPreview();

    if (mSurface != 0 && !mUseOverlay) {
        mSurface->unregisterBuffers();
    }

    mPreviewBuffer.clear();
}

// stop recording mode
void CameraService::Client::stopRecording() {
    LOG1("stopRecording (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    mCameraService->playSound(SOUND_RECORDING);
    disableMsgType(CAMERA_MSG_VIDEO_FRAME);
    mHardware->stopRecording();

    mPreviewBuffer.clear();
}

// release a recording frame
void CameraService::Client::releaseRecordingFrame(const sp<IMemory>& mem) {
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;
    mHardware->releaseRecordingFrame(mem);
}

bool CameraService::Client::previewEnabled() {
    LOG1("previewEnabled (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return false;
    return mHardware->previewEnabled();
}

bool CameraService::Client::recordingEnabled() {
    LOG1("recordingEnabled (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return false;
    return mHardware->recordingEnabled();
}

status_t CameraService::Client::autoFocus() {
    LOG1("autoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->autoFocus();
}

status_t CameraService::Client::cancelAutoFocus() {
    LOG1("cancelAutoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->cancelAutoFocus();
}

// take a picture - image is returned in callback
status_t CameraService::Client::takePicture() {
    LOG1("takePicture (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    enableMsgType(CAMERA_MSG_SHUTTER |
                  CAMERA_MSG_POSTVIEW_FRAME |
                  CAMERA_MSG_RAW_IMAGE |
                  CAMERA_MSG_COMPRESSED_IMAGE);

    return mHardware->takePicture();
}

// set preview/capture parameters - key/value pairs
status_t CameraService::Client::setParameters(const String8& params) {
    LOG1("setParameters (pid %d) (%s)", getCallingPid(), params.string());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    CameraParameters p(params);
    return mHardware->setParameters(p);
}

// get preview/capture parameters - key/value pairs
String8 CameraService::Client::getParameters() const {
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return String8();

    String8 params(mHardware->getParameters().flatten());
    LOG1("getParameters (pid %d) (%s)", getCallingPid(), params.string());
    return params;
}

status_t CameraService::Client::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) {
    LOG1("sendCommand (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    if (cmd == CAMERA_CMD_SET_DISPLAY_ORIENTATION) {
        // The orientation cannot be set during preview.
        if (mHardware->previewEnabled()) {
            return INVALID_OPERATION;
        }
        switch (arg1) {
            case 0:
                mOrientation = ISurface::BufferHeap::ROT_0;
                break;
            case 90:
                mOrientation = ISurface::BufferHeap::ROT_90;
                break;
            case 180:
                mOrientation = ISurface::BufferHeap::ROT_180;
                break;
            case 270:
                mOrientation = ISurface::BufferHeap::ROT_270;
                break;
            default:
                return BAD_VALUE;
        }
        return OK;
    }

    return mHardware->sendCommand(cmd, arg1, arg2);
}

// ----------------------------------------------------------------------------

void CameraService::Client::enableMsgType(int32_t msgType) {
    android_atomic_or(msgType, &mMsgEnabled);
    mHardware->enableMsgType(msgType);
}

void CameraService::Client::disableMsgType(int32_t msgType) {
    android_atomic_and(~msgType, &mMsgEnabled);
    mHardware->disableMsgType(msgType);
}

#define CHECK_MESSAGE_INTERVAL 10 // 10ms
bool CameraService::Client::lockIfMessageWanted(int32_t msgType) {
    int sleepCount = 0;
    while (mMsgEnabled & msgType) {
        if (mLock.tryLock() == NO_ERROR) {
            if (sleepCount > 0) {
                LOG1("lockIfMessageWanted(%d): waited for %d ms",
                    msgType, sleepCount * CHECK_MESSAGE_INTERVAL);
            }
            return true;
        }
        if (sleepCount++ == 0) {
            LOG1("lockIfMessageWanted(%d): enter sleep", msgType);
        }
        usleep(CHECK_MESSAGE_INTERVAL * 1000);
    }
    LOGW("lockIfMessageWanted(%d): dropped unwanted message", msgType);
    return false;
}

// ----------------------------------------------------------------------------

// Converts from a raw pointer to the client to a strong pointer during a
// hardware callback. This requires the callbacks only happen when the client
// is still alive.
sp<CameraService::Client> CameraService::Client::getClientFromCookie(void* user) {
    sp<Client> client = gCameraService->getClientById((int) user);

    // This could happen if the Client is in the process of shutting down (the
    // last strong reference is gone, but the destructor hasn't finished
    // stopping the hardware).
    if (client == 0) return NULL;

    // The checks below are not necessary and are for debugging only.
    if (client->mCameraService.get() != gCameraService) {
        LOGE("mismatch service!");
        return NULL;
    }

    if (client->mHardware == 0) {
        LOGE("mHardware == 0: callback after disconnect()?");
        return NULL;
    }

    return client;
}

// Callback messages can be dispatched to internal handlers or pass to our
// client's callback functions, depending on the message type.
//
// notifyCallback:
//      CAMERA_MSG_SHUTTER              handleShutter
//      (others)                        c->notifyCallback
// dataCallback:
//      CAMERA_MSG_PREVIEW_FRAME        handlePreviewData
//      CAMERA_MSG_POSTVIEW_FRAME       handlePostview
//      CAMERA_MSG_RAW_IMAGE            handleRawPicture
//      CAMERA_MSG_COMPRESSED_IMAGE     handleCompressedPicture
//      (others)                        c->dataCallback
// dataCallbackTimestamp
//      (others)                        c->dataCallbackTimestamp
//
// NOTE: the *Callback functions grab mLock of the client before passing
// control to handle* functions. So the handle* functions must release the
// lock before calling the ICameraClient's callbacks, so those callbacks can
// invoke methods in the Client class again (For example, the preview frame
// callback may want to releaseRecordingFrame). The handle* functions must
// release the lock after all accesses to member variables, so it must be
// handled very carefully.

void CameraService::Client::notifyCallback(int32_t msgType, int32_t ext1,
        int32_t ext2, void* user) {
    LOG2("notifyCallback(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) return;
    if (!client->lockIfMessageWanted(msgType)) return;

    switch (msgType) {
        case CAMERA_MSG_SHUTTER:
            // ext1 is the dimension of the yuv picture.
            client->handleShutter((image_rect_type *)ext1);
            break;
        default:
            client->handleGenericNotify(msgType, ext1, ext2);
            break;
    }
}

void CameraService::Client::dataCallback(int32_t msgType,
        const sp<IMemory>& dataPtr, void* user) {
    LOG2("dataCallback(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) return;
    if (!client->lockIfMessageWanted(msgType)) return;

    if (dataPtr == 0) {
        LOGE("Null data returned in data callback");
        client->handleGenericNotify(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
        return;
    }

    switch (msgType) {
        case CAMERA_MSG_PREVIEW_FRAME:
            client->handlePreviewData(dataPtr);
            break;
        case CAMERA_MSG_POSTVIEW_FRAME:
            client->handlePostview(dataPtr);
            break;
        case CAMERA_MSG_RAW_IMAGE:
            client->handleRawPicture(dataPtr);
            break;
        case CAMERA_MSG_COMPRESSED_IMAGE:
            client->handleCompressedPicture(dataPtr);
            break;
        default:
            client->handleGenericData(msgType, dataPtr);
            break;
    }
}

void CameraService::Client::dataCallbackTimestamp(nsecs_t timestamp,
        int32_t msgType, const sp<IMemory>& dataPtr, void* user) {
    LOG2("dataCallbackTimestamp(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) return;
    if (!client->lockIfMessageWanted(msgType)) return;

    if (dataPtr == 0) {
        LOGE("Null data returned in data with timestamp callback");
        client->handleGenericNotify(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
        return;
    }

    client->handleGenericDataTimestamp(timestamp, msgType, dataPtr);
}

// snapshot taken callback
// "size" is the width and height of yuv picture for registerBuffer.
// If it is NULL, use the picture size from parameters.
void CameraService::Client::handleShutter(image_rect_type *size) {
    mCameraService->playSound(SOUND_SHUTTER);

    // Screen goes black after the buffer is unregistered.
    if (mSurface != 0 && !mUseOverlay) {
        mSurface->unregisterBuffers();
    }

    sp<ICameraClient> c = mCameraClient;
    if (c != 0) {
        mLock.unlock();
        c->notifyCallback(CAMERA_MSG_SHUTTER, 0, 0);
        if (!lockIfMessageWanted(CAMERA_MSG_SHUTTER)) return;
    }
    disableMsgType(CAMERA_MSG_SHUTTER);

    // It takes some time before yuvPicture callback to be called.
    // Register the buffer for raw image here to reduce latency.
    if (mSurface != 0 && !mUseOverlay) {
        int w, h;
        CameraParameters params(mHardware->getParameters());
        if (size == NULL) {
            params.getPictureSize(&w, &h);
        } else {
            w = size->width;
            h = size->height;
            w &= ~1;
            h &= ~1;
            LOG1("Snapshot image width=%d, height=%d", w, h);
        }
        // FIXME: don't use hardcoded format constants here
        ISurface::BufferHeap buffers(w, h, w, h,
            HAL_PIXEL_FORMAT_YCrCb_420_SP, mOrientation, 0,
            mHardware->getRawHeap());

        mSurface->registerBuffers(buffers);
        IPCThreadState::self()->flushCommands();
    }

    mLock.unlock();
}

// preview callback - frame buffer update
void CameraService::Client::handlePreviewData(const sp<IMemory>& mem) {
    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

    if (!mUseOverlay) {
        if (mSurface != 0) {
            mSurface->postBuffer(offset);
        }
    }

    // local copy of the callback flags
    int flags = mPreviewCallbackFlag;

    // is callback enabled?
    if (!(flags & FRAME_CALLBACK_FLAG_ENABLE_MASK)) {
        // If the enable bit is off, the copy-out and one-shot bits are ignored
        LOG2("frame callback is disabled");
        mLock.unlock();
        return;
    }

    // hold a strong pointer to the client
    sp<ICameraClient> c = mCameraClient;

    // clear callback flags if no client or one-shot mode
    if (c == 0 || (mPreviewCallbackFlag & FRAME_CALLBACK_FLAG_ONE_SHOT_MASK)) {
        LOG2("Disable preview callback");
        mPreviewCallbackFlag &= ~(FRAME_CALLBACK_FLAG_ONE_SHOT_MASK |
                                  FRAME_CALLBACK_FLAG_COPY_OUT_MASK |
                                  FRAME_CALLBACK_FLAG_ENABLE_MASK);
        if (mUseOverlay) {
            disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        }
    }

    if (c != 0) {
        // Is the received frame copied out or not?
        if (flags & FRAME_CALLBACK_FLAG_COPY_OUT_MASK) {
            LOG2("frame is copied");
            copyFrameAndPostCopiedFrame(c, heap, offset, size);
        } else {
            LOG2("frame is forwarded");
            mLock.unlock();
            c->dataCallback(CAMERA_MSG_PREVIEW_FRAME, mem);
        }
    } else {
        mLock.unlock();
    }
}

// picture callback - postview image ready
void CameraService::Client::handlePostview(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_POSTVIEW_FRAME);

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_POSTVIEW_FRAME, mem);
    }
}

// picture callback - raw image ready
void CameraService::Client::handleRawPicture(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_RAW_IMAGE);

    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

    // Put the YUV version of the snapshot in the preview display.
    if (mSurface != 0 && !mUseOverlay) {
        mSurface->postBuffer(offset);
    }

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_RAW_IMAGE, mem);
    }
}

// picture callback - compressed picture ready
void CameraService::Client::handleCompressedPicture(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_COMPRESSED_IMAGE);

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_COMPRESSED_IMAGE, mem);
    }
}


void CameraService::Client::handleGenericNotify(int32_t msgType,
    int32_t ext1, int32_t ext2) {
    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->notifyCallback(msgType, ext1, ext2);
    }
}

void CameraService::Client::handleGenericData(int32_t msgType,
    const sp<IMemory>& dataPtr) {
    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(msgType, dataPtr);
    }
}

void CameraService::Client::handleGenericDataTimestamp(nsecs_t timestamp,
    int32_t msgType, const sp<IMemory>& dataPtr) {
    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallbackTimestamp(timestamp, msgType, dataPtr);
    }
}

void CameraService::Client::copyFrameAndPostCopiedFrame(
        const sp<ICameraClient>& client, const sp<IMemoryHeap>& heap,
        size_t offset, size_t size) {
    LOG2("copyFrameAndPostCopiedFrame");
    // It is necessary to copy out of pmem before sending this to
    // the callback. For efficiency, reuse the same MemoryHeapBase
    // provided it's big enough. Don't allocate the memory or
    // perform the copy if there's no callback.
    // hold the preview lock while we grab a reference to the preview buffer
    sp<MemoryHeapBase> previewBuffer;

    if (mPreviewBuffer == 0) {
        mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
    } else if (size > mPreviewBuffer->virtualSize()) {
        mPreviewBuffer.clear();
        mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
    }
    if (mPreviewBuffer == 0) {
        LOGE("failed to allocate space for preview buffer");
        mLock.unlock();
        return;
    }
    previewBuffer = mPreviewBuffer;

    memcpy(previewBuffer->base(), (uint8_t *)heap->base() + offset, size);

    sp<MemoryBase> frame = new MemoryBase(previewBuffer, 0, size);
    if (frame == 0) {
        LOGE("failed to allocate space for frame callback");
        mLock.unlock();
        return;
    }

    mLock.unlock();
    client->dataCallback(CAMERA_MSG_PREVIEW_FRAME, frame);
}

// ----------------------------------------------------------------------------

static const int kDumpLockRetries = 50;
static const int kDumpLockSleep = 60000;

static bool tryLock(Mutex& mutex)
{
    bool locked = false;
    for (int i = 0; i < kDumpLockRetries; ++i) {
        if (mutex.tryLock() == NO_ERROR) {
            locked = true;
            break;
        }
        usleep(kDumpLockSleep);
    }
    return locked;
}

status_t CameraService::dump(int fd, const Vector<String16>& args) {
    static const char* kDeadlockedString = "CameraService may be deadlocked\n";

    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump CameraService from pid=%d, uid=%d\n",
                getCallingPid(),
                getCallingUid());
        result.append(buffer);
        write(fd, result.string(), result.size());
    } else {
        bool locked = tryLock(mServiceLock);
        // failed to lock - CameraService is probably deadlocked
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        bool hasClient = false;
        for (int i = 0; i < mNumberOfCameras; i++) {
            sp<Client> client = mClient[i].promote();
            if (client == 0) continue;
            hasClient = true;
            sprintf(buffer, "Client[%d] (%p) PID: %d\n",
                    i,
                    client->getCameraClient()->asBinder().get(),
                    client->mClientPid);
            result.append(buffer);
            write(fd, result.string(), result.size());
            client->mHardware->dump(fd, args);
        }
        if (!hasClient) {
            result.append("No camera client yet.\n");
            write(fd, result.string(), result.size());
        }

        if (locked) mServiceLock.unlock();

        // change logging level
        int n = args.size();
        for (int i = 0; i + 1 < n; i++) {
            if (args[i] == String16("-v")) {
                String8 levelStr(args[i+1]);
                int level = atoi(levelStr.string());
                sprintf(buffer, "Set Log Level to %d", level);
                result.append(buffer);
                setLogLevel(level);
            }
        }
    }
    return NO_ERROR;
}

}; // namespace android
