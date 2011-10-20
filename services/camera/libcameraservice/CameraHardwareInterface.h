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
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <utils/RefBase.h>
#include <surfaceflinger/ISurface.h>
#include <ui/android_native_buffer.h>
#include <ui/GraphicBuffer.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <system/window.h>
#include <hardware/camera.h>

namespace android {

typedef void (*notify_callback)(int32_t msgType,
                            int32_t ext1,
                            int32_t ext2,
                            void* user);

typedef void (*data_callback)(int32_t msgType,
                            const sp<IMemory> &dataPtr,
                            camera_frame_metadata_t *metadata,
                            void* user);

typedef void (*data_callback_timestamp)(nsecs_t timestamp,
                            int32_t msgType,
                            const sp<IMemory> &dataPtr,
                            void *user);

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
    CameraHardwareInterface(const char *name)
    {
        mDevice = 0;
        mName = name;
    }

    ~CameraHardwareInterface()
    {
        LOGI("Destroying camera %s", mName.string());
        if(mDevice) {
            int rc = mDevice->common.close(&mDevice->common);
            if (rc != OK)
                LOGE("Could not close camera %s: %d", mName.string(), rc);
        }
    }

    status_t initialize(hw_module_t *module)
    {
        LOGI("Opening camera %s", mName.string());
        int rc = module->methods->open(module, mName.string(),
                                       (hw_device_t **)&mDevice);
        if (rc != OK) {
            LOGE("Could not open camera %s: %d", mName.string(), rc);
            return rc;
        }
        initHalPreviewWindow();
        return rc;
    }

    /** Set the ANativeWindow to which preview frames are sent */
    status_t setPreviewWindow(const sp<ANativeWindow>& buf)
    {
        ALOGV("%s(%s) buf %p", __FUNCTION__, mName.string(), buf.get());

        if (mDevice->ops->set_preview_window) {
            mPreviewWindow = buf;
            mHalPreviewWindow.user = this;
            ALOGV("%s &mHalPreviewWindow %p mHalPreviewWindow.user %p", __FUNCTION__,
                    &mHalPreviewWindow, mHalPreviewWindow.user);
            return mDevice->ops->set_preview_window(mDevice,
                    buf.get() ? &mHalPreviewWindow.nw : 0);
        }
        return INVALID_OPERATION;
    }

    /** Set the notification and data callbacks */
    void setCallbacks(notify_callback notify_cb,
                      data_callback data_cb,
                      data_callback_timestamp data_cb_timestamp,
                      void* user)
    {
        mNotifyCb = notify_cb;
        mDataCb = data_cb;
        mDataCbTimestamp = data_cb_timestamp;
        mCbUser = user;

        ALOGV("%s(%s)", __FUNCTION__, mName.string());

        if (mDevice->ops->set_callbacks) {
            mDevice->ops->set_callbacks(mDevice,
                                   __notify_cb,
                                   __data_cb,
                                   __data_cb_timestamp,
                                   __get_memory,
                                   this);
        }
    }

    /**
     * The following three functions all take a msgtype,
     * which is a bitmask of the messages defined in
     * include/ui/Camera.h
     */

    /**
     * Enable a message, or set of messages.
     */
    void enableMsgType(int32_t msgType)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->enable_msg_type)
            mDevice->ops->enable_msg_type(mDevice, msgType);
    }

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
    void disableMsgType(int32_t msgType)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->disable_msg_type)
            mDevice->ops->disable_msg_type(mDevice, msgType);
    }

    /**
     * Query whether a message, or a set of messages, is enabled.
     * Note that this is operates as an AND, if any of the messages
     * queried are off, this will return false.
     */
    int msgTypeEnabled(int32_t msgType)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->msg_type_enabled)
            return mDevice->ops->msg_type_enabled(mDevice, msgType);
        return false;
    }

    /**
     * Start preview mode.
     */
    status_t startPreview()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->start_preview)
            return mDevice->ops->start_preview(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Stop a previously started preview.
     */
    void stopPreview()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->stop_preview)
            mDevice->ops->stop_preview(mDevice);
    }

    /**
     * Returns true if preview is enabled.
     */
    int previewEnabled()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->preview_enabled)
            return mDevice->ops->preview_enabled(mDevice);
        return false;
    }

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

    status_t storeMetaDataInBuffers(int enable)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->store_meta_data_in_buffers)
            return mDevice->ops->store_meta_data_in_buffers(mDevice, enable);
        return enable ? INVALID_OPERATION: OK;
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
    status_t startRecording()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->start_recording)
            return mDevice->ops->start_recording(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Stop a previously started recording.
     */
    void stopRecording()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->stop_recording)
            mDevice->ops->stop_recording(mDevice);
    }

    /**
     * Returns true if recording is enabled.
     */
    int recordingEnabled()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->recording_enabled)
            return mDevice->ops->recording_enabled(mDevice);
        return false;
    }

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
    void releaseRecordingFrame(const sp<IMemory>& mem)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->release_recording_frame) {
            ssize_t offset;
            size_t size;
            sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
            void *data = ((uint8_t *)heap->base()) + offset;
            return mDevice->ops->release_recording_frame(mDevice, data);
        }
    }

    /**
     * Start auto focus, the notification callback routine is called
     * with CAMERA_MSG_FOCUS once when focusing is complete. autoFocus()
     * will be called again if another auto focus is needed.
     */
    status_t autoFocus()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->auto_focus)
            return mDevice->ops->auto_focus(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Cancels auto-focus function. If the auto-focus is still in progress,
     * this function will cancel it. Whether the auto-focus is in progress
     * or not, this function will return the focus position to the default.
     * If the camera does not support auto-focus, this is a no-op.
     */
    status_t cancelAutoFocus()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->cancel_auto_focus)
            return mDevice->ops->cancel_auto_focus(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Take a picture.
     */
    status_t takePicture()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->take_picture)
            return mDevice->ops->take_picture(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Cancel a picture that was started with takePicture.  Calling this
     * method when no picture is being taken is a no-op.
     */
    status_t cancelPicture()
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->cancel_picture)
            return mDevice->ops->cancel_picture(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Set the camera parameters. This returns BAD_VALUE if any parameter is
     * invalid or not supported. */
    status_t setParameters(const CameraParameters &params)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->set_parameters)
            return mDevice->ops->set_parameters(mDevice,
                                               params.flatten().string());
        return INVALID_OPERATION;
    }

    /** Return the camera parameters. */
    CameraParameters getParameters() const
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        CameraParameters parms;
        if (mDevice->ops->get_parameters) {
            char *temp = mDevice->ops->get_parameters(mDevice);
            String8 str_parms(temp);
            if (mDevice->ops->put_parameters)
                mDevice->ops->put_parameters(mDevice, temp);
            else
                free(temp);
            parms.unflatten(str_parms);
        }
        return parms;
    }

    /**
     * Send command to camera driver.
     */
    status_t sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->send_command)
            return mDevice->ops->send_command(mDevice, cmd, arg1, arg2);
        return INVALID_OPERATION;
    }

    /**
     * Release the hardware resources owned by this object.  Note that this is
     * *not* done in the destructor.
     */
    void release() {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->release)
            mDevice->ops->release(mDevice);
    }

    /**
     * Dump state of the camera hardware
     */
    status_t dump(int fd, const Vector<String16>& args) const
    {
        ALOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->dump)
            return mDevice->ops->dump(mDevice, fd);
        return OK; // It's fine if the HAL doesn't implement dump()
    }

private:
    camera_device_t *mDevice;
    String8 mName;

    static void __notify_cb(int32_t msg_type, int32_t ext1,
                            int32_t ext2, void *user)
    {
        ALOGV("%s", __FUNCTION__);
        CameraHardwareInterface *__this =
                static_cast<CameraHardwareInterface *>(user);
        __this->mNotifyCb(msg_type, ext1, ext2, __this->mCbUser);
    }

    static void __data_cb(int32_t msg_type,
                          const camera_memory_t *data, unsigned int index,
                          camera_frame_metadata_t *metadata,
                          void *user)
    {
        ALOGV("%s", __FUNCTION__);
        CameraHardwareInterface *__this =
                static_cast<CameraHardwareInterface *>(user);
        sp<CameraHeapMemory> mem(static_cast<CameraHeapMemory *>(data->handle));
        if (index >= mem->mNumBufs) {
            LOGE("%s: invalid buffer index %d, max allowed is %d", __FUNCTION__,
                 index, mem->mNumBufs);
            return;
        }
        __this->mDataCb(msg_type, mem->mBuffers[index], metadata, __this->mCbUser);
    }

    static void __data_cb_timestamp(nsecs_t timestamp, int32_t msg_type,
                             const camera_memory_t *data, unsigned index,
                             void *user)
    {
        ALOGV("%s", __FUNCTION__);
        CameraHardwareInterface *__this =
                static_cast<CameraHardwareInterface *>(user);
        // Start refcounting the heap object from here on.  When the clients
        // drop all references, it will be destroyed (as well as the enclosed
        // MemoryHeapBase.
        sp<CameraHeapMemory> mem(static_cast<CameraHeapMemory *>(data->handle));
        if (index >= mem->mNumBufs) {
            LOGE("%s: invalid buffer index %d, max allowed is %d", __FUNCTION__,
                 index, mem->mNumBufs);
            return;
        }
        __this->mDataCbTimestamp(timestamp, msg_type, mem->mBuffers[index], __this->mCbUser);
    }

    // This is a utility class that combines a MemoryHeapBase and a MemoryBase
    // in one.  Since we tend to use them in a one-to-one relationship, this is
    // handy.

    class CameraHeapMemory : public RefBase {
    public:
        CameraHeapMemory(int fd, size_t buf_size, uint_t num_buffers = 1) :
                         mBufSize(buf_size),
                         mNumBufs(num_buffers)
        {
            mHeap = new MemoryHeapBase(fd, buf_size * num_buffers);
            commonInitialization();
        }

        CameraHeapMemory(size_t buf_size, uint_t num_buffers = 1) :
                         mBufSize(buf_size),
                         mNumBufs(num_buffers)
        {
            mHeap = new MemoryHeapBase(buf_size * num_buffers);
            commonInitialization();
        }

        void commonInitialization()
        {
            handle.data = mHeap->base();
            handle.size = mBufSize * mNumBufs;
            handle.handle = this;

            mBuffers = new sp<MemoryBase>[mNumBufs];
            for (uint_t i = 0; i < mNumBufs; i++)
                mBuffers[i] = new MemoryBase(mHeap,
                                             i * mBufSize,
                                             mBufSize);

            handle.release = __put_memory;
        }

        virtual ~CameraHeapMemory()
        {
            delete [] mBuffers;
        }

        size_t mBufSize;
        uint_t mNumBufs;
        sp<MemoryHeapBase> mHeap;
        sp<MemoryBase> *mBuffers;

        camera_memory_t handle;
    };

    static camera_memory_t* __get_memory(int fd, size_t buf_size, uint_t num_bufs,
                                         void *user __attribute__((unused)))
    {
        CameraHeapMemory *mem;
        if (fd < 0)
            mem = new CameraHeapMemory(buf_size, num_bufs);
        else
            mem = new CameraHeapMemory(fd, buf_size, num_bufs);
        mem->incStrong(mem);
        return &mem->handle;
    }

    static void __put_memory(camera_memory_t *data)
    {
        if (!data)
            return;

        CameraHeapMemory *mem = static_cast<CameraHeapMemory *>(data->handle);
        mem->decStrong(mem);
    }

    static ANativeWindow *__to_anw(void *user)
    {
        CameraHardwareInterface *__this =
                reinterpret_cast<CameraHardwareInterface *>(user);
        return __this->mPreviewWindow.get();
    }
#define anw(n) __to_anw(((struct camera_preview_window *)n)->user)

    static int __dequeue_buffer(struct preview_stream_ops* w,
                                buffer_handle_t** buffer, int *stride)
    {
        int rc;
        ANativeWindow *a = anw(w);
        ANativeWindowBuffer* anb;
        rc = a->dequeueBuffer(a, &anb);
        if (!rc) {
            *buffer = &anb->handle;
            *stride = anb->stride;
        }
        return rc;
    }

#ifndef container_of
#define container_of(ptr, type, member) ({                      \
        const typeof(((type *) 0)->member) *__mptr = (ptr);     \
        (type *) ((char *) __mptr - (char *)(&((type *)0)->member)); })
#endif

    static int __lock_buffer(struct preview_stream_ops* w,
                      buffer_handle_t* buffer)
    {
        ANativeWindow *a = anw(w);
        return a->lockBuffer(a,
                  container_of(buffer, ANativeWindowBuffer, handle));
    }

    static int __enqueue_buffer(struct preview_stream_ops* w,
                      buffer_handle_t* buffer)
    {
        ANativeWindow *a = anw(w);
        return a->queueBuffer(a,
                  container_of(buffer, ANativeWindowBuffer, handle));
    }

    static int __cancel_buffer(struct preview_stream_ops* w,
                      buffer_handle_t* buffer)
    {
        ANativeWindow *a = anw(w);
        return a->cancelBuffer(a,
                  container_of(buffer, ANativeWindowBuffer, handle));
    }

    static int __set_buffer_count(struct preview_stream_ops* w, int count)
    {
        ANativeWindow *a = anw(w);
        return native_window_set_buffer_count(a, count);
    }

    static int __set_buffers_geometry(struct preview_stream_ops* w,
                      int width, int height, int format)
    {
        ANativeWindow *a = anw(w);
        return native_window_set_buffers_geometry(a,
                          width, height, format);
    }

    static int __set_crop(struct preview_stream_ops *w,
                      int left, int top, int right, int bottom)
    {
        ANativeWindow *a = anw(w);
        android_native_rect_t crop;
        crop.left = left;
        crop.top = top;
        crop.right = right;
        crop.bottom = bottom;
        return native_window_set_crop(a, &crop);
    }

    static int __set_usage(struct preview_stream_ops* w, int usage)
    {
        ANativeWindow *a = anw(w);
        return native_window_set_usage(a, usage);
    }

    static int __set_swap_interval(struct preview_stream_ops *w, int interval)
    {
        ANativeWindow *a = anw(w);
        return a->setSwapInterval(a, interval);
    }

    static int __get_min_undequeued_buffer_count(
                      const struct preview_stream_ops *w,
                      int *count)
    {
        ANativeWindow *a = anw(w);
        return a->query(a, NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, count);
    }

    void initHalPreviewWindow()
    {
        mHalPreviewWindow.nw.cancel_buffer = __cancel_buffer;
        mHalPreviewWindow.nw.lock_buffer = __lock_buffer;
        mHalPreviewWindow.nw.dequeue_buffer = __dequeue_buffer;
        mHalPreviewWindow.nw.enqueue_buffer = __enqueue_buffer;
        mHalPreviewWindow.nw.set_buffer_count = __set_buffer_count;
        mHalPreviewWindow.nw.set_buffers_geometry = __set_buffers_geometry;
        mHalPreviewWindow.nw.set_crop = __set_crop;
        mHalPreviewWindow.nw.set_usage = __set_usage;
        mHalPreviewWindow.nw.set_swap_interval = __set_swap_interval;

        mHalPreviewWindow.nw.get_min_undequeued_buffer_count =
                __get_min_undequeued_buffer_count;
    }

    sp<ANativeWindow>        mPreviewWindow;

    struct camera_preview_window {
        struct preview_stream_ops nw;
        void *user;
    };

    struct camera_preview_window mHalPreviewWindow;

    notify_callback         mNotifyCb;
    data_callback           mDataCb;
    data_callback_timestamp mDataCbTimestamp;
    void *mCbUser;
};

};  // namespace android

#endif
