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

#include <binder/IServiceManager.h>

#include "common_time_config_service.h"

namespace android {

sp<CommonTimeConfigService> CommonTimeConfigService::instantiate() {
    sp<CommonTimeConfigService> ctcs = new CommonTimeConfigService();

    defaultServiceManager()->addService(ICommonTimeConfig::kServiceName, ctcs);
    return ctcs;
}

status_t CommonTimeConfigService::getMasterElectionPriority(uint8_t *priority) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setMasterElectionPriority(uint8_t priority) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getMasterElectionEndpoint(
        struct sockaddr_storage *addr) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setMasterElectionEndpoint(
        const struct sockaddr_storage *addr) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getMasterElectionGroupId(uint64_t *id) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setMasterElectionGroupId(uint64_t id) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getInterfaceBinding(String16& ifaceName) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setInterfaceBinding(const String16& ifaceName) {
        return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getMasterAnnounceInterval(int *interval) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setMasterAnnounceInterval(int interval) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getClientSyncInterval(int *interval) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setClientSyncInterval(int interval) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getPanicThreshold(int *threshold) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setPanicThreshold(int threshold) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::getAutoDisable(bool *autoDisable) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::setAutoDisable(bool autoDisable) {
    return UNKNOWN_ERROR;
}

status_t CommonTimeConfigService::forceNetworklessMasterMode() {
    return UNKNOWN_ERROR;
}

}; // namespace android
