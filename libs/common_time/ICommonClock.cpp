/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <common_time/ICommonClock.h>
#include <binder/Parcel.h>

#include "utils.h"

namespace android {

/***** ICommonClock *****/

enum {
    IS_COMMON_TIME_VALID = IBinder::FIRST_CALL_TRANSACTION,
    COMMON_TIME_TO_LOCAL_TIME,
    LOCAL_TIME_TO_COMMON_TIME,
    GET_COMMON_TIME,
    GET_COMMON_FREQ,
    GET_LOCAL_TIME,
    GET_LOCAL_FREQ,
    GET_ESTIMATED_ERROR,
    GET_TIMELINE_ID,
    GET_STATE,
    GET_MASTER_ADDRESS,
    REGISTER_LISTENER,
    UNREGISTER_LISTENER,
};

const String16 ICommonClock::kServiceName("common_time.clock");
const uint64_t ICommonClock::kInvalidTimelineID = 0;
const int32_t ICommonClock::kErrorEstimateUnknown = 0x7FFFFFFF;

class BpCommonClock : public BpInterface<ICommonClock>
{
  public:
    BpCommonClock(const sp<IBinder>& impl)
        : BpInterface<ICommonClock>(impl) {}

    virtual status_t isCommonTimeValid(bool* valid, uint32_t* timelineID) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(IS_COMMON_TIME_VALID,
                                             data,
                                             &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *valid = reply.readInt32();
                *timelineID = reply.readInt32();
            }
        }
        return status;
    }

    virtual status_t commonTimeToLocalTime(int64_t commonTime,
            int64_t* localTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeInt64(commonTime);
        status_t status = remote()->transact(COMMON_TIME_TO_LOCAL_TIME,
                data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *localTime = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t localTimeToCommonTime(int64_t localTime,
            int64_t* commonTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeInt64(localTime);
        status_t status = remote()->transact(LOCAL_TIME_TO_COMMON_TIME,
                data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *commonTime = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t getCommonTime(int64_t* commonTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_COMMON_TIME, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *commonTime = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t getCommonFreq(uint64_t* freq) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_COMMON_FREQ, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *freq = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t getLocalTime(int64_t* localTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_LOCAL_TIME, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *localTime = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t getLocalFreq(uint64_t* freq) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_LOCAL_FREQ, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *freq = reply.readInt64();
            }
        }
        return status;
    }

    virtual status_t getEstimatedError(int32_t* estimate) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_ESTIMATED_ERROR, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *estimate = reply.readInt32();
            }
        }
        return status;
    }

    virtual status_t getTimelineID(uint64_t* id) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_TIMELINE_ID, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *id = static_cast<uint64_t>(reply.readInt64());
            }
        }
        return status;
    }

    virtual status_t getState(State* state) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_STATE, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK) {
                *state = static_cast<State>(reply.readInt32());
            }
        }
        return status;
    }

    virtual status_t getMasterAddr(struct sockaddr_storage* addr) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_MASTER_ADDRESS, data, &reply);
        if (status == OK) {
            status = reply.readInt32();
            if (status == OK)
                deserializeSockaddr(&reply, addr);
        }
        return status;
    }

    virtual status_t registerListener(
            const sp<ICommonClockListener>& listener) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());

        status_t status = remote()->transact(REGISTER_LISTENER, data, &reply);

        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }

    virtual status_t unregisterListener(
            const sp<ICommonClockListener>& listener) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        status_t status = remote()->transact(UNREGISTER_LISTENER, data, &reply);

        if (status == OK) {
            status = reply.readInt32();
        }

        return status;
    }
};

IMPLEMENT_META_INTERFACE(CommonClock, "android.os.ICommonClock");

status_t BnCommonClock::onTransact(uint32_t code,
                                   const Parcel& data,
                                   Parcel* reply,
                                   uint32_t flags) {
    switch(code) {
        case IS_COMMON_TIME_VALID: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            bool valid;
            uint32_t timelineID;
            status_t status = isCommonTimeValid(&valid, &timelineID);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(valid);
                reply->writeInt32(timelineID);
            }
            return OK;
        } break;

        case COMMON_TIME_TO_LOCAL_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t commonTime = data.readInt64();
            int64_t localTime;
            status_t status = commonTimeToLocalTime(commonTime, &localTime);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(localTime);
            }
            return OK;
        } break;

        case LOCAL_TIME_TO_COMMON_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t localTime = data.readInt64();
            int64_t commonTime;
            status_t status = localTimeToCommonTime(localTime, &commonTime);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(commonTime);
            }
            return OK;
        } break;

        case GET_COMMON_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t commonTime;
            status_t status = getCommonTime(&commonTime);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(commonTime);
            }
            return OK;
        } break;

        case GET_COMMON_FREQ: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            uint64_t freq;
            status_t status = getCommonFreq(&freq);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(freq);
            }
            return OK;
        } break;

        case GET_LOCAL_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t localTime;
            status_t status = getLocalTime(&localTime);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(localTime);
            }
            return OK;
        } break;

        case GET_LOCAL_FREQ: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            uint64_t freq;
            status_t status = getLocalFreq(&freq);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(freq);
            }
            return OK;
        } break;

        case GET_ESTIMATED_ERROR: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int32_t error;
            status_t status = getEstimatedError(&error);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(error);
            }
            return OK;
        } break;

        case GET_TIMELINE_ID: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            uint64_t id;
            status_t status = getTimelineID(&id);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt64(static_cast<int64_t>(id));
            }
            return OK;
        } break;

        case GET_STATE: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            State state;
            status_t status = getState(&state);
            reply->writeInt32(status);
            if (status == OK) {
                reply->writeInt32(static_cast<int32_t>(state));
            }
            return OK;
        } break;

        case GET_MASTER_ADDRESS: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            struct sockaddr_storage addr;
            status_t status = getMasterAddr(&addr);

            if ((status == OK) && !canSerializeSockaddr(&addr)) {
                status = UNKNOWN_ERROR;
            }

            reply->writeInt32(status);

            if (status == OK) {
                serializeSockaddr(reply, &addr);
            }

            return OK;
        } break;

        case REGISTER_LISTENER: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            sp<ICommonClockListener> listener =
                interface_cast<ICommonClockListener>(data.readStrongBinder());
            status_t status = registerListener(listener);
            reply->writeInt32(status);
            return OK;
        } break;

        case UNREGISTER_LISTENER: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            sp<ICommonClockListener> listener =
                interface_cast<ICommonClockListener>(data.readStrongBinder());
            status_t status = unregisterListener(listener);
            reply->writeInt32(status);
            return OK;
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

/***** ICommonClockListener *****/

enum {
    ON_TIMELINE_CHANGED = IBinder::FIRST_CALL_TRANSACTION,
};

class BpCommonClockListener : public BpInterface<ICommonClockListener>
{
  public:
    BpCommonClockListener(const sp<IBinder>& impl)
        : BpInterface<ICommonClockListener>(impl) {}

    virtual void onTimelineChanged(uint64_t timelineID) {
        Parcel data, reply;
        data.writeInterfaceToken(
                ICommonClockListener::getInterfaceDescriptor());
        data.writeInt64(timelineID);
        remote()->transact(ON_TIMELINE_CHANGED, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(CommonClockListener,
                         "android.os.ICommonClockListener");

status_t BnCommonClockListener::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    switch(code) {
        case ON_TIMELINE_CHANGED: {
            CHECK_INTERFACE(ICommonClockListener, data, reply);
            uint32_t timelineID = data.readInt64();
            onTimelineChanged(timelineID);
            return NO_ERROR;
        } break;
    }

    return BBinder::onTransact(code, data, reply, flags);
}

}; // namespace android
