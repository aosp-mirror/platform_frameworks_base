/*
**
** Copyright (C) 2008 The Android Open Source Project
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

#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <SkBitmap.h>
#include <media/IMediaMetadataRetriever.h>
#include <utils/String8.h>

// The binder is supposed to propagate the scheduler group across
// the binder interface so that remote calls are executed with
// the same priority as local calls. This is currently not working
// so this change puts in a temporary hack to fix the issue with
// metadata retrieval which can be a huge CPU hit if done on a
// foreground thread.
#ifndef DISABLE_GROUP_SCHEDULE_HACK

#undef LOG_TAG
#define LOG_TAG "IMediaMetadataRetriever"
#include <utils/Log.h>
#include <cutils/sched_policy.h>

namespace android {

static void sendSchedPolicy(Parcel& data)
{
    SchedPolicy policy;
    get_sched_policy(gettid(), &policy);
    data.writeInt32(policy);
}

static void setSchedPolicy(const Parcel& data)
{
    SchedPolicy policy = (SchedPolicy) data.readInt32();
    set_sched_policy(gettid(), policy);
}
static void restoreSchedPolicy()
{
    set_sched_policy(gettid(), SP_FOREGROUND);
}
}; // end namespace android
#endif

namespace android {

enum {
    DISCONNECT = IBinder::FIRST_CALL_TRANSACTION,
    SET_DATA_SOURCE_URL,
    SET_DATA_SOURCE_FD,
    GET_FRAME_AT_TIME,
    EXTRACT_ALBUM_ART,
    EXTRACT_METADATA,
};

class BpMediaMetadataRetriever: public BpInterface<IMediaMetadataRetriever>
{
public:
    BpMediaMetadataRetriever(const sp<IBinder>& impl)
        : BpInterface<IMediaMetadataRetriever>(impl)
    {
    }

    // disconnect from media metadata retriever service
    void disconnect()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
    }

    status_t setDataSource(
            const char *srcUrl, const KeyedVector<String8, String8> *headers)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
        data.writeCString(srcUrl);

        if (headers == NULL) {
            data.writeInt32(0);
        } else {
            // serialize the headers
            data.writeInt32(headers->size());
            for (size_t i = 0; i < headers->size(); ++i) {
                data.writeString8(headers->keyAt(i));
                data.writeString8(headers->valueAt(i));
            }
        }

        remote()->transact(SET_DATA_SOURCE_URL, data, &reply);
        return reply.readInt32();
    }

    status_t setDataSource(int fd, int64_t offset, int64_t length)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
        data.writeFileDescriptor(fd);
        data.writeInt64(offset);
        data.writeInt64(length);
        remote()->transact(SET_DATA_SOURCE_FD, data, &reply);
        return reply.readInt32();
    }

    sp<IMemory> getFrameAtTime(int64_t timeUs, int option)
    {
        ALOGV("getTimeAtTime: time(%lld us) and option(%d)", timeUs, option);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
        data.writeInt64(timeUs);
        data.writeInt32(option);
#ifndef DISABLE_GROUP_SCHEDULE_HACK
        sendSchedPolicy(data);
#endif
        remote()->transact(GET_FRAME_AT_TIME, data, &reply);
        status_t ret = reply.readInt32();
        if (ret != NO_ERROR) {
            return NULL;
        }
        return interface_cast<IMemory>(reply.readStrongBinder());
    }

    sp<IMemory> extractAlbumArt()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
#ifndef DISABLE_GROUP_SCHEDULE_HACK
        sendSchedPolicy(data);
#endif
        remote()->transact(EXTRACT_ALBUM_ART, data, &reply);
        status_t ret = reply.readInt32();
        if (ret != NO_ERROR) {
            return NULL;
        }
        return interface_cast<IMemory>(reply.readStrongBinder());
    }

    const char* extractMetadata(int keyCode)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaMetadataRetriever::getInterfaceDescriptor());
#ifndef DISABLE_GROUP_SCHEDULE_HACK
        sendSchedPolicy(data);
#endif
        data.writeInt32(keyCode);
        remote()->transact(EXTRACT_METADATA, data, &reply);
        status_t ret = reply.readInt32();
        if (ret != NO_ERROR) {
            return NULL;
        }
        return reply.readCString();
    }
};

IMPLEMENT_META_INTERFACE(MediaMetadataRetriever, "android.media.IMediaMetadataRetriever");

// ----------------------------------------------------------------------

status_t BnMediaMetadataRetriever::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case DISCONNECT: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
            disconnect();
            return NO_ERROR;
        } break;
        case SET_DATA_SOURCE_URL: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
            const char* srcUrl = data.readCString();

            KeyedVector<String8, String8> headers;
            int32_t numHeaders = data.readInt32();
            for (int i = 0; i < numHeaders; ++i) {
                String8 key = data.readString8();
                String8 value = data.readString8();
                headers.add(key, value);
            }

            reply->writeInt32(
                    setDataSource(srcUrl, numHeaders > 0 ? &headers : NULL));

            return NO_ERROR;
        } break;
        case SET_DATA_SOURCE_FD: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
            int fd = dup(data.readFileDescriptor());
            int64_t offset = data.readInt64();
            int64_t length = data.readInt64();
            reply->writeInt32(setDataSource(fd, offset, length));
            return NO_ERROR;
        } break;
        case GET_FRAME_AT_TIME: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
            int64_t timeUs = data.readInt64();
            int option = data.readInt32();
            ALOGV("getTimeAtTime: time(%lld us) and option(%d)", timeUs, option);
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            setSchedPolicy(data);
#endif
            sp<IMemory> bitmap = getFrameAtTime(timeUs, option);
            if (bitmap != 0) {  // Don't send NULL across the binder interface
                reply->writeInt32(NO_ERROR);
                reply->writeStrongBinder(bitmap->asBinder());
            } else {
                reply->writeInt32(UNKNOWN_ERROR);
            }
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            restoreSchedPolicy();
#endif
            return NO_ERROR;
        } break;
        case EXTRACT_ALBUM_ART: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            setSchedPolicy(data);
#endif
            sp<IMemory> albumArt = extractAlbumArt();
            if (albumArt != 0) {  // Don't send NULL across the binder interface
                reply->writeInt32(NO_ERROR);
                reply->writeStrongBinder(albumArt->asBinder());
            } else {
                reply->writeInt32(UNKNOWN_ERROR);
            }
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            restoreSchedPolicy();
#endif
            return NO_ERROR;
        } break;
        case EXTRACT_METADATA: {
            CHECK_INTERFACE(IMediaMetadataRetriever, data, reply);
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            setSchedPolicy(data);
#endif
            int keyCode = data.readInt32();
            const char* value = extractMetadata(keyCode);
            if (value != NULL) {  // Don't send NULL across the binder interface
                reply->writeInt32(NO_ERROR);
                reply->writeCString(value);
            } else {
                reply->writeInt32(UNKNOWN_ERROR);
            }
#ifndef DISABLE_GROUP_SCHEDULE_HACK
            restoreSchedPolicy();
#endif
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
