/*
 * Copyright (C) 2011 The Android Open Source Project
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
#include <linux/socket.h>

#include <common_time/ICommonTimeConfig.h>
#include <binder/Parcel.h>

#include "utils.h"

namespace android {

/***** ICommonTimeConfig *****/

enum {
    GET_MASTER_ELECTION_PRIORITY = IBinder::FIRST_CALL_TRANSACTION,
    SET_MASTER_ELECTION_PRIORITY,
    GET_MASTER_ELECTION_ENDPOINT,
    SET_MASTER_ELECTION_ENDPOINT,
    GET_MASTER_ELECTION_GROUP_ID,
    SET_MASTER_ELECTION_GROUP_ID,
    GET_INTERFACE_BINDING,
    SET_INTERFACE_BINDING,
    GET_MASTER_ANNOUNCE_INTERVAL,
    SET_MASTER_ANNOUNCE_INTERVAL,
    GET_CLIENT_SYNC_INTERVAL,
    SET_CLIENT_SYNC_INTERVAL,
    GET_PANIC_THRESHOLD,
    SET_PANIC_THRESHOLD,
    GET_AUTO_DISABLE,
    SET_AUTO_DISABLE,
    FORCE_NETWORKLESS_MASTER_MODE,
};

const String16 ICommonTimeConfig::kServiceName("common_time.config");

class BpCommonTimeConfig : public BpInterface<ICommonTimeConfig>
{
  public:
    BpCommonTimeConfig(const sp<IBinder>& impl)
        : BpInterface<ICommonTimeConfig>(impl) {}

    virtual status_t getMasterElectionPriority(uint8_t *priority) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_MASTER_ELECTION_PRIORITY,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *priority = static_cast<uint8_t>(reply.readInt32());
            }
        }

        return status;
    }

    virtual status_t setMasterElectionPriority(uint8_t priority) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt32(static_cast<int32_t>(priority));
        status_t status = remote()->transact(SET_MASTER_ELECTION_PRIORITY,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getMasterElectionEndpoint(struct sockaddr_storage *addr) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_MASTER_ELECTION_ENDPOINT,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                deserializeSockaddr(&reply, addr);
            }
        }

        return status;
    }

    virtual status_t setMasterElectionEndpoint(
            const struct sockaddr_storage *addr) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        if (!canSerializeSockaddr(addr))
            return BAD_VALUE;
        if (NULL == addr) {
            data.writeInt32(0);
        } else {
            data.writeInt32(1);
            serializeSockaddr(&data, addr);
        }
        status_t status = remote()->transact(SET_MASTER_ELECTION_ENDPOINT,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getMasterElectionGroupId(uint64_t *id) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_MASTER_ELECTION_GROUP_ID,
                                             data,
                                             &reply);

        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *id = static_cast<uint64_t>(reply.readInt64());
            }
        }

        return status;
    }

    virtual status_t setMasterElectionGroupId(uint64_t id) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt64(id);
        status_t status = remote()->transact(SET_MASTER_ELECTION_GROUP_ID,
                                             data,
                                             &reply);

        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getInterfaceBinding(String16& ifaceName) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_INTERFACE_BINDING,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                ifaceName = reply.readString16();
            }
        }

        return status;
    }

    virtual status_t setInterfaceBinding(const String16& ifaceName) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeString16(ifaceName);
        status_t status = remote()->transact(SET_INTERFACE_BINDING,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getMasterAnnounceInterval(int *interval) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_MASTER_ANNOUNCE_INTERVAL,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *interval = reply.readInt32();
            }
        }

        return status;
    }

    virtual status_t setMasterAnnounceInterval(int interval) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt32(interval);
        status_t status = remote()->transact(SET_MASTER_ANNOUNCE_INTERVAL,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getClientSyncInterval(int *interval) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_CLIENT_SYNC_INTERVAL,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *interval = reply.readInt32();
            }
        }

        return status;
    }

    virtual status_t setClientSyncInterval(int interval) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt32(interval);
        status_t status = remote()->transact(SET_CLIENT_SYNC_INTERVAL,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getPanicThreshold(int *threshold) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_PANIC_THRESHOLD,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *threshold = reply.readInt32();
            }
        }

        return status;
    }

    virtual status_t setPanicThreshold(int threshold) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt32(threshold);
        status_t status = remote()->transact(SET_PANIC_THRESHOLD,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t getAutoDisable(bool *autoDisable) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_AUTO_DISABLE,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *autoDisable = (0 != reply.readInt32());
            }
        }

        return status;
    }

    virtual status_t setAutoDisable(bool autoDisable) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        data.writeInt32(autoDisable ? 1 : 0);
        status_t status = remote()->transact(SET_AUTO_DISABLE,
                                             data,
                                             &reply);

        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t forceNetworklessMasterMode() {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonTimeConfig::getInterfaceDescriptor());
        status_t status = remote()->transact(FORCE_NETWORKLESS_MASTER_MODE,
                                             data,
                                             &reply);

        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }
};

IMPLEMENT_META_INTERFACE(CommonTimeConfig, "android.os.ICommonTimeConfig");

status_t BnCommonTimeConfig::onTransact(uint32_t code,
                                   const Parcel& data,
                                   Parcel* reply,
                                   uint32_t flags) {
    switch(code) {
        case GET_MASTER_ELECTION_PRIORITY: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            uint8_t priority;
            status_t status = getMasterElectionPriority(&priority);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(static_cast<int32_t>(priority));
            }
            return OK;
        } break;

        case SET_MASTER_ELECTION_PRIORITY: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            uint8_t priority = static_cast<uint8_t>(data.readInt32());
            status_t status = setMasterElectionPriority(priority);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_MASTER_ELECTION_ENDPOINT: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            struct sockaddr_storage addr;
            status_t status = getMasterElectionEndpoint(&addr);

            if ((status == OK) && !canSerializeSockaddr(&addr)) {
                status = UNKNOWN_ERROR;
            }

            reply->writeInt32(status);

            if (status == OK) {
                serializeSockaddr(reply, &addr);
            }

            return OK;
        } break;

        case SET_MASTER_ELECTION_ENDPOINT: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            struct sockaddr_storage addr;
            int hasAddr = data.readInt32();

            status_t status;
            if (hasAddr) {
                deserializeSockaddr(&data, &addr);
                status = setMasterElectionEndpoint(&addr);
            } else {
                status = setMasterElectionEndpoint(&addr);
            }

            reply->writeInt32(status);
            return OK;
        } break;

        case GET_MASTER_ELECTION_GROUP_ID: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            uint64_t id;
            status_t status = getMasterElectionGroupId(&id);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(id);
            }
            return OK;
        } break;

        case SET_MASTER_ELECTION_GROUP_ID: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            uint64_t id = static_cast<uint64_t>(data.readInt64());
            status_t status = setMasterElectionGroupId(id);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_INTERFACE_BINDING: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            String16 ret;
            status_t status = getInterfaceBinding(ret);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeString16(ret);
            }
            return OK;
        } break;

        case SET_INTERFACE_BINDING: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            String16 ifaceName;
            ifaceName = data.readString16();
            status_t status = setInterfaceBinding(ifaceName);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_MASTER_ANNOUNCE_INTERVAL: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int interval;
            status_t status = getMasterAnnounceInterval(&interval);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(interval);
            }
            return OK;
        } break;

        case SET_MASTER_ANNOUNCE_INTERVAL: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int interval = data.readInt32();
            status_t status = setMasterAnnounceInterval(interval);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_CLIENT_SYNC_INTERVAL: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int interval;
            status_t status = getClientSyncInterval(&interval);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(interval);
            }
            return OK;
        } break;

        case SET_CLIENT_SYNC_INTERVAL: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int interval = data.readInt32();
            status_t status = setClientSyncInterval(interval);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_PANIC_THRESHOLD: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int threshold;
            status_t status = getPanicThreshold(&threshold);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(threshold);
            }
            return OK;
        } break;

        case SET_PANIC_THRESHOLD: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            int threshold = data.readInt32();
            status_t status = setPanicThreshold(threshold);
            reply->writeInt32(status);
            return OK;
        } break;

        case GET_AUTO_DISABLE: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            bool autoDisable;
            status_t status = getAutoDisable(&autoDisable);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(autoDisable ? 1 : 0);
            }
            return OK;
        } break;

        case SET_AUTO_DISABLE: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            bool autoDisable = (0 != data.readInt32());
            status_t status = setAutoDisable(autoDisable);
            reply->writeInt32(status);
            return OK;
        } break;

        case FORCE_NETWORKLESS_MASTER_MODE: {
            CHECK_INTERFACE(ICommonTimeConfig, data, reply);
            status_t status = forceNetworklessMasterMode();
            reply->writeInt32(status);
            return OK;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

}; // namespace android

