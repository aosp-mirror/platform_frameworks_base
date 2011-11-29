/*
 * Copyright (C) 2010 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "IStreamSource"
#include <utils/Log.h>

#include <media/IStreamSource.h>
#include <media/stagefright/foundation/AMessage.h>

#include <binder/IMemory.h>
#include <binder/Parcel.h>

namespace android {

// static
const char *const IStreamListener::kKeyResumeAtPTS = "resume-at-PTS";

// static
const char *const IStreamListener::kKeyDiscontinuityMask = "discontinuity-mask";

enum {
    // IStreamSource
    SET_LISTENER = IBinder::FIRST_CALL_TRANSACTION,
    SET_BUFFERS,
    ON_BUFFER_AVAILABLE,

    // IStreamListener
    QUEUE_BUFFER,
    ISSUE_COMMAND,
};

struct BpStreamSource : public BpInterface<IStreamSource> {
    BpStreamSource(const sp<IBinder> &impl)
        : BpInterface<IStreamSource>(impl) {
    }

    virtual void setListener(const sp<IStreamListener> &listener) {
        Parcel data, reply;
        data.writeInterfaceToken(IStreamSource::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        remote()->transact(SET_LISTENER, data, &reply);
    }

    virtual void setBuffers(const Vector<sp<IMemory> > &buffers) {
        Parcel data, reply;
        data.writeInterfaceToken(IStreamSource::getInterfaceDescriptor());
        data.writeInt32(static_cast<int32_t>(buffers.size()));
        for (size_t i = 0; i < buffers.size(); ++i) {
            data.writeStrongBinder(buffers.itemAt(i)->asBinder());
        }
        remote()->transact(SET_BUFFERS, data, &reply);
    }

    virtual void onBufferAvailable(size_t index) {
        Parcel data, reply;
        data.writeInterfaceToken(IStreamSource::getInterfaceDescriptor());
        data.writeInt32(static_cast<int32_t>(index));
        remote()->transact(
                ON_BUFFER_AVAILABLE, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(StreamSource, "android.hardware.IStreamSource");

status_t BnStreamSource::onTransact(
        uint32_t code, const Parcel &data, Parcel *reply, uint32_t flags) {
    switch (code) {
        case SET_LISTENER:
        {
            CHECK_INTERFACE(IStreamSource, data, reply);
            setListener(
                    interface_cast<IStreamListener>(data.readStrongBinder()));
            break;
        }

        case SET_BUFFERS:
        {
            CHECK_INTERFACE(IStreamSource, data, reply);
            size_t n = static_cast<size_t>(data.readInt32());
            Vector<sp<IMemory> > buffers;
            for (size_t i = 0; i < n; ++i) {
                sp<IMemory> mem =
                    interface_cast<IMemory>(data.readStrongBinder());

                buffers.push(mem);
            }
            setBuffers(buffers);
            break;
        }

        case ON_BUFFER_AVAILABLE:
        {
            CHECK_INTERFACE(IStreamSource, data, reply);
            onBufferAvailable(static_cast<size_t>(data.readInt32()));
            break;
        }

        default:
            return BBinder::onTransact(code, data, reply, flags);
    }

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

struct BpStreamListener : public BpInterface<IStreamListener> {
    BpStreamListener(const sp<IBinder> &impl)
        : BpInterface<IStreamListener>(impl) {
    }

    virtual void queueBuffer(size_t index, size_t size) {
        Parcel data, reply;
        data.writeInterfaceToken(IStreamListener::getInterfaceDescriptor());
        data.writeInt32(static_cast<int32_t>(index));
        data.writeInt32(static_cast<int32_t>(size));

        remote()->transact(QUEUE_BUFFER, data, &reply, IBinder::FLAG_ONEWAY);
    }

    virtual void issueCommand(
            Command cmd, bool synchronous, const sp<AMessage> &msg) {
        Parcel data, reply;
        data.writeInterfaceToken(IStreamListener::getInterfaceDescriptor());
        data.writeInt32(static_cast<int32_t>(cmd));
        data.writeInt32(static_cast<int32_t>(synchronous));

        if (msg != NULL) {
            data.writeInt32(1);
            msg->writeToParcel(&data);
        } else {
            data.writeInt32(0);
        }

        remote()->transact(ISSUE_COMMAND, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(StreamListener, "android.hardware.IStreamListener");

status_t BnStreamListener::onTransact(
        uint32_t code, const Parcel &data, Parcel *reply, uint32_t flags) {
    switch (code) {
        case QUEUE_BUFFER:
        {
            CHECK_INTERFACE(IStreamListener, data, reply);
            size_t index = static_cast<size_t>(data.readInt32());
            size_t size = static_cast<size_t>(data.readInt32());

            queueBuffer(index, size);
            break;
        }

        case ISSUE_COMMAND:
        {
            CHECK_INTERFACE(IStreamListener, data, reply);
            Command cmd = static_cast<Command>(data.readInt32());

            bool synchronous = static_cast<bool>(data.readInt32());

            sp<AMessage> msg;

            if (data.readInt32()) {
                msg = AMessage::FromParcel(data);
            }

            issueCommand(cmd, synchronous, msg);
            break;
        }

        default:
            return BBinder::onTransact(code, data, reply, flags);
    }

    return OK;
}

}  // namespace android
