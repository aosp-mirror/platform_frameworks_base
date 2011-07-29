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
#include <camera/ICameraClient.h>

namespace android {

enum {
    NOTIFY_CALLBACK = IBinder::FIRST_CALL_TRANSACTION,
    DATA_CALLBACK,
    DATA_CALLBACK_TIMESTAMP,
};

class BpCameraClient: public BpInterface<ICameraClient>
{
public:
    BpCameraClient(const sp<IBinder>& impl)
        : BpInterface<ICameraClient>(impl)
    {
    }

    // generic callback from camera service to app
    void notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2)
    {
        LOGV("notifyCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeInt32(msgType);
        data.writeInt32(ext1);
        data.writeInt32(ext2);
        remote()->transact(NOTIFY_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // generic data callback from camera service to app with image data
    void dataCallback(int32_t msgType, const sp<IMemory>& imageData,
                      camera_frame_metadata_t *metadata)
    {
        LOGV("dataCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeInt32(msgType);
        data.writeStrongBinder(imageData->asBinder());
        if (metadata) {
            data.writeInt32(metadata->number_of_faces);
            data.write(metadata->faces, sizeof(camera_face_t) * metadata->number_of_faces);
        }
        remote()->transact(DATA_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // generic data callback from camera service to app with image data
    void dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& imageData)
    {
        LOGV("dataCallback");
        Parcel data, reply;
        data.writeInterfaceToken(ICameraClient::getInterfaceDescriptor());
        data.writeInt64(timestamp);
        data.writeInt32(msgType);
        data.writeStrongBinder(imageData->asBinder());
        remote()->transact(DATA_CALLBACK_TIMESTAMP, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(CameraClient, "android.hardware.ICameraClient");

// ----------------------------------------------------------------------

status_t BnCameraClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case NOTIFY_CALLBACK: {
            LOGV("NOTIFY_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            int32_t msgType = data.readInt32();
            int32_t ext1 = data.readInt32();
            int32_t ext2 = data.readInt32();
            notifyCallback(msgType, ext1, ext2);
            return NO_ERROR;
        } break;
        case DATA_CALLBACK: {
            LOGV("DATA_CALLBACK");
            CHECK_INTERFACE(ICameraClient, data, reply);
            int32_t msgType = data.readInt32();
            sp<IMemory> imageData = interface_cast<IMemory>(data.readStrongBinder());
            camera_frame_metadata_t *metadata = NULL;
            if (data.dataAvail() > 0) {
                metadata = new camera_frame_metadata_t;
                metadata->number_of_faces = data.readInt32();
                metadata->faces = (camera_face_t *) data.readInplace(
                        sizeof(camera_face_t) * metadata->number_of_faces);
            }
            dataCallback(msgType, imageData, metadata);
            if (metadata) delete metadata;
            return NO_ERROR;
        } break;
        case DATA_CALLBACK_TIMESTAMP: {
            LOGV("DATA_CALLBACK_TIMESTAMP");
            CHECK_INTERFACE(ICameraClient, data, reply);
            nsecs_t timestamp = data.readInt64();
            int32_t msgType = data.readInt32();
            sp<IMemory> imageData = interface_cast<IMemory>(data.readStrongBinder());
            dataCallbackTimestamp(timestamp, msgType, imageData);
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

