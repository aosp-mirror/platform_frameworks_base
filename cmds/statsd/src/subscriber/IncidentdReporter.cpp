/*
 * Copyright (C) 2018 The Android Open Source Project
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
#define DEBUG false
#include "Log.h"

#include "IncidentdReporter.h"

#include <android/os/IIncidentManager.h>
#include <android/os/IncidentReportArgs.h>
#include <android/util/ProtoOutputStream.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>

#include <vector>

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;
using std::vector;

using util::FIELD_TYPE_MESSAGE;
using util::FIELD_TYPE_INT32;
using util::FIELD_TYPE_INT64;

// field ids in IncidentHeaderProto
const int FIELD_ID_ALERT_ID = 1;
const int FIELD_ID_CONFIG_KEY = 3;
const int FIELD_ID_CONFIG_KEY_UID = 1;
const int FIELD_ID_CONFIG_KEY_ID = 2;

namespace {
void getProtoData(const int64_t& rule_id, const ConfigKey& configKey, vector<uint8_t>* protoData) {
    ProtoOutputStream headerProto;
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_ALERT_ID, (long long)rule_id);
    uint64_t token =
            headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_CONFIG_KEY);
    headerProto.write(FIELD_TYPE_INT32 | FIELD_ID_CONFIG_KEY_UID, configKey.GetUid());
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_CONFIG_KEY_ID, (long long)configKey.GetId());
    headerProto.end(token);

    protoData->resize(headerProto.size());
    size_t pos = 0;
    auto iter = headerProto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&((*protoData)[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }
}
}  // namespace

bool GenerateIncidentReport(const IncidentdDetails& config, const int64_t& rule_id,
                            const ConfigKey& configKey) {
    if (config.section_size() == 0) {
        VLOG("The alert %lld contains zero section in config(%d,%lld)", (unsigned long long)rule_id,
             configKey.GetUid(), (long long)configKey.GetId());
        return false;
    }

    IncidentReportArgs incidentReport;

    vector<uint8_t> protoData;
    getProtoData(rule_id, configKey, &protoData);
    incidentReport.addHeader(protoData);

    for (int i = 0; i < config.section_size(); i++) {
        incidentReport.addSection(config.section(i));
    }

    uint8_t dest;
    switch (config.dest()) {
        case IncidentdDetails_Destination_AUTOMATIC:
            dest = android::os::DEST_AUTOMATIC;
            break;
        case IncidentdDetails_Destination_EXPLICIT:
            dest = android::os::DEST_EXPLICIT;
            break;
        default:
            dest = android::os::DEST_AUTOMATIC;
    }
    incidentReport.setDest(dest);

    sp<IIncidentManager> service = interface_cast<IIncidentManager>(
            defaultServiceManager()->getService(android::String16("incident")));
    if (service == nullptr) {
        ALOGW("Failed to fetch incident service.");
        return false;
    }
    VLOG("Calling incidentd %p", service.get());
    binder::Status s = service->reportIncident(incidentReport);
    VLOG("Report incident status: %s", s.toString8().string());
    return s.isOk();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
