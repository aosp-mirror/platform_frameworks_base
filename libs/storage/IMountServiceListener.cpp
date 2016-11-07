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

#include <storage/IMountServiceListener.h>
#include <binder/Parcel.h>

namespace android {

enum {
    TRANSACTION_onUsbMassStorageConnectionChanged = IBinder::FIRST_CALL_TRANSACTION,
    TRANSACTION_onStorageStateChanged,
};

class BpMountServiceListener: public BpInterface<IMountServiceListener> {
public:
    explicit BpMountServiceListener(const sp<IBinder>& impl)
            : BpInterface<IMountServiceListener>(impl) { }

    virtual void onUsbMassStorageConnectionChanged(const bool /* connected */) { }
    virtual void onStorageStateChanged(const String16& /* path */,
            const String16& /* oldState */, const String16& /* newState */) { }
};

IMPLEMENT_META_INTERFACE(MountServiceListener, "android.os.storage.IStorageEventListener")

// ----------------------------------------------------------------------

status_t BnMountServiceListener::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case TRANSACTION_onUsbMassStorageConnectionChanged: {
            CHECK_INTERFACE(IMountServiceListener, data, reply);
            bool connected = (data.readInt32() != 0);
            onUsbMassStorageConnectionChanged(connected);
            reply->writeNoException();
            return NO_ERROR;
        }
        case TRANSACTION_onStorageStateChanged: {
            CHECK_INTERFACE(IMountServiceListener, data, reply);
            String16 path = data.readString16();
            String16 oldState = data.readString16();
            String16 newState = data.readString16();
            onStorageStateChanged(path, oldState, newState);
            reply->writeNoException();
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}
// ----------------------------------------------------------------------

}
