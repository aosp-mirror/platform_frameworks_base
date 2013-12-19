/* * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_COMMON_TIME_CONFIG_SERVICE_H
#define ANDROID_COMMON_TIME_CONFIG_SERVICE_H

#include <sys/socket.h>
#include <common_time/ICommonTimeConfig.h>

namespace android {

class String16;
class CommonTimeServer;

class CommonTimeConfigService : public BnCommonTimeConfig {
  public:
    static sp<CommonTimeConfigService> instantiate(CommonTimeServer& timeServer);

    virtual status_t dump(int fd, const Vector<String16>& args);

    virtual status_t getMasterElectionPriority(uint8_t *priority);
    virtual status_t setMasterElectionPriority(uint8_t priority);
    virtual status_t getMasterElectionEndpoint(struct sockaddr_storage *addr);
    virtual status_t setMasterElectionEndpoint(const struct sockaddr_storage *addr);
    virtual status_t getMasterElectionGroupId(uint64_t *id);
    virtual status_t setMasterElectionGroupId(uint64_t id);
    virtual status_t getInterfaceBinding(String16& ifaceName);
    virtual status_t setInterfaceBinding(const String16& ifaceName);
    virtual status_t getMasterAnnounceInterval(int *interval);
    virtual status_t setMasterAnnounceInterval(int interval);
    virtual status_t getClientSyncInterval(int *interval);
    virtual status_t setClientSyncInterval(int interval);
    virtual status_t getPanicThreshold(int *threshold);
    virtual status_t setPanicThreshold(int threshold);
    virtual status_t getAutoDisable(bool *autoDisable);
    virtual status_t setAutoDisable(bool autoDisable);
    virtual status_t forceNetworklessMasterMode();

  private:
    CommonTimeConfigService(CommonTimeServer& timeServer)
        : mTimeServer(timeServer) { }
    CommonTimeServer& mTimeServer;

};

};  // namespace android

#endif  // ANDROID_COMMON_TIME_CONFIG_SERVICE_H
