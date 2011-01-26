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

#ifndef CAMERA_SOURCE_H_

#define CAMERA_SOURCE_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <camera/ICamera.h>
#include <camera/CameraParameters.h>
#include <utils/List.h>
#include <utils/RefBase.h>

namespace android {

class IMemory;
class Camera;
class Surface;

class CameraSource : public MediaSource, public MediaBufferObserver {
public:
    /**
     * Factory method to create a new CameraSource using the current
     * settings (such as video size, frame rate, color format, etc)
     * from the default camera.
     *
     * @return NULL on error.
     */
    static CameraSource *Create();

    /**
     * Factory method to create a new CameraSource.
     *
     * @param camera the video input frame data source. If it is NULL,
     *          we will try to connect to the camera with the given
     *          cameraId.
     *
     * @param cameraId the id of the camera that the source will connect
     *          to if camera is NULL; otherwise ignored.
     *
     * @param videoSize the dimension (in pixels) of the video frame
     * @param frameRate the target frames per second
     * @param surface the preview surface for display where preview
     *          frames are sent to
     * @param storeMetaDataInVideoBuffers true to request the camera
     *          source to store meta data in video buffers; false to
     *          request the camera source to store real YUV frame data
     *          in the video buffers. The camera source may not support
     *          storing meta data in video buffers, if so, a request
     *          to do that will NOT be honored. To find out whether
     *          meta data is actually being stored in video buffers
     *          during recording, call isMetaDataStoredInVideoBuffers().
     *
     * @return NULL on error.
     */
    static CameraSource *CreateFromCamera(const sp<ICamera> &camera,
                                          int32_t cameraId,
                                          Size videoSize,
                                          int32_t frameRate,
                                          const sp<Surface>& surface,
                                          bool storeMetaDataInVideoBuffers = false);

    virtual ~CameraSource();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    /**
     * Check whether a CameraSource object is properly initialized.
     * Must call this method before stop().
     * @return OK if initialization has successfully completed.
     */
    virtual status_t initCheck() const;

    /**
     * Returns the MetaData associated with the CameraSource,
     * including:
     * kKeyColorFormat: YUV color format of the video frames
     * kKeyWidth, kKeyHeight: dimension (in pixels) of the video frames
     * kKeySampleRate: frame rate in frames per second
     * kKeyMIMEType: always fixed to be MEDIA_MIMETYPE_VIDEO_RAW
     */
    virtual sp<MetaData> getFormat();

    /**
     * Retrieve the total number of video buffers available from
     * this source.
     *
     * This method is useful if these video buffers are used
     * for passing video frame data to other media components,
     * such as OMX video encoders, in order to eliminate the
     * memcpy of the data.
     *
     * @return the total numbner of video buffers. Returns 0 to
     *      indicate that this source does not make the video
     *      buffer information availalble.
     */
    size_t getNumberOfVideoBuffers() const;

    /**
     * Retrieve the individual video buffer available from
     * this source.
     *
     * @param index the index corresponding to the video buffer.
     *      Valid range of the index is [0, n], where n =
     *      getNumberOfVideoBuffers() - 1.
     *
     * @return the video buffer corresponding to the given index.
     *      If index is out of range, 0 should be returned.
     */
    sp<IMemory> getVideoBuffer(size_t index) const;

    /**
     * Tell whether this camera source stores meta data or real YUV
     * frame data in video buffers.
     *
     * @return true if meta data is stored in the video
     *      buffers; false if real YUV data is stored in
     *      the video buffers.
     */
    bool isMetaDataStoredInVideoBuffers() const;

    virtual void signalBufferReturned(MediaBuffer* buffer);

protected:
    enum CameraFlags {
        FLAGS_SET_CAMERA = 1L << 0,
        FLAGS_HOT_CAMERA = 1L << 1,
    };

    int32_t  mCameraFlags;
    Size     mVideoSize;
    int32_t  mVideoFrameRate;
    int32_t  mColorFormat;
    status_t mInitCheck;

    sp<Camera>   mCamera;
    sp<Surface>  mSurface;
    sp<MetaData> mMeta;

    int64_t mStartTimeUs;
    int32_t mNumFramesReceived;
    int64_t mLastFrameTimestampUs;
    bool mStarted;
    int32_t mNumFramesEncoded;

    CameraSource(const sp<ICamera>& camera, int32_t cameraId,
                 Size videoSize, int32_t frameRate,
                 const sp<Surface>& surface,
                 bool storeMetaDataInVideoBuffers);

    virtual void startCameraRecording();
    virtual void stopCameraRecording();
    virtual void releaseRecordingFrame(const sp<IMemory>& frame);

    // Returns true if need to skip the current frame.
    // Called from dataCallbackTimestamp.
    virtual bool skipCurrentFrame(int64_t timestampUs) {return false;}

    // Callback called when still camera raw data is available.
    virtual void dataCallback(int32_t msgType, const sp<IMemory> &data) {}

    virtual void dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data);

private:
    friend class CameraSourceListener;

    Mutex mLock;
    Condition mFrameAvailableCondition;
    Condition mFrameCompleteCondition;
    List<sp<IMemory> > mFramesReceived;
    List<sp<IMemory> > mFramesBeingEncoded;
    List<int64_t> mFrameTimes;

    int64_t mFirstFrameTimeUs;
    int32_t mNumFramesDropped;
    int32_t mNumGlitches;
    int64_t mGlitchDurationThresholdUs;
    bool mCollectStats;
    bool mIsMetaDataStoredInVideoBuffers;

    void releaseQueuedFrames();
    void releaseOneRecordingFrame(const sp<IMemory>& frame);


    status_t init(const sp<ICamera>& camera, int32_t cameraId,
                Size videoSize, int32_t frameRate,
                bool storeMetaDataInVideoBuffers);
    status_t isCameraAvailable(const sp<ICamera>& camera, int32_t cameraId);
    status_t isCameraColorFormatSupported(const CameraParameters& params);
    status_t configureCamera(CameraParameters* params,
                    int32_t width, int32_t height,
                    int32_t frameRate);

    status_t checkVideoSize(const CameraParameters& params,
                    int32_t width, int32_t height);

    status_t checkFrameRate(const CameraParameters& params,
                    int32_t frameRate);

    void releaseCamera();

    CameraSource(const CameraSource &);
    CameraSource &operator=(const CameraSource &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_H_
