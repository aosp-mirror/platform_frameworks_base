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

#ifndef ANDROID_HARDWARE_CAMERA_H
#define ANDROID_HARDWARE_CAMERA_H

#include <utils/Timers.h>
#include <camera/ICameraClient.h>
#include <gui/ISurfaceTexture.h>

namespace android {

/*
 * A set of bit masks for specifying how the received preview frames are
 * handled before the previewCallback() call.
 *
 * The least significant 3 bits of an "int" value are used for this purpose:
 *
 * ..... 0 0 0
 *       ^ ^ ^
 *       | | |---------> determine whether the callback is enabled or not
 *       | |-----------> determine whether the callback is one-shot or not
 *       |-------------> determine whether the frame is copied out or not
 *
 * WARNING:
 * When a frame is sent directly without copying, it is the frame receiver's
 * responsiblity to make sure that the frame data won't get corrupted by
 * subsequent preview frames filled by the camera. This flag is recommended
 * only when copying out data brings significant performance price and the
 * handling/processing of the received frame data is always faster than
 * the preview frame rate so that data corruption won't occur.
 *
 * For instance,
 * 1. 0x00 disables the callback. In this case, copy out and one shot bits
 *    are ignored.
 * 2. 0x01 enables a callback without copying out the received frames. A
 *    typical use case is the Camcorder application to avoid making costly
 *    frame copies.
 * 3. 0x05 is enabling a callback with frame copied out repeatedly. A typical
 *    use case is the Camera application.
 * 4. 0x07 is enabling a callback with frame copied out only once. A typical use
 *    case is the Barcode scanner application.
 */
#define FRAME_CALLBACK_FLAG_ENABLE_MASK              0x01
#define FRAME_CALLBACK_FLAG_ONE_SHOT_MASK            0x02
#define FRAME_CALLBACK_FLAG_COPY_OUT_MASK            0x04

// Typical use cases
#define FRAME_CALLBACK_FLAG_NOOP                     0x00
#define FRAME_CALLBACK_FLAG_CAMCORDER                0x01
#define FRAME_CALLBACK_FLAG_CAMERA                   0x05
#define FRAME_CALLBACK_FLAG_BARCODE_SCANNER          0x07

// msgType in notifyCallback and dataCallback functions
enum {
    CAMERA_MSG_ERROR            = 0x0001,
    CAMERA_MSG_SHUTTER          = 0x0002,
    CAMERA_MSG_FOCUS            = 0x0004,
    CAMERA_MSG_ZOOM             = 0x0008,
    CAMERA_MSG_PREVIEW_FRAME    = 0x0010,
    CAMERA_MSG_VIDEO_FRAME      = 0x0020,
    CAMERA_MSG_POSTVIEW_FRAME   = 0x0040,
    CAMERA_MSG_RAW_IMAGE        = 0x0080,
    CAMERA_MSG_COMPRESSED_IMAGE = 0x0100,
    CAMERA_MSG_RAW_IMAGE_NOTIFY = 0x0200,
    CAMERA_MSG_ALL_MSGS         = 0xFFFF
};

// cmdType in sendCommand functions
enum {
    CAMERA_CMD_START_SMOOTH_ZOOM     = 1,
    CAMERA_CMD_STOP_SMOOTH_ZOOM      = 2,
    // Set the clockwise rotation of preview display (setPreviewDisplay) in
    // degrees. This affects the preview frames and the picture displayed after
    // snapshot. This method is useful for portrait mode applications. Note that
    // preview display of front-facing cameras is flipped horizontally before
    // the rotation, that is, the image is reflected along the central vertical
    // axis of the camera sensor. So the users can see themselves as looking
    // into a mirror.
    //
    // This does not affect the order of byte array of CAMERA_MSG_PREVIEW_FRAME,
    // CAMERA_MSG_VIDEO_FRAME, CAMERA_MSG_POSTVIEW_FRAME, CAMERA_MSG_RAW_IMAGE,
    // or CAMERA_MSG_COMPRESSED_IMAGE. This is not allowed to be set during
    // preview.
    CAMERA_CMD_SET_DISPLAY_ORIENTATION = 3,

    // cmdType to disable/enable shutter sound.
    // In sendCommand passing arg1 = 0 will disable,
    // while passing arg1 = 1 will enable the shutter sound.
    CAMERA_CMD_ENABLE_SHUTTER_SOUND = 4,

    // cmdType to play recording sound.
    CAMERA_CMD_PLAY_RECORDING_SOUND = 5,
};

// camera fatal errors
enum {
    CAMERA_ERROR_UNKNOWN  = 1,
    CAMERA_ERROR_SERVER_DIED = 100
};

enum {
    CAMERA_FACING_BACK = 0, /* The facing of the camera is opposite to that of the screen. */
    CAMERA_FACING_FRONT = 1 /* The facing of the camera is the same as that of the screen. */
};

struct CameraInfo {

    /**
     * The direction that the camera faces to. It should be
     * CAMERA_FACING_BACK or CAMERA_FACING_FRONT.
     */
    int facing;

    /**
     * The orientation of the camera image. The value is the angle that the
     * camera image needs to be rotated clockwise so it shows correctly on
     * the display in its natural orientation. It should be 0, 90, 180, or 270.
     *
     * For example, suppose a device has a naturally tall screen. The
     * back-facing camera sensor is mounted in landscape. You are looking at
     * the screen. If the top side of the camera sensor is aligned with the
     * right edge of the screen in natural orientation, the value should be
     * 90. If the top side of a front-facing camera sensor is aligned with
     * the right of the screen, the value should be 270.
     */
    int orientation;
};

class ICameraService;
class ICamera;
class Surface;
class Mutex;
class String8;

// ref-counted object for callbacks
class CameraListener: virtual public RefBase
{
public:
    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2) = 0;
    virtual void postData(int32_t msgType, const sp<IMemory>& dataPtr) = 0;
    virtual void postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) = 0;
};

class Camera : public BnCameraClient, public IBinder::DeathRecipient
{
public:
            // construct a camera client from an existing remote
    static  sp<Camera>  create(const sp<ICamera>& camera);
    static  int32_t     getNumberOfCameras();
    static  status_t    getCameraInfo(int cameraId,
                                      struct CameraInfo* cameraInfo);
    static  sp<Camera>  connect(int cameraId);
                        ~Camera();
            void        init();

            status_t    reconnect();
            void        disconnect();
            status_t    lock();
            status_t    unlock();

            status_t    getStatus() { return mStatus; }

            // pass the buffered Surface to the camera service
            status_t    setPreviewDisplay(const sp<Surface>& surface);

            // pass the buffered ISurfaceTexture to the camera service
            status_t    setPreviewTexture(const sp<ISurfaceTexture>& surfaceTexture);

            // start preview mode, must call setPreviewDisplay first
            status_t    startPreview();

            // stop preview mode
            void        stopPreview();

            // get preview state
            bool        previewEnabled();

            // start recording mode, must call setPreviewDisplay first
            status_t    startRecording();

            // stop recording mode
            void        stopRecording();

            // get recording state
            bool        recordingEnabled();

            // release a recording frame
            void        releaseRecordingFrame(const sp<IMemory>& mem);

            // autoFocus - status returned from callback
            status_t    autoFocus();

            // cancel auto focus
            status_t    cancelAutoFocus();

            // take a picture - picture returned from callback
            status_t    takePicture(int msgType);

            // set preview/capture parameters - key/value pairs
            status_t    setParameters(const String8& params);

            // get preview/capture parameters - key/value pairs
            String8     getParameters() const;

            // send command to camera driver
            status_t    sendCommand(int32_t cmd, int32_t arg1, int32_t arg2);

            // return the total number of available video buffers.
            int32_t     getNumberOfVideoBuffers() const;

            // return the individual video buffer corresponding to the given index.
            sp<IMemory> getVideoBuffer(int32_t index) const;

            // tell camera hal to store meta data or real YUV in video buffers.
            status_t    storeMetaDataInBuffers(bool enabled);

            void        setListener(const sp<CameraListener>& listener);
            void        setPreviewCallbackFlags(int preview_callback_flag);

    // ICameraClient interface
    virtual void        notifyCallback(int32_t msgType, int32_t ext, int32_t ext2);
    virtual void        dataCallback(int32_t msgType, const sp<IMemory>& dataPtr);
    virtual void        dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

    sp<ICamera>         remote();

private:
                        Camera();
                        Camera(const Camera&);
                        Camera& operator=(const Camera);
                        virtual void binderDied(const wp<IBinder>& who);

            class DeathNotifier: public IBinder::DeathRecipient
            {
            public:
                DeathNotifier() {
                }

                virtual void binderDied(const wp<IBinder>& who);
            };

            static sp<DeathNotifier> mDeathNotifier;

            // helper function to obtain camera service handle
            static const sp<ICameraService>& getCameraService();

            sp<ICamera>         mCamera;
            status_t            mStatus;

            sp<CameraListener>  mListener;

            friend class DeathNotifier;

            static  Mutex               mLock;
            static  sp<ICameraService>  mCameraService;

};

}; // namespace android

#endif
