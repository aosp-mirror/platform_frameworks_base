/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "CameraSource"
#include <utils/Log.h>

#include <OMX_Component.h>
#include <binder/IPCThreadState.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <surfaceflinger/Surface.h>
#include <utils/String8.h>
#include <cutils/properties.h>

namespace android {

struct CameraSourceListener : public CameraListener {
    CameraSourceListener(const sp<CameraSource> &source);

    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void postData(int32_t msgType, const sp<IMemory> &dataPtr);

    virtual void postDataTimestamp(
            nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

protected:
    virtual ~CameraSourceListener();

private:
    wp<CameraSource> mSource;

    CameraSourceListener(const CameraSourceListener &);
    CameraSourceListener &operator=(const CameraSourceListener &);
};

CameraSourceListener::CameraSourceListener(const sp<CameraSource> &source)
    : mSource(source) {
}

CameraSourceListener::~CameraSourceListener() {
}

void CameraSourceListener::notify(int32_t msgType, int32_t ext1, int32_t ext2) {
    LOGV("notify(%d, %d, %d)", msgType, ext1, ext2);
}

void CameraSourceListener::postData(int32_t msgType, const sp<IMemory> &dataPtr) {
    LOGV("postData(%d, ptr:%p, size:%d)",
         msgType, dataPtr->pointer(), dataPtr->size());

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallback(msgType, dataPtr);
    }
}

void CameraSourceListener::postDataTimestamp(
        nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) {

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallbackTimestamp(timestamp/1000, msgType, dataPtr);
    }
}

static int32_t getColorFormat(const char* colorFormat) {
    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV420P)) {
       return OMX_COLOR_FormatYUV420Planar;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422SP)) {
       return OMX_COLOR_FormatYUV422SemiPlanar;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV420SP)) {
        return OMX_COLOR_FormatYUV420SemiPlanar;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422I)) {
        return OMX_COLOR_FormatYCbYCr;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_RGB565)) {
       return OMX_COLOR_Format16bitRGB565;
    }

    LOGE("Uknown color format (%s), please add it to "
         "CameraSource::getColorFormat", colorFormat);

    CHECK_EQ(0, "Unknown color format");
}

CameraSource *CameraSource::Create() {
    Size size;
    size.width = -1;
    size.height = -1;

    sp<ICamera> camera;
    return new CameraSource(camera, 0, size, -1, NULL, false);
}

// static
CameraSource *CameraSource::CreateFromCamera(
    const sp<ICamera>& camera,
    int32_t cameraId,
    Size videoSize,
    int32_t frameRate,
    const sp<Surface>& surface,
    bool storeMetaDataInVideoBuffers) {

    CameraSource *source = new CameraSource(camera, cameraId,
                    videoSize, frameRate, surface,
                    storeMetaDataInVideoBuffers);
    return source;
}

CameraSource::CameraSource(
    const sp<ICamera>& camera,
    int32_t cameraId,
    Size videoSize,
    int32_t frameRate,
    const sp<Surface>& surface,
    bool storeMetaDataInVideoBuffers)
    : mCameraFlags(0),
      mVideoFrameRate(-1),
      mCamera(0),
      mSurface(surface),
      mNumFramesReceived(0),
      mLastFrameTimestampUs(0),
      mStarted(false),
      mFirstFrameTimeUs(0),
      mNumFramesEncoded(0),
      mNumFramesDropped(0),
      mNumGlitches(0),
      mGlitchDurationThresholdUs(200000),
      mCollectStats(false) {

    mVideoSize.width  = -1;
    mVideoSize.height = -1;

    mInitCheck = init(camera, cameraId,
                    videoSize, frameRate,
                    storeMetaDataInVideoBuffers);
}

status_t CameraSource::initCheck() const {
    return mInitCheck;
}

status_t CameraSource::isCameraAvailable(
    const sp<ICamera>& camera, int32_t cameraId) {

    if (camera == 0) {
        mCamera = Camera::connect(cameraId);
        mCameraFlags &= ~FLAGS_HOT_CAMERA;
    } else {
        mCamera = Camera::create(camera);
        mCameraFlags |= FLAGS_HOT_CAMERA;
    }

    // Is camera available?
    if (mCamera == 0) {
        LOGE("Camera connection could not be established.");
        return -EBUSY;
    }
    if (!(mCameraFlags & FLAGS_HOT_CAMERA)) {
        mCamera->lock();
    }
    return OK;
}


/*
 * Check to see whether the requested video width and height is one
 * of the supported sizes.
 * @param width the video frame width in pixels
 * @param height the video frame height in pixels
 * @param suppportedSizes the vector of sizes that we check against
 * @return true if the dimension (width and height) is supported.
 */
static bool isVideoSizeSupported(
    int32_t width, int32_t height,
    const Vector<Size>& supportedSizes) {

    LOGV("isVideoSizeSupported");
    for (size_t i = 0; i < supportedSizes.size(); ++i) {
        if (width  == supportedSizes[i].width &&
            height == supportedSizes[i].height) {
            return true;
        }
    }
    return false;
}

/*
 * If the preview and video output is separate, we only set the
 * the video size, and applications should set the preview size
 * to some proper value, and the recording framework will not
 * change the preview size; otherwise, if the video and preview
 * output is the same, we need to set the preview to be the same
 * as the requested video size.
 *
 */
/*
 * Query the camera to retrieve the supported video frame sizes
 * and also to see whether CameraParameters::setVideoSize()
 * is supported or not.
 * @param params CameraParameters to retrieve the information
 * @@param isSetVideoSizeSupported retunrs whether method
 *      CameraParameters::setVideoSize() is supported or not.
 * @param sizes returns the vector of Size objects for the
 *      supported video frame sizes advertised by the camera.
 */
static void getSupportedVideoSizes(
    const CameraParameters& params,
    bool *isSetVideoSizeSupported,
    Vector<Size>& sizes) {

    *isSetVideoSizeSupported = true;
    params.getSupportedVideoSizes(sizes);
    if (sizes.size() == 0) {
        LOGD("Camera does not support setVideoSize()");
        params.getSupportedPreviewSizes(sizes);
        *isSetVideoSizeSupported = false;
    }
}

/*
 * Check whether the camera has the supported color format
 * @param params CameraParameters to retrieve the information
 * @return OK if no error.
 */
status_t CameraSource::isCameraColorFormatSupported(
        const CameraParameters& params) {
    mColorFormat = getColorFormat(params.get(
            CameraParameters::KEY_VIDEO_FRAME_FORMAT));
    if (mColorFormat == -1) {
        return BAD_VALUE;
    }
    return OK;
}

/*
 * Configure the camera to use the requested video size
 * (width and height) and/or frame rate. If both width and
 * height are -1, configuration on the video size is skipped.
 * if frameRate is -1, configuration on the frame rate
 * is skipped. Skipping the configuration allows one to
 * use the current camera setting without the need to
 * actually know the specific values (see Create() method).
 *
 * @param params the CameraParameters to be configured
 * @param width the target video frame width in pixels
 * @param height the target video frame height in pixels
 * @param frameRate the target frame rate in frames per second.
 * @return OK if no error.
 */
status_t CameraSource::configureCamera(
        CameraParameters* params,
        int32_t width, int32_t height,
        int32_t frameRate) {

    Vector<Size> sizes;
    bool isSetVideoSizeSupportedByCamera = true;
    getSupportedVideoSizes(*params, &isSetVideoSizeSupportedByCamera, sizes);
    bool isCameraParamChanged = false;
    if (width != -1 && height != -1) {
        if (!isVideoSizeSupported(width, height, sizes)) {
            LOGE("Video dimension (%dx%d) is unsupported", width, height);
            releaseCamera();
            return BAD_VALUE;
        }
        if (isSetVideoSizeSupportedByCamera) {
            params->setVideoSize(width, height);
        } else {
            params->setPreviewSize(width, height);
        }
        isCameraParamChanged = true;
    } else if ((width == -1 && height != -1) ||
               (width != -1 && height == -1)) {
        // If one and only one of the width and height is -1
        // we reject such a request.
        LOGE("Requested video size (%dx%d) is not supported", width, height);
        releaseCamera();
        return BAD_VALUE;
    } else {  // width == -1 && height == -1
        // Do not configure the camera.
        // Use the current width and height value setting from the camera.
    }

    if (frameRate != -1) {
        CHECK(frameRate > 0 && frameRate <= 120);
        const char* supportedFrameRates =
                params->get(CameraParameters::KEY_SUPPORTED_PREVIEW_FRAME_RATES);
        CHECK(supportedFrameRates != NULL);
        LOGV("Supported frame rates: %s", supportedFrameRates);
        char buf[4];
        snprintf(buf, 4, "%d", frameRate);
        if (strstr(supportedFrameRates, buf) == NULL) {
            LOGE("Requested frame rate (%d) is not supported: %s",
                frameRate, supportedFrameRates);
            releaseCamera();
            return BAD_VALUE;
        }

        // The frame rate is supported, set the camera to the requested value.
        params->setPreviewFrameRate(frameRate);
        isCameraParamChanged = true;
    } else {  // frameRate == -1
        // Do not configure the camera.
        // Use the current frame rate value setting from the camera
    }

    if (isCameraParamChanged) {
        // Either frame rate or frame size needs to be changed.
        String8 s = params->flatten();
        if (OK != mCamera->setParameters(s)) {
            LOGE("Could not change settings."
                 " Someone else is using camera %p?", mCamera.get());
            return -EBUSY;
        }
    }
    return OK;
}

/*
 * Check whether the requested video frame size
 * has been successfully configured or not. If both width and height
 * are -1, check on the current width and height value setting
 * is performed.
 *
 * @param params CameraParameters to retrieve the information
 * @param the target video frame width in pixels to check against
 * @param the target video frame height in pixels to check against
 * @return OK if no error
 */
status_t CameraSource::checkVideoSize(
        const CameraParameters& params,
        int32_t width, int32_t height) {

    // The actual video size is the same as the preview size
    // if the camera hal does not support separate video and
    // preview output. In this case, we retrieve the video
    // size from preview.
    int32_t frameWidthActual = -1;
    int32_t frameHeightActual = -1;
    Vector<Size> sizes;
    params.getSupportedVideoSizes(sizes);
    if (sizes.size() == 0) {
        // video size is the same as preview size
        params.getPreviewSize(&frameWidthActual, &frameHeightActual);
    } else {
        // video size may not be the same as preview
        params.getVideoSize(&frameWidthActual, &frameHeightActual);
    }
    if (frameWidthActual < 0 || frameHeightActual < 0) {
        LOGE("Failed to retrieve video frame size (%dx%d)",
                frameWidthActual, frameHeightActual);
        return UNKNOWN_ERROR;
    }

    // Check the actual video frame size against the target/requested
    // video frame size.
    if (width != -1 && height != -1) {
        if (frameWidthActual != width || frameHeightActual != height) {
            LOGE("Failed to set video frame size to %dx%d. "
                    "The actual video size is %dx%d ", width, height,
                    frameWidthActual, frameHeightActual);
            return UNKNOWN_ERROR;
        }
    }

    // Good now.
    mVideoSize.width = frameWidthActual;
    mVideoSize.height = frameHeightActual;
    return OK;
}

/*
 * Check the requested frame rate has been successfully configured or not.
 * If the target frameRate is -1, check on the current frame rate value
 * setting is performed.
 *
 * @param params CameraParameters to retrieve the information
 * @param the target video frame rate to check against
 * @return OK if no error.
 */
status_t CameraSource::checkFrameRate(
        const CameraParameters& params,
        int32_t frameRate) {

    int32_t frameRateActual = params.getPreviewFrameRate();
    if (frameRateActual < 0) {
        LOGE("Failed to retrieve preview frame rate (%d)", frameRateActual);
        return UNKNOWN_ERROR;
    }

    // Check the actual video frame rate against the target/requested
    // video frame rate.
    if (frameRate != -1 && (frameRateActual - frameRate) != 0) {
        LOGE("Failed to set preview frame rate to %d fps. The actual "
                "frame rate is %d", frameRate, frameRateActual);
        return UNKNOWN_ERROR;
    }

    // Good now.
    mVideoFrameRate = frameRateActual;
    return OK;
}

/*
 * Initialize the CameraSource to so that it becomes
 * ready for providing the video input streams as requested.
 * @param camera the camera object used for the video source
 * @param cameraId if camera == 0, use camera with this id
 *      as the video source
 * @param videoSize the target video frame size. If both
 *      width and height in videoSize is -1, use the current
 *      width and heigth settings by the camera
 * @param frameRate the target frame rate in frames per second.
 *      if it is -1, use the current camera frame rate setting.
 * @param storeMetaDataInVideoBuffers request to store meta
 *      data or real YUV data in video buffers. Request to
 *      store meta data in video buffers may not be honored
 *      if the source does not support this feature.
 *
 * @return OK if no error.
 */
status_t CameraSource::init(
        const sp<ICamera>& camera,
        int32_t cameraId,
        Size videoSize,
        int32_t frameRate,
        bool storeMetaDataInVideoBuffers) {

    status_t err = OK;
    int64_t token = IPCThreadState::self()->clearCallingIdentity();

    if ((err  = isCameraAvailable(camera, cameraId)) != OK) {
        return err;
    }
    CameraParameters params(mCamera->getParameters());
    if ((err = isCameraColorFormatSupported(params)) != OK) {
        return err;
    }

    // Set the camera to use the requested video frame size
    // and/or frame rate.
    if ((err = configureCamera(&params,
                    videoSize.width, videoSize.height,
                    frameRate))) {
        return err;
    }

    // Check on video frame size and frame rate.
    CameraParameters newCameraParams(mCamera->getParameters());
    if ((err = checkVideoSize(newCameraParams,
                videoSize.width, videoSize.height)) != OK) {
        return err;
    }
    if ((err = checkFrameRate(newCameraParams, frameRate)) != OK) {
        return err;
    }

    // This CHECK is good, since we just passed the lock/unlock
    // check earlier by calling mCamera->setParameters().
    CHECK_EQ(OK, mCamera->setPreviewDisplay(mSurface));

    mIsMetaDataStoredInVideoBuffers = false;
    if (storeMetaDataInVideoBuffers &&
        OK == mCamera->storeMetaDataInBuffers(true)) {
        mIsMetaDataStoredInVideoBuffers = true;
    }

    IPCThreadState::self()->restoreCallingIdentity(token);

    int64_t glitchDurationUs = (1000000LL / mVideoFrameRate);
    if (glitchDurationUs > mGlitchDurationThresholdUs) {
        mGlitchDurationThresholdUs = glitchDurationUs;
    }

    // XXX: query camera for the stride and slice height
    // when the capability becomes available.
    mMeta = new MetaData;
    mMeta->setCString(kKeyMIMEType,  MEDIA_MIMETYPE_VIDEO_RAW);
    mMeta->setInt32(kKeyColorFormat, mColorFormat);
    mMeta->setInt32(kKeyWidth,       mVideoSize.width);
    mMeta->setInt32(kKeyHeight,      mVideoSize.height);
    mMeta->setInt32(kKeyStride,      mVideoSize.width);
    mMeta->setInt32(kKeySliceHeight, mVideoSize.height);
    mMeta->setInt32(kKeyFrameRate,   mVideoFrameRate);
    return OK;
}

CameraSource::~CameraSource() {
    if (mStarted) {
        stop();
    }
}

void CameraSource::startCameraRecording() {
    CHECK_EQ(OK, mCamera->startRecording());
    CHECK(mCamera->recordingEnabled());
}

status_t CameraSource::start(MetaData *meta) {
    CHECK(!mStarted);
    if (mInitCheck != OK) {
        LOGE("CameraSource is not initialized yet");
        return mInitCheck;
    }

    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-stats", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        mCollectStats = true;
    }

    mStartTimeUs = 0;
    int64_t startTimeUs;
    if (meta && meta->findInt64(kKeyTime, &startTimeUs)) {
        mStartTimeUs = startTimeUs;
    }

    // Call setListener first before calling startCameraRecording()
    // to avoid recording frames being dropped.
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    mCamera->setListener(new CameraSourceListener(this));
    startCameraRecording();
    IPCThreadState::self()->restoreCallingIdentity(token);

    mStarted = true;
    return OK;
}

void CameraSource::stopCameraRecording() {
    mCamera->setListener(NULL);
    mCamera->stopRecording();
}

void CameraSource::releaseCamera() {
    LOGV("releaseCamera");
    if ((mCameraFlags & FLAGS_HOT_CAMERA) == 0) {
        LOGV("Camera was cold when we started, stopping preview");
        mCamera->stopPreview();
    }
    mCamera->unlock();
    mCamera.clear();
    mCamera = 0;
    mCameraFlags = 0;
}

status_t CameraSource::stop() {
    LOGV("stop");
    Mutex::Autolock autoLock(mLock);
    mStarted = false;
    mFrameAvailableCondition.signal();

    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    releaseQueuedFrames();
    while (!mFramesBeingEncoded.empty()) {
        LOGI("Waiting for outstanding frames being encoded: %d",
                mFramesBeingEncoded.size());
        mFrameCompleteCondition.wait(mLock);
    }
    stopCameraRecording();
    releaseCamera();
    IPCThreadState::self()->restoreCallingIdentity(token);

    if (mCollectStats) {
        LOGI("Frames received/encoded/dropped: %d/%d/%d in %lld us",
                mNumFramesReceived, mNumFramesEncoded, mNumFramesDropped,
                mLastFrameTimestampUs - mFirstFrameTimeUs);
    }

    if (mNumGlitches > 0) {
        LOGW("%d long delays between neighboring video frames during",
                mNumGlitches);
    }

    CHECK_EQ(mNumFramesReceived, mNumFramesEncoded + mNumFramesDropped);
    return OK;
}

void CameraSource::releaseRecordingFrame(const sp<IMemory>& frame) {
    if (mCamera != NULL) {
        mCamera->releaseRecordingFrame(frame);
    }
}

void CameraSource::releaseQueuedFrames() {
    List<sp<IMemory> >::iterator it;
    while (!mFramesReceived.empty()) {
        it = mFramesReceived.begin();
        releaseRecordingFrame(*it);
        mFramesReceived.erase(it);
        ++mNumFramesDropped;
    }
}

sp<MetaData> CameraSource::getFormat() {
    return mMeta;
}

void CameraSource::releaseOneRecordingFrame(const sp<IMemory>& frame) {
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    releaseRecordingFrame(frame);
    IPCThreadState::self()->restoreCallingIdentity(token);
}

void CameraSource::signalBufferReturned(MediaBuffer *buffer) {
    LOGV("signalBufferReturned: %p", buffer->data());
    Mutex::Autolock autoLock(mLock);
    for (List<sp<IMemory> >::iterator it = mFramesBeingEncoded.begin();
         it != mFramesBeingEncoded.end(); ++it) {
        if ((*it)->pointer() ==  buffer->data()) {
            releaseOneRecordingFrame((*it));
            mFramesBeingEncoded.erase(it);
            ++mNumFramesEncoded;
            buffer->setObserver(0);
            buffer->release();
            mFrameCompleteCondition.signal();
            return;
        }
    }
    CHECK_EQ(0, "signalBufferReturned: bogus buffer");
}

status_t CameraSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    LOGV("read");

    *buffer = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        return ERROR_UNSUPPORTED;
    }

    sp<IMemory> frame;
    int64_t frameTime;

    {
        Mutex::Autolock autoLock(mLock);
        while (mStarted) {
            while(mFramesReceived.empty()) {
                mFrameAvailableCondition.wait(mLock);
            }

            if (!mStarted) {
                return OK;
            }

            frame = *mFramesReceived.begin();
            mFramesReceived.erase(mFramesReceived.begin());

            frameTime = *mFrameTimes.begin();
            mFrameTimes.erase(mFrameTimes.begin());
            int64_t skipTimeUs;
            if (!options || !options->getSkipFrame(&skipTimeUs)) {
                skipTimeUs = frameTime;
            }
            if (skipTimeUs > frameTime) {
                LOGV("skipTimeUs: %lld us > frameTime: %lld us",
                    skipTimeUs, frameTime);
                releaseOneRecordingFrame(frame);
                ++mNumFramesDropped;
                // Safeguard against the abuse of the kSkipFrame_Option.
                if (skipTimeUs - frameTime >= 1E6) {
                    LOGE("Frame skipping requested is way too long: %lld us",
                        skipTimeUs - frameTime);
                    return UNKNOWN_ERROR;
                }
            } else {
                mFramesBeingEncoded.push_back(frame);
                *buffer = new MediaBuffer(frame->pointer(), frame->size());
                (*buffer)->setObserver(this);
                (*buffer)->add_ref();
                (*buffer)->meta_data()->setInt64(kKeyTime, frameTime);

                return OK;
            }
        }
    }
    return OK;
}

void CameraSource::dataCallbackTimestamp(int64_t timestampUs,
        int32_t msgType, const sp<IMemory> &data) {
    LOGV("dataCallbackTimestamp: timestamp %lld us", timestampUs);
    Mutex::Autolock autoLock(mLock);
    if (!mStarted) {
        releaseOneRecordingFrame(data);
        ++mNumFramesReceived;
        ++mNumFramesDropped;
        return;
    }

    if (mNumFramesReceived > 0 &&
        timestampUs - mLastFrameTimestampUs > mGlitchDurationThresholdUs) {
        if (mNumGlitches % 10 == 0) {  // Don't spam the log
            LOGV("Long delay detected in video recording");
        }
        ++mNumGlitches;
    }

    // May need to skip frame or modify timestamp. Currently implemented
    // by the subclass CameraSourceTimeLapse.
    if(skipCurrentFrame(timestampUs)) {
        releaseOneRecordingFrame(data);
        return;
    }

    mLastFrameTimestampUs = timestampUs;
    if (mNumFramesReceived == 0) {
        mFirstFrameTimeUs = timestampUs;
        // Initial delay
        if (mStartTimeUs > 0) {
            if (timestampUs < mStartTimeUs) {
                // Frame was captured before recording was started
                // Drop it without updating the statistical data.
                releaseOneRecordingFrame(data);
                return;
            }
            mStartTimeUs = timestampUs - mStartTimeUs;
        }
    }
    ++mNumFramesReceived;

    mFramesReceived.push_back(data);
    int64_t timeUs = mStartTimeUs + (timestampUs - mFirstFrameTimeUs);
    mFrameTimes.push_back(timeUs);
    LOGV("initial delay: %lld, current time stamp: %lld",
        mStartTimeUs, timeUs);
    mFrameAvailableCondition.signal();
}

size_t CameraSource::getNumberOfVideoBuffers() const {
    LOGV("getNumberOfVideoBuffers");
    size_t nBuffers = 0;
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    if (mInitCheck == OK && mCamera != 0) {
        nBuffers = mCamera->getNumberOfVideoBuffers();
    }
    IPCThreadState::self()->restoreCallingIdentity(token);
    return nBuffers;
}

sp<IMemory> CameraSource::getVideoBuffer(size_t index) const {
    LOGV("getVideoBuffer: %d", index);
    sp<IMemory> buffer = 0;
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    if (mInitCheck == OK && mCamera != 0) {
        buffer = mCamera->getVideoBuffer(index);
    }
    IPCThreadState::self()->restoreCallingIdentity(token);
    return buffer;
}

bool CameraSource::isMetaDataStoredInVideoBuffers() const {
    LOGV("isMetaDataStoredInVideoBuffers");
    return mIsMetaDataStoredInVideoBuffers;
}

}  // namespace android
