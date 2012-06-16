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

#ifndef ANDROID_IAAHMETADATA_H
#define ANDROID_IAAHMETADATA_H

#include <utils/SortedVector.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

#include "utils.h"

namespace android {

class IAAHMetaDataClient : public IInterface {
 public:
    DECLARE_META_INTERFACE (AAHMetaDataClient);

    virtual void notify(uint16_t typeId, uint32_t item_len,
                        const void* data) = 0;
    virtual void flush() = 0;
};

// ----------------------------------------------------------------------------

class BnAAHMetaDataClient : public BnInterface<IAAHMetaDataClient> {
 public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
                                Parcel* reply, uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

class IAAHMetaDataService : public IInterface {
 public:
    DECLARE_META_INTERFACE (AAHMetaDataService);

    virtual void addClient(const sp<IAAHMetaDataClient>& client) = 0;
    virtual void removeClient(const sp<IAAHMetaDataClient>& client) = 0;
};

// ----------------------------------------------------------------------------

class BnAAHMetaDataService : public BnInterface<IAAHMetaDataService> {
 public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
                                Parcel* reply, uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

class AAHMetaDataService : public BnAAHMetaDataService,
        public android::IBinder::DeathRecipient {
 public:
    static const sp<AAHMetaDataService>& getInstance();
    void broadcast(uint16_t typeId, uint32_t item_len, void* data);
    void flush();
    virtual void addClient(const sp<IAAHMetaDataClient>& client);
    virtual void removeClient(const sp<IAAHMetaDataClient>& client);
    virtual void binderDied(const wp<IBinder>& who);
 private:
    AAHMetaDataService();

    SortedVector<sp<IAAHMetaDataClient> > mClients;
    Mutex mLock;

};

}
;
// namespace android

#endif // ANDROID_IAAHMETADATA_H
