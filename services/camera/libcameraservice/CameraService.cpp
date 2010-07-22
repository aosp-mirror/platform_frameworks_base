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
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <utils/String16.h>
#include <utils/Errors.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <camera/ICameraService.h>
#include <surfaceflinger/ISurface.h>
#include <ui/Overlay.h>

#include <hardware/hardware.h>

#include <media/mediaplayer.h>
#include <media/AudioSystem.h>
#include "CameraService.h"

#include <cutils/atomic.h>

namespace android {

extern "C" {
#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
}

// When you enable this, as well as DEBUG_REFS=1 and
// DEBUG_REFS_ENABLED_BY_DEFAULT=0 in libutils/RefBase.cpp, this will track all
// references to the CameraService::Client in order to catch the case where the
// client is being destroyed while a callback from the CameraHardwareInterface
// is outstanding.  This is a serious bug because if we make another call into
// CameraHardwreInterface that itself triggers a callback, we will deadlock.

#define DEBUG_CLIENT_REFERENCES 0

#define PICTURE_TIMEOUT seconds(5)

#define DEBUG_DUMP_PREVIEW_FRAME_TO_FILE 0 /* n-th frame to write */
#define DEBUG_DUMP_JPEG_SNAPSHOT_TO_FILE 0
#define DEBUG_DUMP_YUV_SNAPSHOT_TO_FILE 0
#define DEBUG_DUMP_POSTVIEW_SNAPSHOT_TO_FILE 0

#if DEBUG_DUMP_PREVIEW_FRAME_TO_FILE
static int debug_frame_cnt;
#endif

static int getCallingPid() {
    return IPCThreadState::self()->getCallingPid();
}

// ----------------------------------------------------------------------------

void CameraService::instantiate() {
    defaultServiceManager()->addService(
            String16("media.camera"), new CameraService());
}

// ----------------------------------------------------------------------------

CameraService::CameraService() :
    BnCameraService()
{
    LOGI("CameraService started: pid=%d", getpid());
    mUsers = 0;
}

CameraService::~CameraService()
{
    if (mClient != 0) {
        LOGE("mClient was still connected in destructor!");
    }
}

sp<ICamera> CameraService::connect(const sp<ICameraClient>& cameraClient)
{
    int callingPid = getCallingPid();
    LOGV("CameraService::connect E (pid %d, client %p)", callingPid,
            cameraClient->asBinder().get());

    Mutex::Autolock lock(mServiceLock);
    sp<Client> client;
    if (mClient != 0) {
        sp<Client> currentClient = mClient.promote();
        if (currentClient != 0) {
            sp<ICameraClient> currentCameraClient(currentClient->getCameraClient());
            if (cameraClient->asBinder() == currentCameraClient->asBinder()) {
                // This is the same client reconnecting...
                LOGV("CameraService::connect X (pid %d, same client %p) is reconnecting...",
                    callingPid, cameraClient->asBinder().get());
                return currentClient;
            } else {
                // It's another client... reject it
                LOGV("CameraService::connect X (pid %d, new client %p) rejected. "
                    "(old pid %d, old client %p)",
                    callingPid, cameraClient->asBinder().get(),
                    currentClient->mClientPid, currentCameraClient->asBinder().get());
                if (kill(currentClient->mClientPid, 0) == -1 && errno == ESRCH) {
                    LOGV("The old client is dead!");
                }
                return client;
            }
        } else {
            // can't promote, the previous client has died...
            LOGV("New client (pid %d) connecting, old reference was dangling...",
                    callingPid);
            mClient.clear();
        }
    }

    if (mUsers > 0) {
        LOGV("Still have client, rejected");
        return client;
    }

    // create a new Client object
    client = new Client(this, cameraClient, callingPid);
    mClient = client;
#if DEBUG_CLIENT_REFERENCES
    // Enable tracking for this object, and track increments and decrements of
    // the refcount.
    client->trackMe(true, true);
#endif
    LOGV("CameraService::connect X");
    return client;
}

void CameraService::removeClient(const sp<ICameraClient>& cameraClient)
{
    int callingPid = getCallingPid();

    // Declare this outside the lock to make absolutely sure the
    // destructor won't be called with the lock held.
    sp<Client> client;

    Mutex::Autolock lock(mServiceLock);

    if (mClient == 0) {
        // This happens when we have already disconnected.
        LOGV("removeClient (pid %d): already disconnected", callingPid);
        return;
    }

    // Promote mClient. It can fail if we are called from this path:
    // Client::~Client() -> disconnect() -> removeClient().
    client = mClient.promote();
    if (client == 0) {
        LOGV("removeClient (pid %d): no more strong reference", callingPid);
        mClient.clear();
        return;
    }

    if (cameraClient->asBinder() != client->getCameraClient()->asBinder()) {
        // ugh! that's not our client!!
        LOGW("removeClient (pid %d): mClient doesn't match!", callingPid);
    } else {
        // okay, good, forget about mClient
        mClient.clear();
    }

    LOGV("removeClient (pid %d) done", callingPid);
}

// The reason we need this count is a new CameraService::connect() request may
// come in while the previous Client's destructor has not been run or is still
// running. If the last strong reference of the previous Client is gone but
// destructor has not been run, we should not allow the new Client to be created
// because we need to wait for the previous Client to tear down the hardware
// first.
void CameraService::incUsers() {
    android_atomic_inc(&mUsers);
}

void CameraService::decUsers() {
    android_atomic_dec(&mUsers);
}

static sp<MediaPlayer> newMediaPlayer(const char *file)
{
    sp<MediaPlayer> mp = new MediaPlayer();
    if (mp->setDataSource(file, NULL /* headers */) == NO_ERROR) {
        mp->setAudioStreamType(AudioSystem::ENFORCED_AUDIBLE);
        mp->prepare();
    } else {
        mp.clear();
        LOGE("Failed to load CameraService sounds.");
    }
    return mp;
}

CameraService::Client::Client(const sp<CameraService>& cameraService,
        const sp<ICameraClient>& cameraClient, pid_t clientPid)
{
    int callingPid = getCallingPid();
    LOGV("Client::Client E (pid %d)", callingPid);
    mCameraService = cameraService;
    mCameraClient = cameraClient;
    mClientPid = clientPid;
    mHardware = openCameraHardware();
    mUseOverlay = mHardware->useOverlay();

    mHardware->setCallbacks(notifyCallback,
                            dataCallback,
                            dataCallbackTimestamp,
                            mCameraService.get());

    // Enable zoom, error, and focus messages by default
    mHardware->enableMsgType(CAMERA_MSG_ERROR |
                             CAMERA_MSG_ZOOM |
                             CAMERA_MSG_FOCUS);

    mMediaPlayerClick = newMediaPlayer("/system/media/audio/ui/camera_click.ogg");
    mMediaPlayerBeep = newMediaPlayer("/system/media/audio/ui/VideoRecord.ogg");
    mOverlayW = 0;
    mOverlayH = 0;

    // Callback is disabled by default
    mPreviewCallbackFlag = FRAME_CALLBACK_FLAG_NOOP;
    mOrientation = 0;
    cameraService->incUsers();
    LOGV("Client::Client X (pid %d)", callingPid);
}

status_t CameraService::Client::checkPid()
{
    int callingPid = getCallingPid();
    if (mClientPid == callingPid) return NO_ERROR;
    LOGW("Attempt to use locked camera (client %p) from different process "
        " (old pid %d, new pid %d)",
        getCameraClient()->asBinder().get(), mClientPid, callingPid);
    return -EBUSY;
}

status_t CameraService::Client::lock()
{
    int callingPid = getCallingPid();
    LOGV("lock from pid %d (mClientPid %d)", callingPid, mClientPid);
    Mutex::Autolock _l(mLock);
    // lock camera to this client if the the camera is unlocked
    if (mClientPid == 0) {
        mClientPid = callingPid;
        return NO_ERROR;
    }
    // returns NO_ERROR if the client already owns the camera, -EBUSY otherwise
    return checkPid();
}

status_t CameraService::Client::unlock()
{
    int callingPid = getCallingPid();
    LOGV("unlock from pid %d (mClientPid %d)", callingPid, mClientPid);
    Mutex::Autolock _l(mLock);
    // allow anyone to use camera
    status_t result = checkPid();
    if (result == NO_ERROR) {
        mClientPid = 0;
        LOGV("clear mCameraClient (pid %d)", callingPid);
        // we need to remove the reference so that when app goes
        // away, the reference count goes to 0.
        mCameraClient.clear();
    }
    return result;
}

status_t CameraService::Client::connect(const sp<ICameraClient>& client)
{
    int callingPid = getCallingPid();

    // connect a new process to the camera
    LOGV("Client::connect E (pid %d, client %p)", callingPid, client->asBinder().get());

    // I hate this hack, but things get really ugly when the media recorder
    // service is handing back the camera to the app. The ICameraClient
    // destructor will be called during the same IPC, making it look like
    // the remote client is trying to disconnect. This hack temporarily
    // sets the mClientPid to an invalid pid to prevent the hardware from
    // being torn down.
    {

        // hold a reference to the old client or we will deadlock if the client is
        // in the same process and we hold the lock when we remove the reference
        sp<ICameraClient> oldClient;
        {
            Mutex::Autolock _l(mLock);
            if (mClientPid != 0 && checkPid() != NO_ERROR) {
                LOGW("Tried to connect to locked camera (old pid %d, new pid %d)",
                        mClientPid, callingPid);
                return -EBUSY;
            }
            oldClient = mCameraClient;

            // did the client actually change?
            if ((mCameraClient != NULL) && (client->asBinder() == mCameraClient->asBinder())) {
                LOGV("Connect to the same client");
                return NO_ERROR;
            }

            mCameraClient = client;
            mClientPid = -1;
            mPreviewCallbackFlag = FRAME_CALLBACK_FLAG_NOOP;
            LOGV("Connect to the new client (pid %d, client %p)",
                callingPid, mCameraClient->asBinder().get());
        }

    }
    // the old client destructor is called when oldClient goes out of scope
    // now we set the new PID to lock the interface again
    mClientPid = callingPid;

    return NO_ERROR;
}

#if HAVE_ANDROID_OS
static void *unregister_surface(void *arg)
{
    ISurface *surface = (ISurface *)arg;
    surface->unregisterBuffers();
    IPCThreadState::self()->flushCommands();
    return NULL;
}
#endif

CameraService::Client::~Client()
{
    int callingPid = getCallingPid();

    // tear down client
    LOGV("Client::~Client E (pid %d, client %p)",
            callingPid, getCameraClient()->asBinder().get());
    if (mSurface != 0 && !mUseOverlay) {
#if HAVE_ANDROID_OS
        pthread_t thr;
        // We unregister the buffers in a different thread because binder does
        // not let us make sychronous transactions in a binder destructor (that
        // is, upon our reaching a refcount of zero.)
        pthread_create(&thr, NULL,
                       unregister_surface,
                       mSurface.get());
        pthread_join(thr, NULL);
#else
        mSurface->unregisterBuffers();
#endif
    }

    if (mMediaPlayerBeep.get() != NULL) {
        mMediaPlayerBeep->disconnect();
        mMediaPlayerBeep.clear();
    }
    if (mMediaPlayerClick.get() != NULL) {
        mMediaPlayerClick->disconnect();
        mMediaPlayerClick.clear();
    }

    // make sure we tear down the hardware
    mClientPid = callingPid;
    disconnect();
    LOGV("Client::~Client X (pid %d)", mClientPid);
}

void CameraService::Client::disconnect()
{
    int callingPid = getCallingPid();

    LOGV("Client::disconnect() E (pid %d client %p)",
            callingPid, getCameraClient()->asBinder().get());

    Mutex::Autolock lock(mLock);
    if (mClientPid <= 0) {
        LOGV("camera is unlocked (mClientPid = %d), don't tear down hardware", mClientPid);
        return;
    }
    if (checkPid() != NO_ERROR) {
        LOGV("Different client - don't disconnect");
        return;
    }

    // Make sure disconnect() is done once and once only, whether it is called
    // from the user directly, or called by the destructor.
    if (mHardware == 0) return;

    LOGV("hardware teardown");
    // Before destroying mHardware, we must make sure it's in the
    // idle state.
    mHardware->stopPreview();
    // Cancel all picture callbacks.
    mHardware->disableMsgType(CAMERA_MSG_SHUTTER |
                              CAMERA_MSG_POSTVIEW_FRAME |
                              CAMERA_MSG_RAW_IMAGE |
                              CAMERA_MSG_COMPRESSED_IMAGE);
    mHardware->cancelPicture();
    // Turn off remaining messages.
    mHardware->disableMsgType(CAMERA_MSG_ALL_MSGS);
    // Release the hardware resources.
    mHardware->release();
    // Release the held overlay resources.
    if (mUseOverlay)
    {
        mOverlayRef = 0;
    }
    mHardware.clear();

    mCameraService->removeClient(mCameraClient);
    mCameraService->decUsers();

    LOGV("Client::disconnect() X (pid %d)", callingPid);
}

// pass the buffered ISurface to the camera service
status_t CameraService::Client::setPreviewDisplay(const sp<ISurface>& surface)
{
    LOGV("setPreviewDisplay(%p) (pid %d)",
         ((surface == NULL) ? NULL : surface.get()), getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    Mutex::Autolock surfaceLock(mSurfaceLock);
    result = NO_ERROR;
    // asBinder() is safe on NULL (returns NULL)
    if (surface->asBinder() != mSurface->asBinder()) {
        if (mSurface != 0) {
            LOGV("clearing old preview surface %p", mSurface.get());
            if ( !mUseOverlay)
            {
                mSurface->unregisterBuffers();
            }
            else
            {
                // Force the destruction of any previous overlay
                sp<Overlay> dummy;
                mHardware->setOverlay( dummy );
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
    }
    return result;
}

// set the preview callback flag to affect how the received frames from
// preview are handled.
void CameraService::Client::setPreviewCallbackFlag(int callback_flag)
{
    LOGV("setPreviewCallbackFlag (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPid() != NO_ERROR) return;
    mPreviewCallbackFlag = callback_flag;

    if(mUseOverlay) {
        if(mPreviewCallbackFlag & FRAME_CALLBACK_FLAG_ENABLE_MASK)
            mHardware->enableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        else
            mHardware->disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    }
}

// start preview mode
status_t CameraService::Client::startCameraMode(camera_mode mode)
{
    int callingPid = getCallingPid();

    LOGV("startCameraMode(%d) (pid %d)", mode, callingPid);

    /* we cannot call into mHardware with mLock held because
     * mHardware has callbacks onto us which acquire this lock
     */

    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    switch(mode) {
    case CAMERA_RECORDING_MODE:
        if (mSurface == 0) {
            LOGE("setPreviewDisplay must be called before startRecordingMode.");
            return INVALID_OPERATION;
        }
        return startRecordingMode();

    default: // CAMERA_PREVIEW_MODE
        if (mSurface == 0) {
            LOGV("mSurface is not set yet.");
        }
        return startPreviewMode();
    }
}

status_t CameraService::Client::startRecordingMode()
{
    LOGV("startRecordingMode (pid %d)", getCallingPid());

    status_t ret = UNKNOWN_ERROR;

    // if preview has not been started, start preview first
    if (!mHardware->previewEnabled()) {
        ret = startPreviewMode();
        if (ret != NO_ERROR) {
            return ret;
        }
    }

    // if recording has been enabled, nothing needs to be done
    if (mHardware->recordingEnabled()) {
        return NO_ERROR;
    }

    // start recording mode
    ret = mHardware->startRecording();
    if (ret != NO_ERROR) {
        LOGE("mHardware->startRecording() failed with status %d", ret);
    }
    return ret;
}

status_t CameraService::Client::setOverlay()
{
    LOGV("setOverlay");
    int w, h;
    CameraParameters params(mHardware->getParameters());
    params.getPreviewSize(&w, &h);

    if ( w != mOverlayW || h != mOverlayH )
    {
        // Force the destruction of any previous overlay
        sp<Overlay> dummy;
        mHardware->setOverlay( dummy );
        mOverlayRef = 0;
    }

    status_t ret = NO_ERROR;
    if (mSurface != 0) {
        if (mOverlayRef.get() == NULL) {

            // FIXME:
            // Surfaceflinger may hold onto the previous overlay reference for some
            // time after we try to destroy it. retry a few times. In the future, we
            // should make the destroy call block, or possibly specify that we can
            // wait in the createOverlay call if the previous overlay is in the 
            // process of being destroyed.
            for (int retry = 0; retry < 50; ++retry) {
                mOverlayRef = mSurface->createOverlay(w, h, OVERLAY_FORMAT_DEFAULT,
                                                      mOrientation);
                if (mOverlayRef != NULL) break;
                LOGW("Overlay create failed - retrying");
                usleep(20000);
            }
            if ( mOverlayRef.get() == NULL )
            {
                LOGE("Overlay Creation Failed!");
                return -EINVAL;
            }
            ret = mHardware->setOverlay(new Overlay(mOverlayRef));
        }
    } else {
        ret = mHardware->setOverlay(NULL);
    }
    if (ret != NO_ERROR) {
        LOGE("mHardware->setOverlay() failed with status %d\n", ret);
    }

    mOverlayW = w;
    mOverlayH = h;

    return ret;
}

status_t CameraService::Client::registerPreviewBuffers()
{
    int w, h;
    CameraParameters params(mHardware->getParameters());
    params.getPreviewSize(&w, &h);

    // don't use a hardcoded format here
    ISurface::BufferHeap buffers(w, h, w, h,
                                 HAL_PIXEL_FORMAT_YCrCb_420_SP,
                                 mOrientation,
                                 0,
                                 mHardware->getPreviewHeap());

    status_t ret = mSurface->registerBuffers(buffers);
    if (ret != NO_ERROR) {
        LOGE("registerBuffers failed with status %d", ret);
    }
    return ret;
}

status_t CameraService::Client::startPreviewMode()
{
    LOGV("startPreviewMode (pid %d)", getCallingPid());

    // if preview has been enabled, nothing needs to be done
    if (mHardware->previewEnabled()) {
        return NO_ERROR;
    }

    // start preview mode
#if DEBUG_DUMP_PREVIEW_FRAME_TO_FILE
    debug_frame_cnt = 0;
#endif
    status_t ret = NO_ERROR;

    if (mUseOverlay) {
        // If preview display has been set, set overlay now.
        if (mSurface != 0) {
            ret = setOverlay();
        }
        if (ret != NO_ERROR) return ret;
        ret = mHardware->startPreview();
    } else {
        mHardware->enableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        ret = mHardware->startPreview();
        if (ret != NO_ERROR) return ret;
        // If preview display has been set, register preview buffers now.
        if (mSurface != 0) {
           // Unregister here because the surface registered with raw heap.
           mSurface->unregisterBuffers();
           ret = registerPreviewBuffers();
        }
    }
    return ret;
}

status_t CameraService::Client::startPreview()
{
    LOGV("startPreview (pid %d)", getCallingPid());

    return startCameraMode(CAMERA_PREVIEW_MODE);
}

status_t CameraService::Client::startRecording()
{
    LOGV("startRecording (pid %d)", getCallingPid());

    if (mMediaPlayerBeep.get() != NULL) {
        // do not play record jingle if stream volume is 0
        // (typically because ringer mode is silent).
        int index;
        AudioSystem::getStreamVolumeIndex(AudioSystem::ENFORCED_AUDIBLE, &index);
        if (index != 0) {
            mMediaPlayerBeep->seekTo(0);
            mMediaPlayerBeep->start();
        }
    }

    mHardware->enableMsgType(CAMERA_MSG_VIDEO_FRAME);

    return startCameraMode(CAMERA_RECORDING_MODE);
}

// stop preview mode
void CameraService::Client::stopPreview()
{
    LOGV("stopPreview (pid %d)", getCallingPid());

    // hold main lock during state transition
    {
        Mutex::Autolock lock(mLock);
        if (checkPid() != NO_ERROR) return;

        if (mHardware == 0) {
            LOGE("mHardware is NULL, returning.");
            return;
        }

        mHardware->stopPreview();
        mHardware->disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
        LOGV("stopPreview(), hardware stopped OK");

        if (mSurface != 0 && !mUseOverlay) {
            mSurface->unregisterBuffers();
        }
    }

    // hold preview buffer lock
    {
        Mutex::Autolock lock(mPreviewLock);
        mPreviewBuffer.clear();
    }
}

// stop recording mode
void CameraService::Client::stopRecording()
{
    LOGV("stopRecording (pid %d)", getCallingPid());

    // hold main lock during state transition
    {
        Mutex::Autolock lock(mLock);
        if (checkPid() != NO_ERROR) return;

        if (mHardware == 0) {
            LOGE("mHardware is NULL, returning.");
            return;
        }

        if (mMediaPlayerBeep.get() != NULL) {
            mMediaPlayerBeep->seekTo(0);
            mMediaPlayerBeep->start();
        }

        mHardware->stopRecording();
        mHardware->disableMsgType(CAMERA_MSG_VIDEO_FRAME);
        LOGV("stopRecording(), hardware stopped OK");
    }

    // hold preview buffer lock
    {
        Mutex::Autolock lock(mPreviewLock);
        mPreviewBuffer.clear();
    }
}

// release a recording frame
void CameraService::Client::releaseRecordingFrame(const sp<IMemory>& mem)
{
    Mutex::Autolock lock(mLock);
    if (checkPid() != NO_ERROR) return;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return;
    }

    mHardware->releaseRecordingFrame(mem);
}

bool CameraService::Client::previewEnabled()
{
    Mutex::Autolock lock(mLock);
    if (mHardware == 0) return false;
    return mHardware->previewEnabled();
}

bool CameraService::Client::recordingEnabled()
{
    Mutex::Autolock lock(mLock);
    if (mHardware == 0) return false;
    return mHardware->recordingEnabled();
}

// Safely retrieves a strong pointer to the client during a hardware callback.
sp<CameraService::Client> CameraService::Client::getClientFromCookie(void* user)
{
    sp<Client> client = 0;
    CameraService *service = static_cast<CameraService*>(user);
    if (service != NULL) {
        Mutex::Autolock ourLock(service->mServiceLock);
        if (service->mClient != 0) {
            client = service->mClient.promote();
            if (client == 0) {
                LOGE("getClientFromCookie: client appears to have died");
                service->mClient.clear();
            }
        } else {
            LOGE("getClientFromCookie: got callback but client was NULL");
        }
    }
    return client;
}


#if DEBUG_DUMP_JPEG_SNAPSHOT_TO_FILE || \
    DEBUG_DUMP_YUV_SNAPSHOT_TO_FILE || \
    DEBUG_DUMP_PREVIEW_FRAME_TO_FILE
static void dump_to_file(const char *fname,
                         uint8_t *buf, uint32_t size)
{
    int nw, cnt = 0;
    uint32_t written = 0;

    LOGV("opening file [%s]\n", fname);
    int fd = open(fname, O_RDWR | O_CREAT);
    if (fd < 0) {
        LOGE("failed to create file [%s]: %s", fname, strerror(errno));
        return;
    }

    LOGV("writing %d bytes to file [%s]\n", size, fname);
    while (written < size) {
        nw = ::write(fd,
                     buf + written,
                     size - written);
        if (nw < 0) {
            LOGE("failed to write to file [%s]: %s",
                 fname, strerror(errno));
            break;
        }
        written += nw;
        cnt++;
    }
    LOGV("done writing %d bytes to file [%s] in %d passes\n",
         size, fname, cnt);
    ::close(fd);
}
#endif

status_t CameraService::Client::autoFocus()
{
    LOGV("autoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    return mHardware->autoFocus();
}

status_t CameraService::Client::cancelAutoFocus()
{
    LOGV("cancelAutoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    return mHardware->cancelAutoFocus();
}

// take a picture - image is returned in callback
status_t CameraService::Client::takePicture()
{
    LOGV("takePicture (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    mHardware->enableMsgType(CAMERA_MSG_SHUTTER |
                             CAMERA_MSG_POSTVIEW_FRAME |
                             CAMERA_MSG_RAW_IMAGE |
                             CAMERA_MSG_COMPRESSED_IMAGE);

    return mHardware->takePicture();
}

// snapshot taken
void CameraService::Client::handleShutter(
    image_rect_type *size // The width and height of yuv picture for
                          // registerBuffer. If this is NULL, use the picture
                          // size from parameters.
)
{
    // Play shutter sound.
    if (mMediaPlayerClick.get() != NULL) {
        // do not play shutter sound if stream volume is 0
        // (typically because ringer mode is silent).
        int index;
        AudioSystem::getStreamVolumeIndex(AudioSystem::ENFORCED_AUDIBLE, &index);
        if (index != 0) {
            mMediaPlayerClick->seekTo(0);
            mMediaPlayerClick->start();
        }
    }

    // Screen goes black after the buffer is unregistered.
    if (mSurface != 0 && !mUseOverlay) {
        mSurface->unregisterBuffers();
    }

    sp<ICameraClient> c = mCameraClient;
    if (c != NULL) {
        c->notifyCallback(CAMERA_MSG_SHUTTER, 0, 0);
    }
    mHardware->disableMsgType(CAMERA_MSG_SHUTTER);

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
            LOGV("Snapshot image width=%d, height=%d", w, h);
        }
        // FIXME: don't use hardcoded format constants here
        ISurface::BufferHeap buffers(w, h, w, h,
            HAL_PIXEL_FORMAT_YCrCb_420_SP, mOrientation, 0,
            mHardware->getRawHeap());

        mSurface->registerBuffers(buffers);
    }
}

// preview callback - frame buffer update
void CameraService::Client::handlePreviewData(const sp<IMemory>& mem)
{
    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

#if DEBUG_HEAP_LEAKS && 0 // debugging
    if (gWeakHeap == NULL) {
        if (gWeakHeap != heap) {
            LOGV("SETTING PREVIEW HEAP");
            heap->trackMe(true, true);
            gWeakHeap = heap;
        }
    }
#endif
#if DEBUG_DUMP_PREVIEW_FRAME_TO_FILE
    {
        if (debug_frame_cnt++ == DEBUG_DUMP_PREVIEW_FRAME_TO_FILE) {
            dump_to_file("/data/preview.yuv",
                         (uint8_t *)heap->base() + offset, size);
        }
    }
#endif

    if (!mUseOverlay)
    {
        Mutex::Autolock surfaceLock(mSurfaceLock);
        if (mSurface != NULL) {
            mSurface->postBuffer(offset);
        }
    }

    // local copy of the callback flags
    int flags = mPreviewCallbackFlag;

    // is callback enabled?
    if (!(flags & FRAME_CALLBACK_FLAG_ENABLE_MASK)) {
        // If the enable bit is off, the copy-out and one-shot bits are ignored
        LOGV("frame callback is diabled");
        return;
    }

    // hold a strong pointer to the client
    sp<ICameraClient> c = mCameraClient;

    // clear callback flags if no client or one-shot mode
    if ((c == NULL) || (mPreviewCallbackFlag & FRAME_CALLBACK_FLAG_ONE_SHOT_MASK)) {
        LOGV("Disable preview callback");
        mPreviewCallbackFlag &= ~(FRAME_CALLBACK_FLAG_ONE_SHOT_MASK |
                                FRAME_CALLBACK_FLAG_COPY_OUT_MASK |
                                FRAME_CALLBACK_FLAG_ENABLE_MASK);
        // TODO: Shouldn't we use this API for non-overlay hardware as well?
        if (mUseOverlay)
            mHardware->disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    }

    // Is the received frame copied out or not?
    if (flags & FRAME_CALLBACK_FLAG_COPY_OUT_MASK) {
        LOGV("frame is copied");
        copyFrameAndPostCopiedFrame(c, heap, offset, size);
    } else {
        LOGV("frame is forwarded");
        c->dataCallback(CAMERA_MSG_PREVIEW_FRAME, mem);
    }
}

// picture callback - postview image ready
void CameraService::Client::handlePostview(const sp<IMemory>& mem)
{
#if DEBUG_DUMP_POSTVIEW_SNAPSHOT_TO_FILE // for testing pursposes only
    {
        ssize_t offset;
        size_t size;
        sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
        dump_to_file("/data/postview.yuv",
                     (uint8_t *)heap->base() + offset, size);
    }
#endif

    sp<ICameraClient> c = mCameraClient;
    if (c != NULL) {
        c->dataCallback(CAMERA_MSG_POSTVIEW_FRAME, mem);
    }
    mHardware->disableMsgType(CAMERA_MSG_POSTVIEW_FRAME);
}

// picture callback - raw image ready
void CameraService::Client::handleRawPicture(const sp<IMemory>& mem)
{
    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
#if DEBUG_HEAP_LEAKS && 0 // debugging
    gWeakHeap = heap; // debugging
#endif

    //LOGV("handleRawPicture(%d, %d)", offset, size);
#if DEBUG_DUMP_YUV_SNAPSHOT_TO_FILE // for testing pursposes only
    dump_to_file("/data/photo.yuv",
                 (uint8_t *)heap->base() + offset, size);
#endif

    // Put the YUV version of the snapshot in the preview display.
    if (mSurface != 0 && !mUseOverlay) {
        mSurface->postBuffer(offset);
    }

    sp<ICameraClient> c = mCameraClient;
    if (c != NULL) {
        c->dataCallback(CAMERA_MSG_RAW_IMAGE, mem);
    }
    mHardware->disableMsgType(CAMERA_MSG_RAW_IMAGE);
}

// picture callback - compressed picture ready
void CameraService::Client::handleCompressedPicture(const sp<IMemory>& mem)
{
#if DEBUG_DUMP_JPEG_SNAPSHOT_TO_FILE // for testing pursposes only
    {
        ssize_t offset;
        size_t size;
        sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
        dump_to_file("/data/photo.jpg",
                     (uint8_t *)heap->base() + offset, size);
    }
#endif

    sp<ICameraClient> c = mCameraClient;
    if (c != NULL) {
        c->dataCallback(CAMERA_MSG_COMPRESSED_IMAGE, mem);
    }
    mHardware->disableMsgType(CAMERA_MSG_COMPRESSED_IMAGE);
}

void CameraService::Client::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2, void* user)
{
    LOGV("notifyCallback(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) {
        return;
    }

    switch (msgType) {
        case CAMERA_MSG_SHUTTER:
            // ext1 is the dimension of the yuv picture.
            client->handleShutter((image_rect_type *)ext1);
            break;
        default:
            sp<ICameraClient> c = client->mCameraClient;
            if (c != NULL) {
                c->notifyCallback(msgType, ext1, ext2);
            }
            break;
    }

#if DEBUG_CLIENT_REFERENCES
    if (client->getStrongCount() == 1) {
        LOGE("++++++++++++++++ (NOTIFY CALLBACK) THIS WILL CAUSE A LOCKUP!");
        client->printRefs();
    }
#endif
}

void CameraService::Client::dataCallback(int32_t msgType, const sp<IMemory>& dataPtr, void* user)
{
    LOGV("dataCallback(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) {
        return;
    }

    sp<ICameraClient> c = client->mCameraClient;
    if (dataPtr == NULL) {
        LOGE("Null data returned in data callback");
        if (c != NULL) {
            c->notifyCallback(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
            c->dataCallback(msgType, NULL);
        }
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
            if (c != NULL) {
                c->dataCallback(msgType, dataPtr);
            }
            break;
    }

#if DEBUG_CLIENT_REFERENCES
    if (client->getStrongCount() == 1) {
        LOGE("++++++++++++++++ (DATA CALLBACK) THIS WILL CAUSE A LOCKUP!");
        client->printRefs();
    }
#endif
}

void CameraService::Client::dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType,
                                                  const sp<IMemory>& dataPtr, void* user)
{
    LOGV("dataCallbackTimestamp(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) {
        return;
    }
    sp<ICameraClient> c = client->mCameraClient;

    if (dataPtr == NULL) {
        LOGE("Null data returned in data with timestamp callback");
        if (c != NULL) {
            c->notifyCallback(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
            c->dataCallbackTimestamp(0, msgType, NULL);
        }
        return;
    }

    if (c != NULL) {
        c->dataCallbackTimestamp(timestamp, msgType, dataPtr);
    }

#if DEBUG_CLIENT_REFERENCES
    if (client->getStrongCount() == 1) {
        LOGE("++++++++++++++++ (DATA CALLBACK TIMESTAMP) THIS WILL CAUSE A LOCKUP!");
        client->printRefs();
    }
#endif
}

// set preview/capture parameters - key/value pairs
status_t CameraService::Client::setParameters(const String8& params)
{
    LOGV("setParameters(%s)", params.string());

    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
    if (result != NO_ERROR) return result;

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    CameraParameters p(params);

    return mHardware->setParameters(p);
}

// get preview/capture parameters - key/value pairs
String8 CameraService::Client::getParameters() const
{
    Mutex::Autolock lock(mLock);

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return String8();
    }

    String8 params(mHardware->getParameters().flatten());
    LOGV("getParameters(%s)", params.string());
    return params;
}

status_t CameraService::Client::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
{
    LOGV("sendCommand (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPid();
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

    if (mHardware == 0) {
        LOGE("mHardware is NULL, returning.");
        return INVALID_OPERATION;
    }

    return mHardware->sendCommand(cmd, arg1, arg2);
}

void CameraService::Client::copyFrameAndPostCopiedFrame(const sp<ICameraClient>& client,
        const sp<IMemoryHeap>& heap, size_t offset, size_t size)
{
    LOGV("copyFrameAndPostCopiedFrame");
    // It is necessary to copy out of pmem before sending this to
    // the callback. For efficiency, reuse the same MemoryHeapBase
    // provided it's big enough. Don't allocate the memory or
    // perform the copy if there's no callback.

    // hold the preview lock while we grab a reference to the preview buffer
    sp<MemoryHeapBase> previewBuffer;
    {
        Mutex::Autolock lock(mPreviewLock);
        if (mPreviewBuffer == 0) {
            mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
        } else if (size > mPreviewBuffer->virtualSize()) {
            mPreviewBuffer.clear();
            mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
        }
        if (mPreviewBuffer == 0) {
            LOGE("failed to allocate space for preview buffer");
            return;
        }
        previewBuffer = mPreviewBuffer;
    }
    memcpy(previewBuffer->base(),
           (uint8_t *)heap->base() + offset, size);

    sp<MemoryBase> frame = new MemoryBase(previewBuffer, 0, size);
    if (frame == 0) {
        LOGE("failed to allocate space for frame callback");
        return;
    }
    client->dataCallback(CAMERA_MSG_PREVIEW_FRAME, frame);
}

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

status_t CameraService::dump(int fd, const Vector<String16>& args)
{
    static const char* kDeadlockedString = "CameraService may be deadlocked\n";

    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump CameraService from pid=%d, uid=%d\n",
                getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
        write(fd, result.string(), result.size());
    } else {
        bool locked = tryLock(mServiceLock);
        // failed to lock - CameraService is probably deadlocked
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        if (mClient != 0) {
            sp<Client> currentClient = mClient.promote();
            sprintf(buffer, "Client (%p) PID: %d\n",
                    currentClient->getCameraClient()->asBinder().get(),
                    currentClient->mClientPid);
            result.append(buffer);
            write(fd, result.string(), result.size());
            currentClient->mHardware->dump(fd, args);
        } else {
            result.append("No camera client yet.\n");
            write(fd, result.string(), result.size());
        }

        if (locked) mServiceLock.unlock();
    }
    return NO_ERROR;
}


status_t CameraService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // permission checks...
    switch (code) {
        case BnCameraService::CONNECT:
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int self_pid = getpid();
            if (pid != self_pid) {
                // we're called from a different process, do the real check
                if (!checkCallingPermission(
                        String16("android.permission.CAMERA")))
                {
                    const int uid = ipc->getCallingUid();
                    LOGE("Permission Denial: "
                            "can't use the camera pid=%d, uid=%d", pid, uid);
                    return PERMISSION_DENIED;
                }
            }
            break;
    }

    status_t err = BnCameraService::onTransact(code, data, reply, flags);

#if DEBUG_HEAP_LEAKS
    LOGV("+++ onTransact err %d code %d", err, code);

    if (err == UNKNOWN_TRANSACTION || err == PERMISSION_DENIED) {
        // the 'service' command interrogates this binder for its name, and then supplies it
        // even for the debugging commands.  that means we need to check for it here, using
        // ISurfaceComposer (since we delegated the INTERFACE_TRANSACTION handling to
        // BnSurfaceComposer before falling through to this code).

        LOGV("+++ onTransact code %d", code);

        CHECK_INTERFACE(ICameraService, data, reply);

        switch(code) {
        case 1000:
        {
            if (gWeakHeap != 0) {
                sp<IMemoryHeap> h = gWeakHeap.promote();
                IMemoryHeap *p = gWeakHeap.unsafe_get();
                LOGV("CHECKING WEAK REFERENCE %p (%p)", h.get(), p);
                if (h != 0)
                    h->printRefs();
                bool attempt_to_delete = data.readInt32() == 1;
                if (attempt_to_delete) {
                    // NOT SAFE!
                    LOGV("DELETING WEAK REFERENCE %p (%p)", h.get(), p);
                    if (p) delete p;
                }
                return NO_ERROR;
            }
        }
        break;
        default:
            break;
        }
    }
#endif // DEBUG_HEAP_LEAKS

    return err;
}

}; // namespace android
