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

#include <aah_timesrv/ICommonClock.h>
#include <binder/Parcel.h>

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
    REGISTER_LISTENER,
    UNREGISTER_LISTENER,
};

const String16 ICommonClock::kServiceName("aah.common_clock");
const uint32_t ICommonClock::kInvalidTimelineID = 0;

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
            *valid = reply.readInt32();
            *timelineID = reply.readInt32();
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
            *localTime = reply.readInt64();
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
            *commonTime = reply.readInt64();
        }
        return status;
    }

    virtual status_t getCommonTime(int64_t* commonTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_COMMON_TIME, data, &reply);
        if (status == OK) {
            *commonTime = reply.readInt64();
        }
        return status;
    }

    virtual status_t getCommonFreq(uint64_t* freq) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_COMMON_FREQ, data, &reply);
        if (status == OK) {
            *freq = reply.readInt64();
        }
        return status;
    }

    virtual status_t getLocalTime(int64_t* localTime) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_LOCAL_TIME, data, &reply);
        if (status == OK) {
            *localTime = reply.readInt64();
        }
        return status;
    }

    virtual status_t getLocalFreq(uint64_t* freq) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_LOCAL_FREQ, data, &reply);
        if (status == OK) {
            *freq = reply.readInt64();
        }
        return status;
    }

    virtual status_t registerListener(
            const sp<ICommonClockListener>& listener) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        return remote()->transact(REGISTER_LISTENER, data, &reply);
    }

    virtual status_t unregisterListener(
            const sp<ICommonClockListener>& listener) {
        Parcel data, reply;
        data.writeInterfaceToken(ICommonClock::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        return remote()->transact(UNREGISTER_LISTENER, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(CommonClock, "android.aah.CommonClock");

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
            if (status == OK) {
                reply->writeInt32(valid);
                reply->writeInt32(timelineID);
            }
            return status;
        } break;

        case COMMON_TIME_TO_LOCAL_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t commonTime = data.readInt64();
            int64_t localTime;
            status_t status = commonTimeToLocalTime(commonTime, &localTime);
            if (status == OK) {
                reply->writeInt64(localTime);
            }
            return status;
        } break;

        case LOCAL_TIME_TO_COMMON_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t localTime = data.readInt64();
            int64_t commonTime;
            status_t status = localTimeToCommonTime(localTime, &commonTime);
            if (status == OK) {
                reply->writeInt64(commonTime);
            }
            return status;
        } break;

        case GET_COMMON_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t commonTime;
            status_t status = getCommonTime(&commonTime);
            if (status == OK) {
                reply->writeInt64(commonTime);
            }
            return status;
        } break;

        case GET_COMMON_FREQ: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            uint64_t freq;
            status_t status = getCommonFreq(&freq);
            if (status == OK) {
                reply->writeInt64(freq);
            }
            return status;
        } break;

        case GET_LOCAL_TIME: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            int64_t localTime;
            status_t status = getLocalTime(&localTime);
            if (status == OK) {
                reply->writeInt64(localTime);
            }
            return status;
        } break;

        case GET_LOCAL_FREQ: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            uint64_t freq;
            status_t status = getLocalFreq(&freq);
            if (status == OK) {
                reply->writeInt64(freq);
            }
            return status;
        } break;

        case REGISTER_LISTENER: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            sp<ICommonClockListener> listener =
                interface_cast<ICommonClockListener>(data.readStrongBinder());
            return registerListener(listener);
        } break;

        case UNREGISTER_LISTENER: {
            CHECK_INTERFACE(ICommonClock, data, reply);
            sp<ICommonClockListener> listener =
                interface_cast<ICommonClockListener>(data.readStrongBinder());
            return unregisterListener(listener);
        } break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

/***** ICommonClockListener *****/

enum {
    ON_CLOCK_SYNC = IBinder::FIRST_CALL_TRANSACTION,
    ON_CLOCK_SYNC_LOSS,
};

class BpCommonClockListener : public BpInterface<ICommonClockListener>
{
  public:
    BpCommonClockListener(const sp<IBinder>& impl)
        : BpInterface<ICommonClockListener>(impl) {}

    virtual void onClockSync(uint32_t timelineID) {
        Parcel data, reply;
        data.writeInterfaceToken(
                ICommonClockListener::getInterfaceDescriptor());
        data.writeInt32(timelineID);
        remote()->transact(ON_CLOCK_SYNC, data, &reply);
    }

    virtual void onClockSyncLoss() {
        Parcel data, reply;
        data.writeInterfaceToken(
                ICommonClockListener::getInterfaceDescriptor());
        remote()->transact(ON_CLOCK_SYNC_LOSS, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(CommonClockListener,
                         "android.aah.CommonClockListener");

status_t BnCommonClockListener::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    switch(code) {
        case ON_CLOCK_SYNC: {
            CHECK_INTERFACE(ICommonClockListener, data, reply);
            uint32_t timelineID = data.readInt32();
            onClockSync(timelineID);
            return NO_ERROR;
        } break;

        case ON_CLOCK_SYNC_LOSS: {
            CHECK_INTERFACE(ICommonClockListener, data, reply);
            onClockSyncLoss();
            return NO_ERROR;
        } break;
    }

    return BBinder::onTransact(code, data, reply, flags);
}

}; // namespace android
