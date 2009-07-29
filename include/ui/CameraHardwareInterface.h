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

#include <utils/IMemory.h>
#include <utils/RefBase.h>
#include <ui/CameraParameters.h>
#include <ui/Overlay.h>

namespace android {

/** Callback for startPreview() */
typedef void (*preview_callback)(const sp<IMemory>& mem, void* user);

/** Callback for startRecord() */
typedef void (*recording_callback)(nsecs_t timestamp, const sp<IMemory>& mem, void* user);

/** Callback for takePicture() */
typedef void (*shutter_callback)(void* user);

/** Callback for takePicture() */
typedef void (*raw_callback)(const sp<IMemory>& mem, void* user);

/** Callback for takePicture() */
typedef void (*jpeg_callback)(const sp<IMemory>& mem, void* user);

/** Callback for autoFocus() */
typedef void (*autofocus_callback)(bool focused, void* user);

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
 *   -# startPreview() is called, which is passed a preview_callback()
 *      function and a user parameter. The camera instance then periodically
 *      calls preview_callback() each time a new preview frame is available.
 *      The callback routine has two parameters: the first is a pointer to
 *      the IMemory containing the frame and the second a user parameter. If
 *      the preview_callback code needs to use this memory after returning,
 *      it must copy the data.
 *
 * Prior to taking a picture, CameraService calls autofocus() with
 * autofocus_callback() and a user parameter. When auto focusing has
 * completed, the camera instance calls autofocus_callback(), which informs
 * the application whether focusing was successful. The camera instance
 * only calls autofocus_callback() once and it is up to the application to
 * call autoFocus() again if refocusing is desired.
 *
 * CameraService calls takePicture() to request the camera instance take a
 * picture. This method has two callbacks: raw_callback() and jpeg_callback().
 * When the raw image is available, raw_callback() is called with a pointer
 * to the IMemory containing the raw image. When the jpeg image is available,
 * jpeg_callback() is called with a pointer to the IMemory containing the
 * jpeg image. As with preview_callback(), the memory must be copied if it's
 * needed after returning.
 */
class CameraHardwareInterface : public virtual RefBase {
public:
    virtual ~CameraHardwareInterface() { }

    /** Return the IMemoryHeap for the preview image heap */
    virtual sp<IMemoryHeap>         getPreviewHeap() const = 0;

    /** Return the IMemoryHeap for the raw image heap */
    virtual sp<IMemoryHeap>         getRawHeap() const = 0;

    /**
     * Start preview mode. When a preview image is available
     * preview_callback is called with the user parameter. The
     * call back parameter may be null.
     */
    virtual status_t    startPreview(preview_callback cb, void* user) = 0;
    /**
     * Only used if overlays are used for camera preview.
     */
    virtual bool useOverlay() {return false;}
    virtual status_t setOverlay(const sp<Overlay> &overlay) {return BAD_VALUE;}

    /**
     * Stop a previously started preview.
     */
    virtual void        stopPreview() = 0;

    /**
     * Returns true if preview is enabled.
     */
    virtual bool        previewEnabled() = 0;

    /**
     * Start record mode. When a record image is available recording_callback()
     * is called with the user parameter.  Every record frame must be released
     * by calling releaseRecordingFrame().
     */
    virtual status_t    startRecording(recording_callback cb, void* user) = 0;

    /**
     * Stop a previously started recording.
     */
    virtual void        stopRecording() = 0;

    /**
     * Returns true if recording is enabled.
     */
    virtual bool        recordingEnabled() = 0;
    
    /**
     * Release a record frame previously returned by the recording_callback()
     * passed to startRecord().
     */
    virtual void        releaseRecordingFrame(const sp<IMemory>& mem) = 0;

    /**
     * Start auto focus, the callback routine is called
     * once when focusing is complete. autoFocus() will
     * be called again if another auto focus is needed.
     */
    virtual status_t    autoFocus(autofocus_callback,
                                  void* user) = 0;

    /**
     * Take a picture. The raw_callback is called when
     * the uncompressed image is available. The jpeg_callback
     * is called when the compressed image is available. These
     * call backs may be null. The user parameter is passed
     * to each of the call back routines.
     */
    virtual status_t    takePicture(shutter_callback,
                                    raw_callback,
                                    jpeg_callback,
                                    void* user) = 0;

    /**
     * Cancel a picture that was started with takePicture.  You may cancel any
     * of the shutter, raw, or jpeg callbacks.  Calling this method when no
     * picture is being taken is a no-op.
     */
    virtual status_t    cancelPicture(bool cancel_shutter,
                                      bool cancel_raw,
                                      bool cancel_jpeg) = 0;

    /** Set the camera parameters. */
    virtual status_t    setParameters(const CameraParameters& params) = 0;

    /** Return the camera parameters. */
    virtual CameraParameters  getParameters() const = 0;

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

/** factory function to instantiate a camera hardware object */
extern "C" sp<CameraHardwareInterface> openCameraHardware();

};  // namespace android

#endif
