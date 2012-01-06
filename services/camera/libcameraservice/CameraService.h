/*
**
** Copyright (C) 2008, The Android Open Source Project
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

#include <binder/BinderService.h>
#include <camera/ICameraService.h>
#include <hardware/camera.h>

/* This needs to be increased if we can have more cameras */
#define MAX_CAMERAS 2

namespace android {

class MemoryHeapBase;
class MediaPlayer;
class CameraHardwareInterface;

class CameraService :
    public BinderService<CameraService>,
    public BnCameraService
{
    class Client;
    friend class BinderService<CameraService>;
public:
    static char const* getServiceName() { return "media.camera"; }

                        CameraService();
    virtual             ~CameraService();

    virtual int32_t     getNumberOfCameras();
    virtual status_t    getCameraInfo(int cameraId,
                                      struct CameraInfo* cameraInfo);
    virtual sp<ICamera> connect(const sp<ICameraClient>& cameraClient, int cameraId);
    virtual void        removeClient(const sp<ICameraClient>& cameraClient);
    virtual sp<Client>  getClientById(int cameraId);

    virtual status_t    dump(int fd, const Vector<String16>& args);
    virtual status_t    onTransact(uint32_t code, const Parcel& data,
                                   Parcel* reply, uint32_t flags);
    virtual void onFirstRef();

    enum sound_kind {
        SOUND_SHUTTER = 0,
        SOUND_RECORDING = 1,
        NUM_SOUNDS
    };

    void                loadSound();
    void                playSound(sound_kind kind);
    void                releaseSound();

private:
    Mutex               mServiceLock;
    wp<Client>          mClient[MAX_CAMERAS];  // protected by mServiceLock
    int                 mNumberOfCameras;

    // atomics to record whether the hardware is allocated to some client.
    volatile int32_t    mBusy[MAX_CAMERAS];
    void                setCameraBusy(int cameraId);
    void                setCameraFree(int cameraId);

    // sounds
    MediaPlayer*        newMediaPlayer(const char *file);

    Mutex               mSoundLock;
    sp<MediaPlayer>     mSoundPlayer[NUM_SOUNDS];
    int                 mSoundRef;  // reference count (release all MediaPlayer when 0)

    class Client : public BnCamera
    {
    public:
        // ICamera interface (see ICamera for details)
        virtual void            disconnect();
        virtual status_t        connect(const sp<ICameraClient>& client);
        virtual status_t        lock();
        virtual status_t        unlock();
        virtual status_t        setPreviewDisplay(const sp<Surface>& surface);
        virtual status_t        setPreviewTexture(const sp<ISurfaceTexture>& surfaceTexture);
        virtual void            setPreviewCallbackFlag(int flag);
        virtual status_t        startPreview();
        virtual void            stopPreview();
        virtual bool            previewEnabled();
        virtual status_t        storeMetaDataInBuffers(bool enabled);
        virtual status_t        startRecording();
        virtual void            stopRecording();
        virtual bool            recordingEnabled();
        virtual void            releaseRecordingFrame(const sp<IMemory>& mem);
        virtual status_t        autoFocus();
        virtual status_t        cancelAutoFocus();
        virtual status_t        takePicture(int msgType);
        virtual status_t        setParameters(const String8& params);
        virtual String8         getParameters() const;
        virtual status_t        sendCommand(int32_t cmd, int32_t arg1, int32_t arg2);
    private:
        friend class CameraService;
                                Client(const sp<CameraService>& cameraService,
                                       const sp<ICameraClient>& cameraClient,
                                       const sp<CameraHardwareInterface>& hardware,
                                       int cameraId,
                                       int cameraFacing,
                                       int clientPid);
                                ~Client();

        // return our camera client
        const sp<ICameraClient>&    getCameraClient() { return mCameraClient; }

        // check whether the calling process matches mClientPid.
        status_t                checkPid() const;
        status_t                checkPidAndHardware() const;  // also check mHardware != 0

        // these are internal functions used to set up preview buffers
        status_t                registerPreviewBuffers();

        // camera operation mode
        enum camera_mode {
            CAMERA_PREVIEW_MODE   = 0,  // frame automatically released
            CAMERA_RECORDING_MODE = 1,  // frame has to be explicitly released by releaseRecordingFrame()
        };
        // these are internal functions used for preview/recording
        status_t                startCameraMode(camera_mode mode);
        status_t                startPreviewMode();
        status_t                startRecordingMode();

        // internal function used by sendCommand to enable/disable shutter sound.
        status_t                enableShutterSound(bool enable);

        // these are static callback functions
        static void             notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2, void* user);
        static void             dataCallback(int32_t msgType, const sp<IMemory>& dataPtr,
                                             camera_frame_metadata_t *metadata, void* user);
        static void             dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr, void* user);
        // convert client from cookie
        static sp<Client>       getClientFromCookie(void* user);
        // handlers for messages
        void                    handleShutter(void);
        void                    handlePreviewData(int32_t msgType, const sp<IMemory>& mem,
                                                  camera_frame_metadata_t *metadata);
        void                    handlePostview(const sp<IMemory>& mem);
        void                    handleRawPicture(const sp<IMemory>& mem);
        void                    handleCompressedPicture(const sp<IMemory>& mem);
        void                    handleGenericNotify(int32_t msgType, int32_t ext1, int32_t ext2);
        void                    handleGenericData(int32_t msgType, const sp<IMemory>& dataPtr,
                                                  camera_frame_metadata_t *metadata);
        void                    handleGenericDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

        void                    copyFrameAndPostCopiedFrame(
                                    int32_t msgType,
                                    const sp<ICameraClient>& client,
                                    const sp<IMemoryHeap>& heap,
                                    size_t offset, size_t size,
                                    camera_frame_metadata_t *metadata);

        int                     getOrientation(int orientation, bool mirror);

        status_t                setPreviewWindow(
                                    const sp<IBinder>& binder,
                                    const sp<ANativeWindow>& window);

        // these are initialized in the constructor.
        sp<CameraService>               mCameraService;  // immutable after constructor
        sp<ICameraClient>               mCameraClient;
        int                             mCameraId;       // immutable after constructor
        int                             mCameraFacing;   // immutable after constructor
        pid_t                           mClientPid;
        sp<CameraHardwareInterface>     mHardware;       // cleared after disconnect()
        int                             mPreviewCallbackFlag;
        int                             mOrientation;     // Current display orientation
        bool                            mPlayShutterSound;

        // Ensures atomicity among the public methods
        mutable Mutex                   mLock;
        // This is a binder of Surface or SurfaceTexture.
        sp<IBinder>                     mSurface;
        sp<ANativeWindow>               mPreviewWindow;

        // If the user want us to return a copy of the preview frame (instead
        // of the original one), we allocate mPreviewBuffer and reuse it if possible.
        sp<MemoryHeapBase>              mPreviewBuffer;

        // We need to avoid the deadlock when the incoming command thread and
        // the CameraHardwareInterface callback thread both want to grab mLock.
        // An extra flag is used to tell the callback thread that it should stop
        // trying to deliver the callback messages if the client is not
        // interested in it anymore. For example, if the client is calling
        // stopPreview(), the preview frame messages do not need to be delivered
        // anymore.

        // This function takes the same parameter as the enableMsgType() and
        // disableMsgType() functions in CameraHardwareInterface.
        void                    enableMsgType(int32_t msgType);
        void                    disableMsgType(int32_t msgType);
        volatile int32_t        mMsgEnabled;

        // This function keeps trying to grab mLock, or give up if the message
        // is found to be disabled. It returns true if mLock is grabbed.
        bool                    lockIfMessageWanted(int32_t msgType);
    };

    camera_module_t *mModule;
};

} // namespace android

#endif
