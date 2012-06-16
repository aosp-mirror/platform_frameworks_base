/*
 **
 ** Copyright 2012, The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <media/stagefright/Utils.h>

#include <time.h>

#include "IAAHMetaData.h"

namespace android {

enum {
    NOTIFY = IBinder::FIRST_CALL_TRANSACTION,
    FLUSH = IBinder::FIRST_CALL_TRANSACTION + 1,
};

class BpAAHMetaDataClient : public BpInterface<IAAHMetaDataClient> {
 public:
    BpAAHMetaDataClient(const sp<IBinder>& impl)
            : BpInterface<IAAHMetaDataClient>(impl) {
    }

    virtual void notify(uint16_t typeId, uint32_t item_len, const void* buf) {
        Parcel data, reply;
        data.writeInterfaceToken(IAAHMetaDataClient::getInterfaceDescriptor());
        data.writeInt32((int32_t) typeId);
        data.writeInt32((int32_t) item_len);
        data.write(buf, item_len);
        remote()->transact(NOTIFY, data, &reply, IBinder::FLAG_ONEWAY);
    }
    virtual void flush() {
        Parcel data, reply;
        data.writeInterfaceToken(IAAHMetaDataClient::getInterfaceDescriptor());
        remote()->transact(FLUSH, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(AAHMetaDataClient, "android.media.IAAHMetaDataClient");

// ----------------------------------------------------------------------

status_t BnAAHMetaDataClient::onTransact(uint32_t code, const Parcel& data,
                                         Parcel* reply, uint32_t flags) {
    switch (code) {
        case NOTIFY: {
            CHECK_INTERFACE(IAAHMetaDataClient, data, reply);
            uint16_t typeId = (uint16_t) data.readInt32();
            uint32_t item_len = (uint32_t) data.readInt32();
            const void* buf = data.readInplace(item_len);

            notify(typeId, item_len, buf);
            return NO_ERROR;
        }
            break;
        case FLUSH: {
            CHECK_INTERFACE(IAAHMetaDataClient, data, reply);
            flush();
            return NO_ERROR;
        }
            break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

enum {
    ADDCLIENT = IBinder::FIRST_CALL_TRANSACTION,
    REMOVECLIENT = ADDCLIENT + 1,
};

class BpAAHMetaDataService : public BpInterface<IAAHMetaDataService> {
 public:
    BpAAHMetaDataService(const sp<IBinder>& impl)
            : BpInterface<IAAHMetaDataService>(impl) {
    }

    virtual void addClient(const sp<IAAHMetaDataClient>& client) {
        Parcel data, reply;
        data.writeInterfaceToken(IAAHMetaDataService::getInterfaceDescriptor());
        data.writeStrongBinder(client->asBinder());
        remote()->transact(ADDCLIENT, data, &reply, IBinder::FLAG_ONEWAY);
    }

    virtual void removeClient(const sp<IAAHMetaDataClient>& client) {
        Parcel data, reply;
        data.writeInterfaceToken(IAAHMetaDataService::getInterfaceDescriptor());
        data.writeStrongBinder(client->asBinder());
        remote()->transact(REMOVECLIENT, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(AAHMetaDataService, "android.media.IAAHMetaDataService");

// ----------------------------------------------------------------------

status_t BnAAHMetaDataService::onTransact(uint32_t code, const Parcel& data,
                                          Parcel* reply, uint32_t flags) {
    switch (code) {
        case ADDCLIENT: {
            CHECK_INTERFACE(IAAHMetaDataService, data, reply);
            sp<IAAHMetaDataClient> client = interface_cast < IAAHMetaDataClient
                    > (data.readStrongBinder());

            addClient(client);
            return NO_ERROR;
        }
            break;
        case REMOVECLIENT: {
            CHECK_INTERFACE(IAAHMetaDataService, data, reply);
            sp<IAAHMetaDataClient> client = interface_cast < IAAHMetaDataClient
                    > (data.readStrongBinder());

            removeClient(client);
            return NO_ERROR;
        }
            break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

static bool s_aah_metadata_service_initialized = false;
static sp<AAHMetaDataService> s_aah_metadata_service = NULL;
static Mutex s_aah_metadata_service_lock;

const sp<AAHMetaDataService>& AAHMetaDataService::getInstance() {
    Mutex::Autolock autolock(&s_aah_metadata_service_lock);
    if (!s_aah_metadata_service_initialized) {
        s_aah_metadata_service = new AAHMetaDataService();
        status_t ret = android::defaultServiceManager()->addService(
                IAAHMetaDataService::descriptor, s_aah_metadata_service);
        if (ret != 0) {
            LOGE("failed to add AAHMetaDataService error code %d", ret);
            s_aah_metadata_service = NULL;
        }
        s_aah_metadata_service_initialized = true;
    }
    return s_aah_metadata_service;
}

AAHMetaDataService::AAHMetaDataService() {
}

void AAHMetaDataService::addClient(const sp<IAAHMetaDataClient>& client) {
    Mutex::Autolock lock(mLock);
    IAAHMetaDataClient* obj = client.get();
    LOGV("addClient %p", obj);
    client->asBinder()->linkToDeath(this);
    mClients.add(client);
}

void AAHMetaDataService::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mLock);
    for (uint32_t i = 0; i < mClients.size(); ++i) {
        const sp<IAAHMetaDataClient>& c = mClients[i];
        if (who == c->asBinder()) {
            LOGD("IAAHMetaDataClient binder Died");
            LOGV("removed died client %p", c.get());
            mClients.removeAt(i);
            return;
        }
    }
}

void AAHMetaDataService::removeClient(const sp<IAAHMetaDataClient>& client) {
    IAAHMetaDataClient* obj = client.get();
    Mutex::Autolock lock(mLock);
    for (uint32_t i = 0; i < mClients.size(); ++i) {
        const sp<IAAHMetaDataClient>& c = mClients[i];
        if (c->asBinder() == client->asBinder()) {
            LOGV("removeClient %p", c.get());
            mClients.removeAt(i);
            return;
        }
    }
}

void AAHMetaDataService::broadcast(uint16_t typeId, uint32_t item_len,
                                   void* data) {
    LOGV("broadcast %d", typeId);
    Mutex::Autolock lock(mLock);
    uint8_t* buf = reinterpret_cast<uint8_t*>(data);
    for (uint32_t i = 0; i < mClients.size(); ++i) {
        const sp<IAAHMetaDataClient> c = mClients[i];
        LOGV("notify %p", c.get());
        c->notify(typeId, item_len, data);
    }
}

void AAHMetaDataService::flush() {
    Mutex::Autolock lock(mLock);
    for (uint32_t i = 0; i < mClients.size(); ++i) {
        const sp<IAAHMetaDataClient> c = mClients[i];
        c->flush();
    }
}
}
;
// namespace android
