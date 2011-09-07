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

#define LOG_TAG "IMountService"

#include <storage/IMountService.h>
#include <binder/Parcel.h>

namespace android {

enum {
    TRANSACTION_registerListener = IBinder::FIRST_CALL_TRANSACTION,
    TRANSACTION_unregisterListener,
    TRANSACTION_isUsbMassStorageConnected,
    TRANSACTION_setUsbMassStorageEnabled,
    TRANSACTION_isUsbMassStorageEnabled,
    TRANSACTION_mountVolume,
    TRANSACTION_unmountVolume,
    TRANSACTION_formatVolume,
    TRANSACTION_getStorageUsers,
    TRANSACTION_getVolumeState,
    TRANSACTION_createSecureContainer,
    TRANSACTION_finalizeSecureContainer,
    TRANSACTION_destroySecureContainer,
    TRANSACTION_mountSecureContainer,
    TRANSACTION_unmountSecureContainer,
    TRANSACTION_isSecureContainerMounted,
    TRANSACTION_renameSecureContainer,
    TRANSACTION_getSecureContainerPath,
    TRANSACTION_getSecureContainerList,
    TRANSACTION_shutdown,
    TRANSACTION_finishMediaUpdate,
    TRANSACTION_mountObb,
    TRANSACTION_unmountObb,
    TRANSACTION_isObbMounted,
    TRANSACTION_getMountedObbPath,
    TRANSACTION_isExternalStorageEmulated,
    TRANSACTION_decryptStorage,
    TRANSACTION_encryptStorage,
};

class BpMountService: public BpInterface<IMountService>
{
public:
    BpMountService(const sp<IBinder>& impl)
        : BpInterface<IMountService>(impl)
    {
    }

    virtual void registerListener(const sp<IMountServiceListener>& listener)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        if (remote()->transact(TRANSACTION_registerListener, data, &reply) != NO_ERROR) {
            LOGD("registerListener could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("registerListener caught exception %d\n", err);
            return;
        }
    }

    virtual void unregisterListener(const sp<IMountServiceListener>& listener)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        if (remote()->transact(TRANSACTION_unregisterListener, data, &reply) != NO_ERROR) {
            LOGD("unregisterListener could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("unregisterListener caught exception %d\n", err);
            return;
        }
    }

    virtual bool isUsbMassStorageConnected()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        if (remote()->transact(TRANSACTION_isUsbMassStorageConnected, data, &reply) != NO_ERROR) {
            LOGD("isUsbMassStorageConnected could not contact remote\n");
            return false;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("isUsbMassStorageConnected caught exception %d\n", err);
            return false;
        }
        return reply.readInt32() != 0;
    }

    virtual void setUsbMassStorageEnabled(const bool enable)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeInt32(enable != 0);
        if (remote()->transact(TRANSACTION_setUsbMassStorageEnabled, data, &reply) != NO_ERROR) {
            LOGD("setUsbMassStorageEnabled could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("setUsbMassStorageEnabled caught exception %d\n", err);
            return;
        }
    }

    virtual bool isUsbMassStorageEnabled()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        if (remote()->transact(TRANSACTION_isUsbMassStorageEnabled, data, &reply) != NO_ERROR) {
            LOGD("isUsbMassStorageEnabled could not contact remote\n");
            return false;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("isUsbMassStorageEnabled caught exception %d\n", err);
            return false;
        }
        return reply.readInt32() != 0;
    }

    int32_t mountVolume(const String16& mountPoint)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(mountPoint);
        if (remote()->transact(TRANSACTION_mountVolume, data, &reply) != NO_ERROR) {
            LOGD("mountVolume could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("mountVolume caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t unmountVolume(const String16& mountPoint, const bool force, const bool removeEncryption)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(mountPoint);
        data.writeInt32(force ? 1 : 0);
        data.writeInt32(removeEncryption ? 1 : 0);
        if (remote()->transact(TRANSACTION_unmountVolume, data, &reply) != NO_ERROR) {
            LOGD("unmountVolume could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("unmountVolume caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t formatVolume(const String16& mountPoint)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(mountPoint);
        if (remote()->transact(TRANSACTION_formatVolume, data, &reply) != NO_ERROR) {
            LOGD("formatVolume could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("formatVolume caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t getStorageUsers(const String16& mountPoint, int32_t** users)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(mountPoint);
        if (remote()->transact(TRANSACTION_getStorageUsers, data, &reply) != NO_ERROR) {
            LOGD("getStorageUsers could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("getStorageUsers caught exception %d\n", err);
            return err;
        }
        const int32_t numUsers = reply.readInt32();
        *users = (int32_t*)malloc(sizeof(int32_t)*numUsers);
        for (int i = 0; i < numUsers; i++) {
            **users++ = reply.readInt32();
        }
        return numUsers;
    }

    int32_t getVolumeState(const String16& mountPoint)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(mountPoint);
        if (remote()->transact(TRANSACTION_getVolumeState, data, &reply) != NO_ERROR) {
            LOGD("getVolumeState could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("getVolumeState caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t createSecureContainer(const String16& id, const int32_t sizeMb, const String16& fstype,
            const String16& key, const int32_t ownerUid)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        data.writeInt32(sizeMb);
        data.writeString16(fstype);
        data.writeString16(key);
        data.writeInt32(ownerUid);
        if (remote()->transact(TRANSACTION_createSecureContainer, data, &reply) != NO_ERROR) {
            LOGD("createSecureContainer could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("createSecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t finalizeSecureContainer(const String16& id)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        if (remote()->transact(TRANSACTION_finalizeSecureContainer, data, &reply) != NO_ERROR) {
            LOGD("finalizeSecureContainer couldn't call remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("finalizeSecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t destroySecureContainer(const String16& id)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        if (remote()->transact(TRANSACTION_destroySecureContainer, data, &reply) != NO_ERROR) {
            LOGD("destroySecureContainer couldn't call remote");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("destroySecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t mountSecureContainer(const String16& id, const String16& key, const int32_t ownerUid)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        data.writeString16(key);
        data.writeInt32(ownerUid);
        if (remote()->transact(TRANSACTION_mountSecureContainer, data, &reply) != NO_ERROR) {
            LOGD("mountSecureContainer couldn't call remote");
            return -1;
        }
        int32_t err = reply.readExceptionCode(); // What to do...
        if (err < 0) {
            LOGD("mountSecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t unmountSecureContainer(const String16& id, const bool force)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        data.writeInt32(force ? 1 : 0);
        if (remote()->transact(TRANSACTION_getSecureContainerPath, data, &reply) != NO_ERROR) {
            LOGD("unmountSecureContainer couldn't call remote");
            return -1;
        }
        int32_t err = reply.readExceptionCode(); // What to do...
        if (err < 0) {
            LOGD("unmountSecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    bool isSecureContainerMounted(const String16& id)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        if (remote()->transact(TRANSACTION_isSecureContainerMounted, data, &reply) != NO_ERROR) {
            LOGD("isSecureContainerMounted couldn't call remote");
            return false;
        }
        int32_t err = reply.readExceptionCode(); // What to do...
        if (err < 0) {
            LOGD("isSecureContainerMounted caught exception %d\n", err);
            return false;
        }
        return reply.readInt32() != 0;
    }

    int32_t renameSecureContainer(const String16& oldId, const String16& newId)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(oldId);
        data.writeString16(newId);
        if (remote()->transact(TRANSACTION_renameSecureContainer, data, &reply) != NO_ERROR) {
            LOGD("renameSecureContainer couldn't call remote");
            return -1;
        }
        int32_t err = reply.readExceptionCode(); // What to do...
        if (err < 0) {
            LOGD("renameSecureContainer caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    bool getSecureContainerPath(const String16& id, String16& path)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        if (remote()->transact(TRANSACTION_getSecureContainerPath, data, &reply) != NO_ERROR) {
            LOGD("getSecureContainerPath couldn't call remote");
            return false;
        }
        int32_t err = reply.readExceptionCode(); // What to do...
        if (err < 0) {
            LOGD("getSecureContainerPath caught exception %d\n", err);
            return false;
        }
        path = reply.readString16();
        return true;
    }

    int32_t getSecureContainerList(const String16& id, String16*& containers)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(id);
        if (remote()->transact(TRANSACTION_getSecureContainerList, data, &reply) != NO_ERROR) {
            LOGD("getSecureContainerList couldn't call remote");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("getSecureContainerList caught exception %d\n", err);
            return err;
        }
        const int32_t numStrings = reply.readInt32();
        containers = new String16[numStrings];
        for (int i = 0; i < numStrings; i++) {
            containers[i] = reply.readString16();
        }
        return numStrings;
    }

    void shutdown(const sp<IMountShutdownObserver>& observer)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeStrongBinder(observer->asBinder());
        if (remote()->transact(TRANSACTION_shutdown, data, &reply) != NO_ERROR) {
            LOGD("shutdown could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("shutdown caught exception %d\n", err);
            return;
        }
        reply.readExceptionCode();
    }

    void finishMediaUpdate()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        if (remote()->transact(TRANSACTION_finishMediaUpdate, data, &reply) != NO_ERROR) {
            LOGD("finishMediaUpdate could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("finishMediaUpdate caught exception %d\n", err);
            return;
        }
        reply.readExceptionCode();
    }

    void mountObb(const String16& filename, const String16& key,
            const sp<IObbActionListener>& token, int32_t nonce)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(filename);
        data.writeString16(key);
        data.writeStrongBinder(token->asBinder());
        data.writeInt32(nonce);
        if (remote()->transact(TRANSACTION_mountObb, data, &reply) != NO_ERROR) {
            LOGD("mountObb could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("mountObb caught exception %d\n", err);
            return;
        }
    }

    void unmountObb(const String16& filename, const bool force,
            const sp<IObbActionListener>& token, const int32_t nonce)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(filename);
        data.writeInt32(force ? 1 : 0);
        data.writeStrongBinder(token->asBinder());
        data.writeInt32(nonce);
        if (remote()->transact(TRANSACTION_unmountObb, data, &reply) != NO_ERROR) {
            LOGD("unmountObb could not contact remote\n");
            return;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("unmountObb caught exception %d\n", err);
            return;
        }
    }

    bool isObbMounted(const String16& filename)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(filename);
        if (remote()->transact(TRANSACTION_isObbMounted, data, &reply) != NO_ERROR) {
            LOGD("isObbMounted could not contact remote\n");
            return false;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("isObbMounted caught exception %d\n", err);
            return false;
        }
        return reply.readInt32() != 0;
    }

    bool getMountedObbPath(const String16& filename, String16& path)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(filename);
        if (remote()->transact(TRANSACTION_getMountedObbPath, data, &reply) != NO_ERROR) {
            LOGD("getMountedObbPath could not contact remote\n");
            return false;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("getMountedObbPath caught exception %d\n", err);
            return false;
        }
        path = reply.readString16();
        return true;
    }

    int32_t decryptStorage(const String16& password)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(password);
        if (remote()->transact(TRANSACTION_decryptStorage, data, &reply) != NO_ERROR) {
            LOGD("decryptStorage could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("decryptStorage caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }

    int32_t encryptStorage(const String16& password)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMountService::getInterfaceDescriptor());
        data.writeString16(password);
        if (remote()->transact(TRANSACTION_encryptStorage, data, &reply) != NO_ERROR) {
            LOGD("encryptStorage could not contact remote\n");
            return -1;
        }
        int32_t err = reply.readExceptionCode();
        if (err < 0) {
            LOGD("encryptStorage caught exception %d\n", err);
            return err;
        }
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(MountService, "IMountService");

// ----------------------------------------------------------------------

};
