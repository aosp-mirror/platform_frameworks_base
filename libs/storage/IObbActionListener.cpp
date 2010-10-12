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

#include <storage/IObbActionListener.h>
#include <binder/Parcel.h>

namespace android {

enum {
    TRANSACTION_onObbResult = IBinder::FIRST_CALL_TRANSACTION,
};

// This is a stub that real consumers should override.
class BpObbActionListener: public BpInterface<IObbActionListener> {
public:
    BpObbActionListener(const sp<IBinder>& impl)
        : BpInterface<IObbActionListener>(impl)
    { }

    virtual void onObbResult(const String16& filename, const int32_t nonce, const int32_t state) { }
};

IMPLEMENT_META_INTERFACE(ObbActionListener, "IObbActionListener");

// ----------------------------------------------------------------------

status_t BnObbActionListener::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case TRANSACTION_onObbResult: {
            CHECK_INTERFACE(IObbActionListener, data, reply);
            String16 filename = data.readString16();
            int32_t nonce = data.readInt32();
            int32_t state = data.readInt32();
            onObbResult(filename, nonce, state);
            reply->writeNoException();
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------

};
