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

#ifndef ANDROID_HARDWARE_CAMERA_HARDWARE_INTERFACE_H
#define ANDROID_HARDWARE_CAMERA_HARDWARE_INTERFACE_H

#include <binder/IMemory.h>
#include <ui/egl/android_natives.h>
#include <utils/RefBase.h>
#include <surfaceflinger/ISurface.h>
#include <ui/android_native_buffer.h>
#include <ui/GraphicBuffer.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>

namespace android {

/**
 *  The size of image for display.
 */
typedef struct image_rect_struct
{
  uint32_t width;      /* Image width */
  uint32_t height;     /* Image height */
} image_rect_type;


typedef void (*notify_callback)(int32_t msgType,
                                int32_t ext1,
                                int32_t ext2,
                                void* user);

typedef void (*data_callback)(int32_t msgType,
                              const sp<IMemory>& dataPtr,
                              void* user);

typedef void (*data_callback_timestamp)(nsecs_t timestamp,
                                        int32_t msgType,
                                        const sp<IMemory>& dataPtr,
                                        void* user);

/**
 * CameraHardwareInterface.h defines the interface to the
 * camera hardware abstraction layer, used for setting and getting
 * parameters, live previewing, and taking pictures.
 *
 * It is a referenced counted interface with RefBase as its base class.
 * CameraService calls openCameraHardware() to retrieve a strong pointer to the
 * instance of this interface and may be called multiple times. The
 * following steps describe a typical sequence:
 *
 *   -# After CameraService calls openCameraHardware(), getParameters() and
 *      setParameters() are used to initialize the camera instance.
 *      CameraService calls getPreviewHeap() to establish access to the
 *      preview heap so it can be registered with SurfaceFlinger for
 *      efficient display updating while in preview mode.
 *   -# startPreview() is called.  The camera instance then periodically
 *      sends the message CAMERA_MSG_PREVIEW_FRAME (if enabled) each time
 *      a new preview frame is available.  If data callback code needs to use
 *      this memory after returning, it must copy the data.
 *
 * Prior to taking a picture, CameraService calls autofocus(). When auto
 * focusing has completed, the camera instance sends a CAMERA_MSG_FOCUS notification,
 * which informs the application whether focusing was successful. The camera instance
 * only sends this message once and it is up  to the application to call autoFocus()
 * again if refocusing is desired.
 *
 * CameraService calls takePicture() to request the camera instance take a
 * picture. At this point, if a shutter, postview, raw, and/or compressed callback
 * is desired, the corresponding message must be enabled. As with CAMERA_MSG_PREVIEW_FRAME,
 * any memory provided in a data callback must be copied if it's needed after returning.
 */
class CameraHardwareInterface : public virtual RefBase {
public:
    virtual ~CameraHardwareInterface() { }

    /** Set the ANativeWindow to which preview frames are sent */
    virtual status_t setPreviewWindow(const sp<ANativeWindow>& buf) = 0;

    /** Return the IMemoryHeap for the raw image heap */
    virtual sp<IMemoryHeap>         getRawHeap() const = 0;

    /** Set the notification and data callbacks */
    virtual void setCallbacks(notify_callback notify_cb,
                              data_callback data_cb,
                              data_callback_timestamp data_cb_timestamp,
                              void* user) = 0;

    /**
     * The following three functions all take a msgtype,
     * which is a bitmask of the messages defined in
     * include/ui/Camera.h
     */

    /**
     * Enable a message, or set of messages.
     */
    virtual void        enableMsgType(int32_t msgType) = 0;

    /**
     * Disable a message, or a set of messages.
     *
     * Once received a call to disableMsgType(CAMERA_MSG_VIDEO_FRAME), camera hal
     * should not rely on its client to call releaseRecordingFrame() to release
     * video recording frames sent out by the cameral hal before and after the
     * disableMsgType(CAMERA_MSG_VIDEO_FRAME) call. Camera hal clients must not
     * modify/access any video recording frame after calling
     * disableMsgType(CAMERA_MSG_VIDEO_FRAME).
     */
    virtual void        disableMsgType(int32_t msgType) = 0;

    /**
     * Query whether a message, or a set of messages, is enabled.
     * Note that this is operates as an AND, if any of the messages
     * queried are off, this will return false.
     */
    virtual bool        msgTypeEnabled(int32_t msgType) = 0;

    /**
     * Start preview mode.
     */
    virtual status_t    startPreview() = 0;

    /**
     * Stop a previously started preview.
     */
    virtual void        stopPreview() = 0;

    /**
     * Returns true if preview is enabled.
     */
    virtual bool        previewEnabled() = 0;

    /**
     * Retrieve the total number of available buffers from camera hal for passing
     * video frame data in a recording session. Must be called again if a new
     * recording session is started.
     *
     * This method should be called after startRecording(), since
     * the some camera hal may choose to allocate the video buffers only after
     * recording is started.
     *
     * Some camera hal may not implement this method, and 0 can be returned to
     * indicate that this feature is not available.
     *
     * @return the number of video buffers that camera hal makes available.
     *      Zero (0) is returned to indicate that camera hal does not support
     *      this feature.
     */
    virtual int32_t     getNumberOfVideoBuffers() const { return 0; }

    /**
     * Retrieve the video buffer corresponding to the given index in a
     * recording session. Must be called again if a new recording session
     * is started.
     *
     * It allows a client to retrieve all video buffers that camera hal makes
     * available to passing video frame data by calling this method with all
     * valid index values. The valid index value ranges from 0 to n, where
     * n = getNumberOfVideoBuffers() - 1. With an index outside of the valid
     * range, 0 must be returned. This method should be called after
     * startRecording().
     *
     * The video buffers should NOT be modified/released by camera hal
     * until stopRecording() is called and all outstanding video buffers
     * previously sent out via CAMERA_MSG_VIDEO_FRAME have been released
     * via releaseVideoBuffer().
     *
     * @param index an index to retrieve the corresponding video buffer.
     *
     * @return the video buffer corresponding to the given index.
     */
    virtual sp<IMemory> getVideoBuffer(int32_t index) const { return 0; }

    /**
     * Request the camera hal to store meta data or real YUV data in
     * the video buffers send out via CAMERA_MSG_VIDEO_FRRAME for a
     * recording session. If it is not called, the default camera
     * hal behavior is to store real YUV data in the video buffers.
     *
     * This method should be called before startRecording() in order
     * to be effective.
     *
     * If meta data is stored in the video buffers, it is up to the
     * receiver of the video buffers to interpret the contents and
     * to find the actual frame data with the help of the meta data
     * in the buffer. How this is done is outside of the scope of
     * this method.
     *
     * Some camera hal may not support storing meta data in the video
     * buffers, but all camera hal should support storing real YUV data
     * in the video buffers. If the camera hal does not support storing
     * the meta data in the video buffers when it is requested to do
     * do, INVALID_OPERATION must be returned. It is very useful for
     * the camera hal to pass meta data rather than the actual frame
     * data directly to the video encoder, since the amount of the
     * uncompressed frame data can be very large if video size is large.
     *
     * @param enable if true to instruct the camera hal to store
     *      meta data in the video buffers; false to instruct
     *      the camera hal to store real YUV data in the video
     *      buffers.
     *
     * @return OK on success.
     */
    virtual status_t    storeMetaDataInBuffers(bool enable) {
                            return enable? INVALID_OPERATION: OK;
                        }

    /**
     * Start record mode. When a record image is available a CAMERA_MSG_VIDEO_FRAME
     * message is sent with the corresponding frame. Every record frame must be released
     * by a cameral hal client via releaseRecordingFrame() before the client calls
     * disableMsgType(CAMERA_MSG_VIDEO_FRAME). After the client calls
     * disableMsgType(CAMERA_MSG_VIDEO_FRAME), it is camera hal's responsibility
     * to manage the life-cycle of the video recording frames, and the client must
     * not modify/access any video recording frames.
     */
    virtual status_t    startRecording() = 0;

    /**
     * Stop a previously started recording.
     */
    virtual void        stopRecording() = 0;

    /**
     * Returns true if recording is enabled.
     */
    virtual bool        recordingEnabled() = 0;

    /**
     * Release a record frame previously returned by CAMERA_MSG_VIDEO_FRAME.
     *
     * It is camera hal client's responsibility to release video recording
     * frames sent out by the camera hal before the camera hal receives
     * a call to disableMsgType(CAMERA_MSG_VIDEO_FRAME). After it receives
     * the call to disableMsgType(CAMERA_MSG_VIDEO_FRAME), it is camera hal's
     * responsibility of managing the life-cycle of the video recording
     * frames.
     */
    virtual void        releaseRecordingFrame(const sp<IMemory>& mem) = 0;

    /**
     * Start auto focus, the notification callback routine is called
     * with CAMERA_MSG_FOCUS once when focusing is complete. autoFocus()
     * will be called again if another auto focus is needed.
     */
    virtual status_t    autoFocus() = 0;

    /**
     * Cancels auto-focus function. If the auto-focus is still in progress,
     * this function will cancel it. Whether the auto-focus is in progress
     * or not, this function will return the focus position to the default.
     * If the camera does not support auto-focus, this is a no-op.
     */
    virtual status_t    cancelAutoFocus() = 0;

    /**
     * Take a picture.
     */
    virtual status_t    takePicture() = 0;

    /**
     * Cancel a picture that was started with takePicture.  Calling this
     * method when no picture is being taken is a no-op.
     */
    virtual status_t    cancelPicture() = 0;

    /**
     * Set the camera parameters. This returns BAD_VALUE if any parameter is
     * invalid or not supported. */
    virtual status_t    setParameters(const CameraParameters& params) = 0;

    /** Return the camera parameters. */
    virtual CameraParameters  getParameters() const = 0;

    /**
     * Send command to camera driver.
     */
    virtual status_t sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) = 0;

    /**
     * Release the hardware resources owned by this object.  Note that this is
     * *not* done in the destructor.
     */
    virtual void release() = 0;

    /**
     * Dump state of the camera hardware
     */
    virtual status_t dump(int fd, const Vector<String16>& args) const = 0;
};

/**
 * The functions need to be provided by the camera HAL.
 *
 * If getNumberOfCameras() returns N, the valid cameraId for getCameraInfo()
 * and openCameraHardware() is 0 to N-1.
 */
extern "C" int HAL_getNumberOfCameras();
extern "C" void HAL_getCameraInfo(int cameraId, struct CameraInfo* cameraInfo);
/* HAL should return NULL if it fails to open camera hardware. */
extern "C" sp<CameraHardwareInterface> HAL_openCameraHardware(int cameraId);

};  // namespace android

#endif
