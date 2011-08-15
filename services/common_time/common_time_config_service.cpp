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

#include <utils/String8.h>

#include "common_time_config_service.h"
#include "common_time_server.h"

namespace android {

sp<CommonTimeConfigService> CommonTimeConfigService::instantiate(
        CommonTimeServer& timeServer) {
    sp<CommonTimeConfigService> ctcs = new CommonTimeConfigService(timeServer);
    if (ctcs == NULL)
        return NULL;

    defaultServiceManager()->addService(ICommonTimeConfig::kServiceName, ctcs);
    return ctcs;
}

status_t CommonTimeConfigService::dump(int fd, const Vector<String16>& args) {
    return mTimeServer.dumpConfigInterface(fd, args);
}

status_t CommonTimeConfigService::getMasterElectionPriority(uint8_t *priority) {
    return mTimeServer.getMasterElectionPriority(priority);
}

status_t CommonTimeConfigService::setMasterElectionPriority(uint8_t priority) {
    return mTimeServer.setMasterElectionPriority(priority);
}

status_t CommonTimeConfigService::getMasterElectionEndpoint(
        struct sockaddr_storage *addr) {
    return mTimeServer.getMasterElectionEndpoint(addr);
}

status_t CommonTimeConfigService::setMasterElectionEndpoint(
        const struct sockaddr_storage *addr) {
    return mTimeServer.setMasterElectionEndpoint(addr);
}

status_t CommonTimeConfigService::getMasterElectionGroupId(uint64_t *id) {
    return mTimeServer.getMasterElectionGroupId(id);
}

status_t CommonTimeConfigService::setMasterElectionGroupId(uint64_t id) {
    return mTimeServer.setMasterElectionGroupId(id);
}

status_t CommonTimeConfigService::getInterfaceBinding(String16& ifaceName) {
    String8 tmp;
    status_t ret = mTimeServer.getInterfaceBinding(tmp);
    ifaceName = String16(tmp);
    return ret;
}

status_t CommonTimeConfigService::setInterfaceBinding(const String16& ifaceName) {
    String8 tmp(ifaceName);
    return mTimeServer.setInterfaceBinding(tmp);
}

status_t CommonTimeConfigService::getMasterAnnounceInterval(int *interval) {
    return mTimeServer.getMasterAnnounceInterval(interval);
}

status_t CommonTimeConfigService::setMasterAnnounceInterval(int interval) {
    return mTimeServer.setMasterAnnounceInterval(interval);
}

status_t CommonTimeConfigService::getClientSyncInterval(int *interval) {
    return mTimeServer.getClientSyncInterval(interval);
}

status_t CommonTimeConfigService::setClientSyncInterval(int interval) {
    return mTimeServer.setClientSyncInterval(interval);
}

status_t CommonTimeConfigService::getPanicThreshold(int *threshold) {
    return mTimeServer.getPanicThreshold(threshold);
}

status_t CommonTimeConfigService::setPanicThreshold(int threshold) {
    return mTimeServer.setPanicThreshold(threshold);
}

status_t CommonTimeConfigService::getAutoDisable(bool *autoDisable) {
    return mTimeServer.getAutoDisable(autoDisable);
}

status_t CommonTimeConfigService::setAutoDisable(bool autoDisable) {
    return mTimeServer.setAutoDisable(autoDisable);
}

status_t CommonTimeConfigService::forceNetworklessMasterMode() {
    return mTimeServer.forceNetworklessMasterMode();
}

}; // namespace android
