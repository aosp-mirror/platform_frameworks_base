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

#ifndef ANDROID_ICOMMONTIMECONFIG_H
#define ANDROID_ICOMMONTIMECONFIG_H

#include <stdint.h>
#include <linux/socket.h>

#include <binder/IInterface.h>
#include <binder/IServiceManager.h>

namespace android {

class String16;

class ICommonTimeConfig : public IInterface {
  public:
    DECLARE_META_INTERFACE(CommonTimeConfig);

    // Name of the ICommonTimeConfig service registered with the service
    // manager.
    static const String16 kServiceName;

    virtual status_t getMasterElectionPriority(uint8_t *priority) = 0;
    virtual status_t setMasterElectionPriority(uint8_t priority) = 0;
    virtual status_t getMasterElectionEndpoint(struct sockaddr_storage *addr) = 0;
    virtual status_t setMasterElectionEndpoint(const struct sockaddr_storage *addr) = 0;
    virtual status_t getMasterElectionGroupId(uint64_t *id) = 0;
    virtual status_t setMasterElectionGroupId(uint64_t id) = 0;
    virtual status_t getInterfaceBinding(String16& ifaceName) = 0;
    virtual status_t setInterfaceBinding(const String16& ifaceName) = 0;
    virtual status_t getMasterAnnounceInterval(int *interval) = 0;
    virtual status_t setMasterAnnounceInterval(int interval) = 0;
    virtual status_t getClientSyncInterval(int *interval) = 0;
    virtual status_t setClientSyncInterval(int interval) = 0;
    virtual status_t getPanicThreshold(int *threshold) = 0;
    virtual status_t setPanicThreshold(int threshold) = 0;
    virtual status_t getAutoDisable(bool *autoDisable) = 0;
    virtual status_t setAutoDisable(bool autoDisable) = 0;
    virtual status_t forceNetworklessMasterMode() = 0;

    // Simple helper to make it easier to connect to the CommonTimeConfig service.
    static inline sp<ICommonTimeConfig> getInstance() {
        sp<IBinder> binder = defaultServiceManager()->checkService(
                ICommonTimeConfig::kServiceName);
        sp<ICommonTimeConfig> clk = interface_cast<ICommonTimeConfig>(binder);
        return clk;
    }
};

class BnCommonTimeConfig : public BnInterface<ICommonTimeConfig> {
  public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
                                Parcel* reply, uint32_t flags = 0);
};

};  // namespace android

#endif  // ANDROID_ICOMMONTIMECONFIG_H
