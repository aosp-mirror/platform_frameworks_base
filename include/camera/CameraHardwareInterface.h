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
#include <utils/RefBase.h>
#include <surfaceflinger/ISurface.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>

namespace android {

class Overlay;

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

    /** Return the IMemoryHeap for the preview image heap */
    virtual sp<IMemoryHeap>         getPreviewHeap() const = 0;

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
     * Only used if overlays are used for camera preview.
     */
    virtual bool         useOverlay() {return false;}
    virtual status_t     setOverlay(const sp<Overlay> &overlay) {return BAD_VALUE;}

    /**
     * Stop a previously started preview.
     */
    virtual void        stopPreview() = 0;

    /**
     * Returns true if preview is enabled.
     */
    virtual bool        previewEnabled() = 0;

    /**
     * Start record mode. When a record image is available a CAMERA_MSG_VIDEO_FRAME
     * message is sent with the corresponding frame. Every record frame must be released
     * by calling releaseRecordingFrame().
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

    /** Set the camera parameters. */
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
extern "C" sp<CameraHardwareInterface> HAL_openCameraHardware(int cameraId);

};  // namespace android

#endif
