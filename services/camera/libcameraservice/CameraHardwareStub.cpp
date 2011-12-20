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

#define LOG_TAG "CameraHardwareStub"
#include <utils/Log.h>

#include "CameraHardwareStub.h"
#include <utils/threads.h>
#include <fcntl.h>
#include <sys/mman.h>

#include "CannedJpeg.h"

namespace android {

CameraHardwareStub::CameraHardwareStub()
                  : mParameters(),
                    mPreviewHeap(0),
                    mRawHeap(0),
                    mFakeCamera(0),
                    mPreviewFrameSize(0),
                    mNotifyCb(0),
                    mDataCb(0),
                    mDataCbTimestamp(0),
                    mCallbackCookie(0),
                    mMsgEnabled(0),
                    mCurrentPreviewFrame(0)
{
    initDefaultParameters();
}

void CameraHardwareStub::initDefaultParameters()
{
    CameraParameters p;

    p.set(CameraParameters::KEY_SUPPORTED_PREVIEW_SIZES, "320x240");
    p.setPreviewSize(320, 240);
    p.setPreviewFrameRate(15);
    p.setPreviewFormat(CameraParameters::PIXEL_FORMAT_YUV420SP);

    p.set(CameraParameters::KEY_SUPPORTED_PICTURE_SIZES, "320x240");
    p.setPictureSize(320, 240);
    p.setPictureFormat(CameraParameters::PIXEL_FORMAT_JPEG);

    if (setParameters(p) != NO_ERROR) {
        LOGE("Failed to set default parameters?!");
    }
}

void CameraHardwareStub::initHeapLocked()
{
    // Create raw heap.
    int picture_width, picture_height;
    mParameters.getPictureSize(&picture_width, &picture_height);
    mRawHeap = new MemoryHeapBase(picture_width * picture_height * 3 / 2);

    int preview_width, preview_height;
    mParameters.getPreviewSize(&preview_width, &preview_height);
    ALOGD("initHeapLocked: preview size=%dx%d", preview_width, preview_height);

    // Note that we enforce yuv420sp in setParameters().
    int how_big = preview_width * preview_height * 3 / 2;

    // If we are being reinitialized to the same size as before, no
    // work needs to be done.
    if (how_big == mPreviewFrameSize)
        return;

    mPreviewFrameSize = how_big;

    // Make a new mmap'ed heap that can be shared across processes.
    // use code below to test with pmem
    mPreviewHeap = new MemoryHeapBase(mPreviewFrameSize * kBufferCount);
    // Make an IMemory for each frame so that we can reuse them in callbacks.
    for (int i = 0; i < kBufferCount; i++) {
        mBuffers[i] = new MemoryBase(mPreviewHeap, i * mPreviewFrameSize, mPreviewFrameSize);
    }

    // Recreate the fake camera to reflect the current size.
    delete mFakeCamera;
    mFakeCamera = new FakeCamera(preview_width, preview_height);
}

CameraHardwareStub::~CameraHardwareStub()
{
    delete mFakeCamera;
    mFakeCamera = 0; // paranoia
}

status_t CameraHardwareStub::setPreviewWindow(const sp<ANativeWindow>& buf)
{
    return NO_ERROR;
}

sp<IMemoryHeap> CameraHardwareStub::getRawHeap() const
{
    return mRawHeap;
}

void CameraHardwareStub::setCallbacks(notify_callback notify_cb,
                                      data_callback data_cb,
                                      data_callback_timestamp data_cb_timestamp,
                                      void* user)
{
    Mutex::Autolock lock(mLock);
    mNotifyCb = notify_cb;
    mDataCb = data_cb;
    mDataCbTimestamp = data_cb_timestamp;
    mCallbackCookie = user;
}

void CameraHardwareStub::enableMsgType(int32_t msgType)
{
    Mutex::Autolock lock(mLock);
    mMsgEnabled |= msgType;
}

void CameraHardwareStub::disableMsgType(int32_t msgType)
{
    Mutex::Autolock lock(mLock);
    mMsgEnabled &= ~msgType;
}

bool CameraHardwareStub::msgTypeEnabled(int32_t msgType)
{
    Mutex::Autolock lock(mLock);
    return (mMsgEnabled & msgType);
}

// ---------------------------------------------------------------------------

int CameraHardwareStub::previewThread()
{
    mLock.lock();
        // the attributes below can change under our feet...

        int previewFrameRate = mParameters.getPreviewFrameRate();

        // Find the offset within the heap of the current buffer.
        ssize_t offset = mCurrentPreviewFrame * mPreviewFrameSize;

        sp<MemoryHeapBase> heap = mPreviewHeap;

        // this assumes the internal state of fake camera doesn't change
        // (or is thread safe)
        FakeCamera* fakeCamera = mFakeCamera;

        sp<MemoryBase> buffer = mBuffers[mCurrentPreviewFrame];

    mLock.unlock();

    // TODO: here check all the conditions that could go wrong
    if (buffer != 0) {
        // Calculate how long to wait between frames.
        int delay = (int)(1000000.0f / float(previewFrameRate));

        // This is always valid, even if the client died -- the memory
        // is still mapped in our process.
        void *base = heap->base();

        // Fill the current frame with the fake camera.
        uint8_t *frame = ((uint8_t *)base) + offset;
        fakeCamera->getNextFrameAsYuv420(frame);

        //ALOGV("previewThread: generated frame to buffer %d", mCurrentPreviewFrame);

        // Notify the client of a new frame.
        if (mMsgEnabled & CAMERA_MSG_PREVIEW_FRAME)
            mDataCb(CAMERA_MSG_PREVIEW_FRAME, buffer, NULL, mCallbackCookie);

        // Advance the buffer pointer.
        mCurrentPreviewFrame = (mCurrentPreviewFrame + 1) % kBufferCount;

        // Wait for it...
        usleep(delay);
    }

    return NO_ERROR;
}

status_t CameraHardwareStub::startPreview()
{
    Mutex::Autolock lock(mLock);
    if (mPreviewThread != 0) {
        // already running
        return INVALID_OPERATION;
    }
    mPreviewThread = new PreviewThread(this);
    return NO_ERROR;
}

void CameraHardwareStub::stopPreview()
{
    sp<PreviewThread> previewThread;

    { // scope for the lock
        Mutex::Autolock lock(mLock);
        previewThread = mPreviewThread;
    }

    // don't hold the lock while waiting for the thread to quit
    if (previewThread != 0) {
        previewThread->requestExitAndWait();
    }

    Mutex::Autolock lock(mLock);
    mPreviewThread.clear();
}

bool CameraHardwareStub::previewEnabled() {
    return mPreviewThread != 0;
}

status_t CameraHardwareStub::startRecording()
{
    return UNKNOWN_ERROR;
}

void CameraHardwareStub::stopRecording()
{
}

bool CameraHardwareStub::recordingEnabled()
{
    return false;
}

void CameraHardwareStub::releaseRecordingFrame(const sp<IMemory>& mem)
{
}

// ---------------------------------------------------------------------------

int CameraHardwareStub::beginAutoFocusThread(void *cookie)
{
    CameraHardwareStub *c = (CameraHardwareStub *)cookie;
    return c->autoFocusThread();
}

int CameraHardwareStub::autoFocusThread()
{
    if (mMsgEnabled & CAMERA_MSG_FOCUS)
        mNotifyCb(CAMERA_MSG_FOCUS, true, 0, mCallbackCookie);
    return NO_ERROR;
}

status_t CameraHardwareStub::autoFocus()
{
    Mutex::Autolock lock(mLock);
    if (createThread(beginAutoFocusThread, this) == false)
        return UNKNOWN_ERROR;
    return NO_ERROR;
}

status_t CameraHardwareStub::cancelAutoFocus()
{
    return NO_ERROR;
}

/*static*/ int CameraHardwareStub::beginPictureThread(void *cookie)
{
    CameraHardwareStub *c = (CameraHardwareStub *)cookie;
    return c->pictureThread();
}

int CameraHardwareStub::pictureThread()
{
    if (mMsgEnabled & CAMERA_MSG_SHUTTER)
        mNotifyCb(CAMERA_MSG_SHUTTER, 0, 0, mCallbackCookie);

    if (mMsgEnabled & CAMERA_MSG_RAW_IMAGE) {
        //FIXME: use a canned YUV image!
        // In the meantime just make another fake camera picture.
        int w, h;
        mParameters.getPictureSize(&w, &h);
        sp<MemoryBase> mem = new MemoryBase(mRawHeap, 0, w * h * 3 / 2);
        FakeCamera cam(w, h);
        cam.getNextFrameAsYuv420((uint8_t *)mRawHeap->base());
        mDataCb(CAMERA_MSG_RAW_IMAGE, mem, NULL, mCallbackCookie);
    }

    if (mMsgEnabled & CAMERA_MSG_COMPRESSED_IMAGE) {
        sp<MemoryHeapBase> heap = new MemoryHeapBase(kCannedJpegSize);
        sp<MemoryBase> mem = new MemoryBase(heap, 0, kCannedJpegSize);
        memcpy(heap->base(), kCannedJpeg, kCannedJpegSize);
        mDataCb(CAMERA_MSG_COMPRESSED_IMAGE, mem, NULL, mCallbackCookie);
    }
    return NO_ERROR;
}

status_t CameraHardwareStub::takePicture()
{
    stopPreview();
    if (createThread(beginPictureThread, this) == false)
        return UNKNOWN_ERROR;
    return NO_ERROR;
}

status_t CameraHardwareStub::cancelPicture()
{
    return NO_ERROR;
}

status_t CameraHardwareStub::dump(int fd, const Vector<String16>& args) const
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    AutoMutex lock(&mLock);
    if (mFakeCamera != 0) {
        mFakeCamera->dump(fd);
        mParameters.dump(fd, args);
        snprintf(buffer, 255, " preview frame(%d), size (%d), running(%s)\n", mCurrentPreviewFrame, mPreviewFrameSize, mPreviewRunning?"true": "false");
        result.append(buffer);
    } else {
        result.append("No camera client yet.\n");
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t CameraHardwareStub::setParameters(const CameraParameters& params)
{
    Mutex::Autolock lock(mLock);
    // XXX verify params

    if (strcmp(params.getPreviewFormat(),
        CameraParameters::PIXEL_FORMAT_YUV420SP) != 0) {
        LOGE("Only yuv420sp preview is supported");
        return -1;
    }

    if (strcmp(params.getPictureFormat(),
        CameraParameters::PIXEL_FORMAT_JPEG) != 0) {
        LOGE("Only jpeg still pictures are supported");
        return -1;
    }

    int w, h;
    params.getPictureSize(&w, &h);
    if (w != kCannedJpegWidth && h != kCannedJpegHeight) {
        LOGE("Still picture size must be size of canned JPEG (%dx%d)",
             kCannedJpegWidth, kCannedJpegHeight);
        return -1;
    }

    mParameters = params;
    initHeapLocked();

    return NO_ERROR;
}

CameraParameters CameraHardwareStub::getParameters() const
{
    Mutex::Autolock lock(mLock);
    return mParameters;
}

status_t CameraHardwareStub::sendCommand(int32_t command, int32_t arg1,
                                         int32_t arg2)
{
    return BAD_VALUE;
}

void CameraHardwareStub::release()
{
}

sp<CameraHardwareInterface> CameraHardwareStub::createInstance()
{
    return new CameraHardwareStub();
}

static CameraInfo sCameraInfo[] = {
    {
        CAMERA_FACING_BACK,
        90,  /* orientation */
    }
};

extern "C" int HAL_getNumberOfCameras()
{
    return sizeof(sCameraInfo) / sizeof(sCameraInfo[0]);
}

extern "C" void HAL_getCameraInfo(int cameraId, struct CameraInfo* cameraInfo)
{
    memcpy(cameraInfo, &sCameraInfo[cameraId], sizeof(CameraInfo));
}

extern "C" sp<CameraHardwareInterface> HAL_openCameraHardware(int cameraId)
{
    return CameraHardwareStub::createInstance();
}

}; // namespace android
