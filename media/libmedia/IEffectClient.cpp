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
#define LOG_TAG "IEffectClient"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <media/IEffectClient.h>

namespace android {

enum {
    CONTROL_STATUS_CHANGED = IBinder::FIRST_CALL_TRANSACTION,
    ENABLE_STATUS_CHANGED,
    COMMAND_EXECUTED
};

class BpEffectClient: public BpInterface<IEffectClient>
{
public:
    BpEffectClient(const sp<IBinder>& impl)
        : BpInterface<IEffectClient>(impl)
    {
    }

    void controlStatusChanged(bool controlGranted)
    {
        ALOGV("controlStatusChanged");
        Parcel data, reply;
        data.writeInterfaceToken(IEffectClient::getInterfaceDescriptor());
        data.writeInt32((uint32_t)controlGranted);
        remote()->transact(CONTROL_STATUS_CHANGED, data, &reply, IBinder::FLAG_ONEWAY);
    }

    void enableStatusChanged(bool enabled)
    {
        ALOGV("enableStatusChanged");
        Parcel data, reply;
        data.writeInterfaceToken(IEffectClient::getInterfaceDescriptor());
        data.writeInt32((uint32_t)enabled);
        remote()->transact(ENABLE_STATUS_CHANGED, data, &reply, IBinder::FLAG_ONEWAY);
    }

    void commandExecuted(uint32_t cmdCode,
                         uint32_t cmdSize,
                         void *pCmdData,
                         uint32_t replySize,
                         void *pReplyData)
    {
        ALOGV("commandExecuted");
        Parcel data, reply;
        data.writeInterfaceToken(IEffectClient::getInterfaceDescriptor());
        data.writeInt32(cmdCode);
        int size = cmdSize;
        if (pCmdData == NULL) {
            size = 0;
        }
        data.writeInt32(size);
        if (size) {
            data.write(pCmdData, size);
        }
        size = replySize;
        if (pReplyData == NULL) {
            size = 0;
        }
        data.writeInt32(size);
        if (size) {
            data.write(pReplyData, size);
        }
        remote()->transact(COMMAND_EXECUTED, data, &reply, IBinder::FLAG_ONEWAY);
    }

};

IMPLEMENT_META_INTERFACE(EffectClient, "android.media.IEffectClient");

// ----------------------------------------------------------------------

status_t BnEffectClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case CONTROL_STATUS_CHANGED: {
            ALOGV("CONTROL_STATUS_CHANGED");
            CHECK_INTERFACE(IEffectClient, data, reply);
            bool hasControl = (bool)data.readInt32();
            controlStatusChanged(hasControl);
            return NO_ERROR;
        } break;
        case ENABLE_STATUS_CHANGED: {
            ALOGV("ENABLE_STATUS_CHANGED");
            CHECK_INTERFACE(IEffectClient, data, reply);
            bool enabled = (bool)data.readInt32();
            enableStatusChanged(enabled);
            return NO_ERROR;
        } break;
        case COMMAND_EXECUTED: {
            ALOGV("COMMAND_EXECUTED");
            CHECK_INTERFACE(IEffectClient, data, reply);
            uint32_t cmdCode = data.readInt32();
            uint32_t cmdSize = data.readInt32();
            char *cmd = NULL;
            if (cmdSize) {
                cmd = (char *)malloc(cmdSize);
                data.read(cmd, cmdSize);
            }
            uint32_t replySize = data.readInt32();
            char *resp = NULL;
            if (replySize) {
                resp = (char *)malloc(replySize);
                data.read(resp, replySize);
            }
            commandExecuted(cmdCode, cmdSize, cmd, replySize, resp);
            if (cmd) {
                free(cmd);
            }
            if (resp) {
                free(resp);
            }
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
