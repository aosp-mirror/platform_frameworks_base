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

#ifndef ANDROID_IMOUNTSERVICE_H
#define ANDROID_IMOUNTSERVICE_H

#include <storage/IMountServiceListener.h>
#include <storage/IMountShutdownObserver.h>
#include <storage/IObbActionListener.h>
#include <storage/ObbInfo.h>

#include <utils/String8.h>

#include <binder/IInterface.h>
#include <binder/Parcel.h>

namespace android {

class IMountService: public IInterface {
public:
    DECLARE_META_INTERFACE(MountService);

    virtual void registerListener(const sp<IMountServiceListener>& listener) = 0;
    virtual void
            unregisterListener(const sp<IMountServiceListener>& listener) = 0;
    virtual bool isUsbMassStorageConnected() = 0;
    virtual void setUsbMassStorageEnabled(const bool enable) = 0;
    virtual bool isUsbMassStorageEnabled() = 0;
    virtual int32_t mountVolume(const String16& mountPoint) = 0;
    virtual int32_t unmountVolume(
            const String16& mountPoint, const bool force, const bool removeEncryption) = 0;
    virtual int32_t formatVolume(const String16& mountPoint) = 0;
    virtual int32_t
            getStorageUsers(const String16& mountPoint, int32_t** users) = 0;
    virtual int32_t getVolumeState(const String16& mountPoint) = 0;
    virtual int32_t createSecureContainer(const String16& id,
            const int32_t sizeMb, const String16& fstype, const String16& key,
            const int32_t ownerUid) = 0;
    virtual int32_t finalizeSecureContainer(const String16& id) = 0;
    virtual int32_t destroySecureContainer(const String16& id) = 0;
    virtual int32_t mountSecureContainer(const String16& id,
            const String16& key, const int32_t ownerUid) = 0;
    virtual int32_t
            unmountSecureContainer(const String16& id, const bool force) = 0;
    virtual bool isSecureContainerMounted(const String16& id) = 0;
    virtual int32_t renameSecureContainer(const String16& oldId,
            const String16& newId) = 0;
    virtual bool getSecureContainerPath(const String16& id, String16& path) = 0;
    virtual int32_t getSecureContainerList(const String16& id,
            String16*& containers) = 0;
    virtual void shutdown(const sp<IMountShutdownObserver>& observer) = 0;
    virtual void finishMediaUpdate() = 0;
    virtual void mountObb(const String16& rawPath, const String16& canonicalPath,
            const String16& key, const sp<IObbActionListener>& token,
            const int32_t nonce, const sp<ObbInfo>& obbInfo) = 0;
    virtual void unmountObb(const String16& filename, const bool force,
            const sp<IObbActionListener>& token, const int32_t nonce) = 0;
    virtual bool isObbMounted(const String16& filename) = 0;
    virtual bool getMountedObbPath(const String16& filename, String16& path) = 0;
    virtual int32_t decryptStorage(const String16& password) = 0;
    virtual int32_t encryptStorage(const String16& password) = 0;
};

// ----------------------------------------------------------------------------

class BnMountService: public BnInterface<IMountService> {
public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
            Parcel* reply, uint32_t flags = 0);
};

}
; // namespace android

#endif // ANDROID_IMOUNTSERVICE_H
