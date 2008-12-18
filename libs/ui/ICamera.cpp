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
#include <utils/Parcel.h>
#include <ui/ICamera.h>

namespace android {

enum {
    DISCONNECT = IBinder::FIRST_CALL_TRANSACTION,
    SET_PREVIEW_DISPLAY,
    SET_FRAME_CALLBACK_FLAG,
    START_PREVIEW,
    STOP_PREVIEW,
    AUTO_FOCUS,
    TAKE_PICTURE,
    SET_PARAMETERS,
    GET_PARAMETERS,
    CONNECT
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
        LOGV("disconnect");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
    }

    // pass the buffered ISurface to the camera service
    status_t setPreviewDisplay(const sp<ISurface>& surface)
    {
        LOGV("setPreviewDisplay");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeStrongBinder(surface->asBinder());
        remote()->transact(SET_PREVIEW_DISPLAY, data, &reply);
        return reply.readInt32();
    }

    // set the frame callback flag to affect how the received frames from
    // preview are handled.
    void setFrameCallbackFlag(int frame_callback_flag)
    {
        LOGV("setFrameCallbackFlag(%d)", frame_callback_flag);
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeInt32(frame_callback_flag);
        remote()->transact(SET_FRAME_CALLBACK_FLAG, data, &reply);
    }

    // start preview mode, must call setPreviewDisplay first
    status_t startPreview()
    {
        LOGV("startPreview");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(START_PREVIEW, data, &reply);
        return reply.readInt32();
    }

    // stop preview mode
    void stopPreview()
    {
        LOGV("stopPreview");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(STOP_PREVIEW, data, &reply);
    }

    // auto focus
    status_t autoFocus()
    {
        LOGV("autoFocus");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(AUTO_FOCUS, data, &reply);
        status_t ret = reply.readInt32();
        return ret;
    }

    // take a picture - returns an IMemory (ref-counted mmap)
    status_t takePicture()
    {
        LOGV("takePicture");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(TAKE_PICTURE, data, &reply);
        status_t ret = reply.readInt32();
        return ret;
    }

    // set preview/capture parameters - key/value pairs
    status_t setParameters(const String8& params)
    {
        LOGV("setParameters");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeString8(params);
        remote()->transact(SET_PARAMETERS, data, &reply);
        return reply.readInt32();
    }

    // get preview/capture parameters - key/value pairs
    String8 getParameters() const
    {
        LOGV("getParameters");
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        remote()->transact(GET_PARAMETERS, data, &reply);
        return reply.readString8();
    }
    virtual status_t connect(const sp<ICameraClient>& cameraClient)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ICamera::getInterfaceDescriptor());
        data.writeStrongBinder(cameraClient->asBinder());
        remote()->transact(CONNECT, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(Camera, "android.hardware.ICamera");

// ----------------------------------------------------------------------

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnCamera::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case DISCONNECT: {
            LOGV("DISCONNECT");
            CHECK_INTERFACE(ICamera, data, reply);
            disconnect();
            return NO_ERROR;
        } break;
        case SET_PREVIEW_DISPLAY: {
            LOGV("SET_PREVIEW_DISPLAY");
            CHECK_INTERFACE(ICamera, data, reply);
            sp<ISurface> surface = interface_cast<ISurface>(data.readStrongBinder());
            reply->writeInt32(setPreviewDisplay(surface));
            return NO_ERROR;
        } break;
        case SET_FRAME_CALLBACK_FLAG: {
            LOGV("SET_FRAME_CALLBACK_TYPE");
            CHECK_INTERFACE(ICamera, data, reply);
            int frame_callback_flag = data.readInt32();
            setFrameCallbackFlag(frame_callback_flag);
            return NO_ERROR;
        } break;
        case START_PREVIEW: {
            LOGV("START_PREVIEW");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(startPreview());
            return NO_ERROR;
        } break;
        case STOP_PREVIEW: {
            LOGV("STOP_PREVIEW");
            CHECK_INTERFACE(ICamera, data, reply);
            stopPreview();
            return NO_ERROR;
        } break;
        case AUTO_FOCUS: {
            LOGV("AUTO_FOCUS");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(autoFocus());
            return NO_ERROR;
        } break;
        case TAKE_PICTURE: {
            LOGV("TAKE_PICTURE");
            CHECK_INTERFACE(ICamera, data, reply);
            reply->writeInt32(takePicture());
            return NO_ERROR;
        } break;
        case SET_PARAMETERS: {
            LOGV("SET_PARAMETERS");
            CHECK_INTERFACE(ICamera, data, reply);
            String8 params(data.readString8());
            reply->writeInt32(setParameters(params));
            return NO_ERROR;
         } break;
        case GET_PARAMETERS: {
            LOGV("GET_PARAMETERS");
            CHECK_INTERFACE(ICamera, data, reply);
             reply->writeString8(getParameters());
            return NO_ERROR;
         } break;
        case CONNECT: {
            CHECK_INTERFACE(ICamera, data, reply);
            sp<ICameraClient> cameraClient = interface_cast<ICameraClient>(data.readStrongBinder());
            reply->writeInt32(connect(cameraClient));
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

