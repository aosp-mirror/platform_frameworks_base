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
#define LOG_TAG "ICameraClient"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <ui/ICameraClient.h>

namespace android {

enum {
    SHUTTER_CALLBACK = IBinder::FIRST_CALL_TRANSACTION,
    RAW_CALLBACK,
    JPEG_CALLBACK,
    FRAME_CALLBACK,
    ERROR_CALLBACK,
    AUTOFOCUS_CALLBACK
};

class BpCameraClient: public BpInterface<ICameraClient>
{
public:
    BpCameraClient(const sp<IBinder>& impl)
        : BpInterface<ICameraClient>(impl)
    {
    }

    // callback to let the app know the shutter has closed, ideal for playing the shutter sound
    void shutterCallback()
    {
        LOGV("shutterCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        remote()->transact(SHUTTER_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // callback from camera service to app with picture data
    void rawCallback(const sp<IMemory>& picture)
    {
        LOGV("rawCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeStrongBinder(picture->asBinder());
        remote()->transact(RAW_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // callback from camera service to app with picture data
    void jpegCallback(const sp<IMemory>& picture)
    {
        LOGV("jpegCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeStrongBinder(picture->asBinder());
        remote()->transact(JPEG_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // callback from camera service to app with video frame data
    void frameCallback(const sp<IMemory>& frame)
    {
        LOGV("frameCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeStrongBinder(frame->asBinder());
        remote()->transact(FRAME_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // callback from camera service to app to report error
    void errorCallback(status_t error)
    {
        LOGV("errorCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeInt32(error);
        remote()->transact(ERROR_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // callback from camera service to app to report autofocus completion
    void autoFocusCallback(bool focused)
    {
        LOGV("autoFocusCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeInt32(focused);
        remote()->transact(AUTOFOCUS_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(CameraClient, "android.hardware.ICameraClient");

// ----------------------------------------------------------------------

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnCameraClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case SHUTTER_CALLBACK: {
            LOGV("SHUTTER_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            shutterCallback();
            return NO_ERROR;
        } break;
        case RAW_CALLBACK: {
            LOGV("RAW_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            sp<IMemory> picture = interface_cast<IMemory>(data.readStrongBinder());
            rawCallback(picture);
            return NO_ERROR;
        } break;
        case JPEG_CALLBACK: {
            LOGV("JPEG_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            sp<IMemory> picture = interface_cast<IMemory>(data.readStrongBinder());
            jpegCallback(picture);
            return NO_ERROR;
        } break;
        case FRAME_CALLBACK: {
            LOGV("FRAME_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            sp<IMemory> frame = interface_cast<IMemory>(data.readStrongBinder());
            frameCallback(frame);
            return NO_ERROR;
        } break;
        case ERROR_CALLBACK: {
            LOGV("ERROR_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            status_t error = data.readInt32();
            errorCallback(error);
            return NO_ERROR;
        } break;
        case AUTOFOCUS_CALLBACK: {
            LOGV("AUTOFOCUS_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            bool focused = (bool)data.readInt32();
            autoFocusCallback(focused);
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

