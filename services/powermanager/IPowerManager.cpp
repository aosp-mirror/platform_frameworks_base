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

#define LOG_TAG "IPowerManager"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <powermanager/IPowerManager.h>

namespace android {

// must be kept in sync with IPowerManager.aidl
enum {
    ACQUIRE_WAKE_LOCK = IBinder::FIRST_CALL_TRANSACTION,
    RELEASE_WAKE_LOCK = IBinder::FIRST_CALL_TRANSACTION + 4,
};

class BpPowerManager : public BpInterface<IPowerManager>
{
public:
    BpPowerManager(const sp<IBinder>& impl)
        : BpInterface<IPowerManager>(impl)
    {
    }

    virtual status_t acquireWakeLock(int flags, const sp<IBinder>& lock, const String16& tag)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IPowerManager::getInterfaceDescriptor());

        data.writeInt32(flags);
        data.writeStrongBinder(lock);
        data.writeString16(tag);
        // no WorkSource passed
        data.writeInt32(0);
        return remote()->transact(ACQUIRE_WAKE_LOCK, data, &reply);
    }

    virtual status_t releaseWakeLock(const sp<IBinder>& lock, int flags)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IPowerManager::getInterfaceDescriptor());
        data.writeStrongBinder(lock);
        data.writeInt32(flags);
        return remote()->transact(RELEASE_WAKE_LOCK, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(PowerManager, "android.os.IPowerManager");

// ----------------------------------------------------------------------------

}; // namespace android
