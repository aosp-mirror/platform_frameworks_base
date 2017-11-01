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

#include <storage/IMountShutdownObserver.h>
#include <binder/Parcel.h>

namespace android {

enum {
    TRANSACTION_onShutDownComplete = IBinder::FIRST_CALL_TRANSACTION,
};

class BpMountShutdownObserver: public BpInterface<IMountShutdownObserver> {
public:
    explicit BpMountShutdownObserver(const sp<IBinder>& impl)
            : BpInterface<IMountShutdownObserver>(impl) { }

    virtual void onShutDownComplete(const int32_t /* statusCode */) {}
};

IMPLEMENT_META_INTERFACE(MountShutdownObserver, "android.os.storage.IStorageShutdownObserver")

status_t BnMountShutdownObserver::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case TRANSACTION_onShutDownComplete: {
            CHECK_INTERFACE(IMountShutdownObserver, data, reply);
            int32_t statusCode = data.readInt32();
            onShutDownComplete(statusCode);
            reply->writeNoException();
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}
// ----------------------------------------------------------------------

}
