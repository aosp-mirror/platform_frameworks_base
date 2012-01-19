/*
**
** Copyright 2010, The Android Open Source Project
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
#define LOG_TAG "IEffect"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <media/IEffect.h>

namespace android {

enum {
    ENABLE = IBinder::FIRST_CALL_TRANSACTION,
    DISABLE,
    COMMAND,
    DISCONNECT,
    GET_CBLK
};

class BpEffect: public BpInterface<IEffect>
{
public:
    BpEffect(const sp<IBinder>& impl)
        : BpInterface<IEffect>(impl)
    {
    }

    status_t enable()
    {
        ALOGV("enable");
        Parcel data, reply;
        data.writeInterfaceToken(IEffect::getInterfaceDescriptor());
        remote()->transact(ENABLE, data, &reply);
        return reply.readInt32();
    }

    status_t disable()
    {
        ALOGV("disable");
        Parcel data, reply;
        data.writeInterfaceToken(IEffect::getInterfaceDescriptor());
        remote()->transact(DISABLE, data, &reply);
        return reply.readInt32();
    }

    status_t command(uint32_t cmdCode,
                     uint32_t cmdSize,
                     void *pCmdData,
                     uint32_t *pReplySize,
                     void *pReplyData)
    {
        ALOGV("command");
        Parcel data, reply;
        data.writeInterfaceToken(IEffect::getInterfaceDescriptor());
        data.writeInt32(cmdCode);
        int size = cmdSize;
        if (pCmdData == NULL) {
            size = 0;
        }
        data.writeInt32(size);
        if (size) {
            data.write(pCmdData, size);
        }
        if (pReplySize == NULL) {
            size = 0;
        } else {
            size = *pReplySize;
        }
        data.writeInt32(size);
        remote()->transact(COMMAND, data, &reply);
        status_t status = reply.readInt32();
        size = reply.readInt32();
        if (size != 0 && pReplyData != NULL && pReplySize != NULL) {
            reply.read(pReplyData, size);
            *pReplySize = size;
        }
        return status;
    }

    void disconnect()
    {
        ALOGV("disconnect");
        Parcel data, reply;
        data.writeInterfaceToken(IEffect::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
        return;
    }

    virtual sp<IMemory> getCblk() const
    {
        Parcel data, reply;
        sp<IMemory> cblk;
        data.writeInterfaceToken(IEffect::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_CBLK, data, &reply);
        if (status == NO_ERROR) {
            cblk = interface_cast<IMemory>(reply.readStrongBinder());
        }
        return cblk;
    }
 };

IMPLEMENT_META_INTERFACE(Effect, "android.media.IEffect");

// ----------------------------------------------------------------------

status_t BnEffect::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case ENABLE: {
            ALOGV("ENABLE");
            CHECK_INTERFACE(IEffect, data, reply);
            reply->writeInt32(enable());
            return NO_ERROR;
        } break;

        case DISABLE: {
            ALOGV("DISABLE");
            CHECK_INTERFACE(IEffect, data, reply);
            reply->writeInt32(disable());
            return NO_ERROR;
        } break;

        case COMMAND: {
            ALOGV("COMMAND");
            CHECK_INTERFACE(IEffect, data, reply);
            uint32_t cmdCode = data.readInt32();
            uint32_t cmdSize = data.readInt32();
            char *cmd = NULL;
            if (cmdSize) {
                cmd = (char *)malloc(cmdSize);
                data.read(cmd, cmdSize);
            }
            uint32_t replySize = data.readInt32();
            uint32_t replySz = replySize;
            char *resp = NULL;
            if (replySize) {
                resp = (char *)malloc(replySize);
            }
            status_t status = command(cmdCode, cmdSize, cmd, &replySz, resp);
            reply->writeInt32(status);
            if (replySz < replySize) {
                replySize = replySz;
            }
            reply->writeInt32(replySize);
            if (replySize) {
                reply->write(resp, replySize);
            }
            if (cmd) {
                free(cmd);
            }
            if (resp) {
                free(resp);
            }
            return NO_ERROR;
        } break;

        case DISCONNECT: {
            ALOGV("DISCONNECT");
            CHECK_INTERFACE(IEffect, data, reply);
            disconnect();
            return NO_ERROR;
        } break;

        case GET_CBLK: {
             CHECK_INTERFACE(IEffect, data, reply);
             reply->writeStrongBinder(getCblk()->asBinder());
             return NO_ERROR;
         } break;

        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

