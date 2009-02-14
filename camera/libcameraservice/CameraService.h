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

#ifndef ANDROID_SERVERS_CAMERA_CAMERASERVICE_H
#define ANDROID_SERVERS_CAMERA_CAMERASERVICE_H

#include <ui/ICameraService.h>
#include <ui/CameraHardwareInterface.h>
#include <ui/Camera.h>

class android::MemoryHeapBase;

namespace android {

// ----------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// When enabled, this feature allows you to send an event to the CameraService
// so that you can cause all references to the heap object gWeakHeap, defined
// below, to be printed. You will also need to set DEBUG_REFS=1 and
// DEBUG_REFS_ENABLED_BY_DEFAULT=0 in libutils/RefBase.cpp. You just have to
// set gWeakHeap to the appropriate heap you want to track.

#define DEBUG_HEAP_LEAKS 0

// ----------------------------------------------------------------------------

class CameraService : public BnCameraService
{
    class Client;

public:
    static void instantiate();

    // ICameraService interface
    virtual sp<ICamera>     connect(const sp<ICameraClient>& cameraClient);

    virtual status_t        dump(int fd, const Vector<String16>& args);

            void            removeClient(const sp<ICameraClient>& cameraClient);

#if DEBUG_HEAP_LEAKS
    virtual status_t onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
#endif

private:

// ----------------------------------------------------------------------------

    class Client : public BnCamera {

    public:
        virtual void            disconnect();

        // connect new client with existing camera remote
        virtual status_t        connect(const sp<ICameraClient>& client);

        // prevent other processes from using this ICamera interface
        virtual status_t        lock();

        // allow other processes to use this ICamera interface
        virtual status_t        unlock();

        // pass the buffered ISurface to the camera service
        virtual status_t        setPreviewDisplay(const sp<ISurface>& surface);

        // set the frame callback flag to affect how the received frames from
        // preview are handled.
        virtual void            setFrameCallbackFlag(int frame_callback_flag);

        // start preview mode, must call setPreviewDisplay first
        virtual status_t        startPreview();

        // stop preview mode
        virtual void            stopPreview();

        // get preview state
        virtual bool            previewEnabled();

        // auto focus
        virtual status_t        autoFocus();

        // take a picture - returns an IMemory (ref-counted mmap)
        virtual status_t        takePicture();

        // set preview/capture parameters - key/value pairs
        virtual status_t        setParameters(const String8& params);

        // get preview/capture parameters - key/value pairs
        virtual String8         getParameters() const;

        // our client...
        const sp<ICameraClient>&    getCameraClient() const { return mCameraClient; }

    private:
        friend class CameraService;
                                Client(const sp<CameraService>& cameraService,
                                        const sp<ICameraClient>& cameraClient,
                                        pid_t clientPid);
                                Client();
        virtual                 ~Client();

                    status_t    checkPid();

        static      void        previewCallback(const sp<IMemory>& mem, void* user);
        static      void        shutterCallback(void *user);
        static      void        yuvPictureCallback(const sp<IMemory>& mem, void* user);
        static      void        jpegPictureCallback(const sp<IMemory>& mem, void* user);
        static      void        autoFocusCallback(bool focused, void* user);
        static      sp<Client>  getClientFromCookie(void* user);

                    void        postShutter();
                    void        postRaw(const sp<IMemory>& mem);
                    void        postJpeg(const sp<IMemory>& mem);
                    void        postFrame(const sp<IMemory>& mem);
                    void        copyFrameAndPostCopiedFrame(sp<IMemoryHeap> heap, size_t offset, size_t size);
                    void        postError(status_t error);
                    void        postAutoFocus(bool focused);

                    // Ensures atomicity among the public methods
        mutable     Mutex                       mLock;
        // mSurfaceLock synchronizes access to mSurface between
        // setPreviewSurface() and postFrame().  Note that among
        // the public methods, all accesses to mSurface are
        // syncrhonized by mLock.  However, postFrame() is called
        // by the CameraHardwareInterface callback, and needs to
        // access mSurface.  It cannot hold mLock, however, because
        // stopPreview() may be holding that lock while attempting
        // to stop preview, and stopPreview itself will block waiting
        // for a callback from CameraHardwareInterface.  If this
        // happens, it will cause a deadlock.
        mutable     Mutex                       mSurfaceLock;
        mutable     Condition                   mReady;
                    sp<CameraService>           mCameraService;
                    sp<ISurface>                mSurface;
                    sp<MemoryHeapBase>          mPreviewBuffer;
                    int                         mFrameCallbackFlag;

                    // these are immutable once the object is created,
                    // they don't need to be protected by a lock
                    sp<ICameraClient>           mCameraClient;
                    sp<CameraHardwareInterface> mHardware;
                    pid_t                       mClientPid;
                    bool                        mUseOverlay;
    };

// ----------------------------------------------------------------------------

                            CameraService();
    virtual                 ~CameraService();

    mutable     Mutex                       mLock;
                wp<Client>                  mClient;

#if DEBUG_HEAP_LEAKS
                wp<IMemoryHeap>             gWeakHeap;
#endif
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif
