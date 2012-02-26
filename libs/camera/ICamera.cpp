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

//#define LOG_NDEBUG 0
#define LOG_TAG "ICamera"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <camera/ICamera.h>
#include <gui/ISurfaceTexture.h>
#include <gui/Surface.h>

namespace android {

enum {
    DISCONNECT = IBinder::FIRST_CALL_TRANSACTION,
    SET_PREVIEW_DISPLAY,
    SET_PREVIEW_TEXTURE,
    SET_PREVIEW_CALLBACK_FLAG,
    START_PREVIEW,
    STOP_PREVIEW,
    AUTO_FOCUS,
    CANCEL_AUTO_FOCUS,
    TAKE_PICTURE,
    SET_PARAMETERS,
    GET_PARAMETERS,
    SEND_COMMAND,
    CONNECT,
    LOCK,
    UNLOCK,
    PREVIEW_ENABLED,
    START_RECORDING,
    STOP_RECORDING,
    RECORDING_ENABLED,
    RELEASE_RECORDING_FRAME,
    STORE_META_DATA_IN_BUFFERS,
};

class BpCamera: public BpInterface<ICamera>
{
public:
    BpCamera(const sp<IBinder>& impl)
        : BpInterface<ICamera>(impl)
    {
    }

    // disconnect from camera service
    void disconnect()
    {
        ALOGV("disconnect");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
    }

    // pass the buffered Surface to the camera service
    status_t setPreviewDisplay(const sp<Surface>& surface)
    {
        ALOGV("setPreviewDisplay");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        Surface::writeToParcel(surface, &data);
        remote()->transact(SET_PREVIEW_DISPLAY, data, &reply);
        return reply.readInt32();
    }

    // pass the buffered SurfaceTexture to the camera service
    status_t setPreviewTexture(const sp<ISurfaceTexture>& surfaceTexture)
    {
        ALOGV("setPreviewTexture");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        sp<IBinder> b(surfaceTexture->asBinder());
        data.writeStrongBinder(b);
        remote()->transact(SET_PREVIEW_TEXTURE, data, &reply);
        return reply.readInt32();
    }

    // set the preview callback flag to affect how the received frames from
    // preview are handled. See Camera.h for details.
    void setPreviewCallbackFlag(int flag)
    {
        ALOGV("setPreviewCallbackFlag(%d)", flag);
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeInt32(flag);
        remote()->transact(SET_PREVIEW_CALLBACK_FLAG, data, &reply);
    }

    // start preview mode, must call setPreviewDisplay first
    status_t startPreview()
    {
        ALOGV("startPreview");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(START_PREVIEW, data, &reply);
        return reply.readInt32();
    }

    // start recording mode, must call setPreviewDisplay first
    status_t startRecording()
    {
        ALOGV("startRecording");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(START_RECORDING, data, &reply);
        return reply.readInt32();
    }

    // stop preview mode
    void stopPreview()
    {
        ALOGV("stopPreview");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(STOP_PREVIEW, data, &reply);
    }

    // stop recording mode
    void stopRecording()
    {
        ALOGV("stopRecording");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(STOP_RECORDING, data, &reply);
    }

    void releaseRecordingFrame(const sp<IMemory>& mem)
    {
        ALOGV("releaseRecordingFrame");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeStrongBinder(mem->asBinder());
        remote()->transact(RELEASE_RECORDING_FRAME, data, &reply);
    }

    status_t storeMetaDataInBuffers(bool enabled)
    {
        ALOGV("storeMetaDataInBuffers: %s", enabled? "true": "false");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeInt32(enabled);
        remote()->transact(STORE_META_DATA_IN_BUFFERS, data, &reply);
        return reply.readInt32();
    }

    // check preview state
    bool previewEnabled()
    {
        ALOGV("previewEnabled");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(PREVIEW_ENABLED, data, &reply);
        return reply.readInt32();
    }

    // check recording state
    bool recordingEnabled()
    {
        ALOGV("recordingEnabled");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(RECORDING_ENABLED, data, &reply);
        return reply.readInt32();
    }

    // auto focus
    status_t autoFocus()
    {
        ALOGV("autoFocus");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(AUTO_FOCUS, data, &reply);
        status_t ret = reply.readInt32();
        return ret;
    }

    // cancel focus
    status_t cancelAutoFocus()
    {
        ALOGV("cancelAutoFocus");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(CANCEL_AUTO_FOCUS, data, &reply);
        status_t ret = reply.readInt32();
        return ret;
    }

    // take a picture - returns an IMemory (ref-counted mmap)
    status_t takePicture(int msgType)
    {
        ALOGV("takePicture: 0x%x", msgType);
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeInt32(msgType);
        remote()->transact(TAKE_PICTURE, data, &reply);
        status_t ret = reply.readInt32();
        return ret;
    }

    // set preview/capture parameters - key/value pairs
    status_t setParameters(const String8& params)
    {
        ALOGV("setParameters");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeString8(params);
        remote()->transact(SET_PARAMETERS, data, &reply);
        return reply.readInt32();
    }

    // get preview/capture parameters - key/value pairs
    String8 getParameters() const
    {
        ALOGV("getParameters");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(GET_PARAMETERS, data, &reply);
        return reply.readString8();
    }
    virtual status_t sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
    {
        ALOGV("sendCommand");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeInt32(cmd);
        data.writeInt32(arg1);
        data.writeInt32(arg2);
        remote()->transact(SEND_COMMAND, data, &reply);
        return reply.readInt32();
    }
    virtual status_t connect(const sp<ICameraClient>& cameraClient)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeStrongBinder(cameraClient->asBinder());
        remote()->transact(CONNECT, data, &reply);
        return reply.readInt32();
    }
    virtual status_t lock()
    {
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(LOCK, data, &reply);
        return reply.readInt32();
    }
    virtual status_t unlock()
    {
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(UNLOCK, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(Camera, "android.hardware.ICamera");

// ----------------------------------------------------------------------

status_t BnCamera::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case DISCONNECT: {
            ALOGV("DISCONNECT");
            CHECK_INTERFACE(ICamera, data, reply);
            disconnect();
            return NO_ERROR;
        } break;
        case SET_PREVIEW_DISPLAY: {
            ALOGV("SET_PREVIEW_DISPLAY");
            CHECK_INTERFACE(ICamera, data, reply);
            sp<Surface> surface = Surface::readFromParcel(data);
            reply->writeInt32(setPreviewDisplay(surface));
            return NO_ERROR;
        } break;
        case SET_PREVIEW_TEXTURE: {
            ALOGV("SET_PREVIEW_TEXTURE");
            CHECK_INTERFACE(ICamera, data, reply);
            sp<ISurfaceTexture> st = interface_cast<ISurfaceTexture>(data.readStrongBinder());
            reply->writeInt32(setPreviewTexture(st));
            return NO_ERROR;
        } break;
        case SET_PREVIEW_CALLBACK_FLAG: {
            ALOGV("SET_PREVIEW_CALLBACK_TYPE");
            CHECK_INTERFACE(ICamera, data, reply);
            int callback_flag = data.readInt32();
            setPreviewCallbackFlag(callback_flag);
            return NO_ERROR;
        } break;
        case START_PREVIEW: {
            ALOGV("START_PREVIEW");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(startPreview());
            return NO_ERROR;
        } break;
        case START_RECORDING: {
            ALOGV("START_RECORDING");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(startRecording());
            return NO_ERROR;
        } break;
        case STOP_PREVIEW: {
            ALOGV("STOP_PREVIEW");
            CHECK_INTERFACE(ICamera, data, reply);
            stopPreview();
            return NO_ERROR;
        } break;
        case STOP_RECORDING: {
            ALOGV("STOP_RECORDING");
            CHECK_INTERFACE(ICamera, data, reply);
            stopRecording();
            return NO_ERROR;
        } break;
        case RELEASE_RECORDING_FRAME: {
            ALOGV("RELEASE_RECORDING_FRAME");
            CHECK_INTERFACE(ICamera, data, reply);
            sp<IMemory> mem = interface_cast<IMemory>(data.readStrongBinder());
            releaseRecordingFrame(mem);
            return NO_ERROR;
        } break;
        case STORE_META_DATA_IN_BUFFERS: {
            ALOGV("STORE_META_DATA_IN_BUFFERS");
            CHECK_INTERFACE(ICamera, data, reply);
            bool enabled = data.readInt32();
            reply->writeInt32(storeMetaDataInBuffers(enabled));
            return NO_ERROR;
        } break;
        case PREVIEW_ENABLED: {
            ALOGV("PREVIEW_ENABLED");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(previewEnabled());
            return NO_ERROR;
        } break;
        case RECORDING_ENABLED: {
            ALOGV("RECORDING_ENABLED");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(recordingEnabled());
            return NO_ERROR;
        } break;
        case AUTO_FOCUS: {
            ALOGV("AUTO_FOCUS");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(autoFocus());
            return NO_ERROR;
        } break;
        case CANCEL_AUTO_FOCUS: {
            ALOGV("CANCEL_AUTO_FOCUS");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(cancelAutoFocus());
            return NO_ERROR;
        } break;
        case TAKE_PICTURE: {
            ALOGV("TAKE_PICTURE");
            CHECK_INTERFACE(ICamera, data, reply);
            int msgType = data.readInt32();
            reply->writeInt32(takePicture(msgType));
            return NO_ERROR;
        } break;
        case SET_PARAMETERS: {
            ALOGV("SET_PARAMETERS");
            CHECK_INTERFACE(ICamera, data, reply);
            String8 params(data.readString8());
            reply->writeInt32(setParameters(params));
            return NO_ERROR;
         } break;
        case GET_PARAMETERS: {
            ALOGV("GET_PARAMETERS");
            CHECK_INTERFACE(ICamera, data, reply);
             reply->writeString8(getParameters());
            return NO_ERROR;
         } break;
        case SEND_COMMAND: {
            ALOGV("SEND_COMMAND");
            CHECK_INTERFACE(ICamera, data, reply);
            int command = data.readInt32();
            int arg1 = data.readInt32();
            int arg2 = data.readInt32();
            reply->writeInt32(sendCommand(command, arg1, arg2));
            return NO_ERROR;
         } break;
        case CONNECT: {
            CHECK_INTERFACE(ICamera, data, reply);
            sp<ICameraClient> cameraClient = interface_cast<ICameraClient>(data.readStrongBinder());
            reply->writeInt32(connect(cameraClient));
            return NO_ERROR;
        } break;
        case LOCK: {
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(lock());
            return NO_ERROR;
        } break;
        case UNLOCK: {
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(unlock());
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
