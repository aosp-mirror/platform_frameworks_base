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

#include "FieldValue.h"
#include "IncidentdReporter.h"
#include "packages/UidMap.h"
#include "stats_log_util.h"

#include <android/util/ProtoOutputStream.h>
#include <incident/incident_report.h>

#include <vector>

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;
using std::vector;

using util::FIELD_TYPE_INT32;
using util::FIELD_TYPE_INT64;
using util::FIELD_TYPE_MESSAGE;
using util::FIELD_TYPE_STRING;

// field ids in IncidentHeaderProto
const int FIELD_ID_ALERT_ID = 1;
const int FIELD_ID_REASON = 2;
const int FIELD_ID_CONFIG_KEY = 3;
const int FIELD_ID_CONFIG_KEY_UID = 1;
const int FIELD_ID_CONFIG_KEY_ID = 2;

const int FIELD_ID_TRIGGER_DETAILS = 4;
const int FIELD_ID_TRIGGER_DETAILS_TRIGGER_METRIC = 1;
const int FIELD_ID_METRIC_VALUE_METRIC_ID = 1;
const int FIELD_ID_METRIC_VALUE_DIMENSION_IN_WHAT = 2;
const int FIELD_ID_METRIC_VALUE_VALUE = 4;

const int FIELD_ID_PACKAGE_INFO = 3;

namespace {
void getProtoData(const int64_t& rule_id, int64_t metricId, const MetricDimensionKey& dimensionKey,
                  int64_t metricValue, const ConfigKey& configKey, const string& reason,
                  vector<uint8_t>* protoData) {
    ProtoOutputStream headerProto;
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_ALERT_ID, (long long)rule_id);
    headerProto.write(FIELD_TYPE_STRING | FIELD_ID_REASON, reason);
    uint64_t token =
            headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_CONFIG_KEY);
    headerProto.write(FIELD_TYPE_INT32 | FIELD_ID_CONFIG_KEY_UID, configKey.GetUid());
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_CONFIG_KEY_ID, (long long)configKey.GetId());
    headerProto.end(token);

    token = headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_TRIGGER_DETAILS);

    // MetricValue trigger_metric = 1;
    uint64_t metricToken =
            headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_TRIGGER_DETAILS_TRIGGER_METRIC);
    // message MetricValue {
    // optional int64 metric_id = 1;
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_METRIC_VALUE_METRIC_ID, (long long)metricId);
    // optional DimensionsValue dimension_in_what = 2;
    uint64_t dimToken =
            headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_METRIC_VALUE_DIMENSION_IN_WHAT);
    writeDimensionToProto(dimensionKey.getDimensionKeyInWhat(), nullptr, &headerProto);
    headerProto.end(dimToken);

    // deprecated field
    // optional DimensionsValue dimension_in_condition = 3;

    // optional int64 value = 4;
    headerProto.write(FIELD_TYPE_INT64 | FIELD_ID_METRIC_VALUE_VALUE, (long long)metricValue);

    // }
    headerProto.end(metricToken);

    // write relevant uid package info
    std::set<int32_t> uids;

    for (const auto& dim : dimensionKey.getDimensionKeyInWhat().getValues()) {
        int uid = getUidIfExists(dim);
        // any uid <= 2000 are predefined AID_*
        if (uid > 2000) {
            uids.insert(uid);
        }
    }

    if (!uids.empty()) {
        uint64_t token = headerProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_PACKAGE_INFO);
        UidMap::getInstance()->writeUidMapSnapshot(getElapsedRealtimeNs(), true, true, uids,
                                                   nullptr /*string set*/, &headerProto);
        headerProto.end(token);
    }

    headerProto.end(token);

    protoData->resize(headerProto.size());
    size_t pos = 0;
    sp<android::util::ProtoReader> reader = headerProto.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&((*protoData)[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }
}
}  // namespace

bool GenerateIncidentReport(const IncidentdDetails& config, int64_t rule_id, int64_t metricId,
                            const MetricDimensionKey& dimensionKey, int64_t metricValue,
                            const ConfigKey& configKey) {
    if (config.section_size() == 0) {
        VLOG("The alert %lld contains zero section in config(%d,%lld)", (unsigned long long)rule_id,
             configKey.GetUid(), (long long)configKey.GetId());
        return false;
    }

    AIncidentReportArgs* args = AIncidentReportArgs_init();

    vector<uint8_t> protoData;
    getProtoData(rule_id, metricId, dimensionKey, metricValue, configKey,
                 config.alert_description(), &protoData);
    AIncidentReportArgs_addHeader(args, protoData.data(), protoData.size());

    for (int i = 0; i < config.section_size(); i++) {
        AIncidentReportArgs_addSection(args, config.section(i));
    }

    uint8_t dest;
    switch (config.dest()) {
        case IncidentdDetails_Destination_AUTOMATIC:
            dest = INCIDENT_REPORT_PRIVACY_POLICY_AUTOMATIC;
            break;
        case IncidentdDetails_Destination_EXPLICIT:
            dest = INCIDENT_REPORT_PRIVACY_POLICY_EXPLICIT;
            break;
        default:
            dest = INCIDENT_REPORT_PRIVACY_POLICY_AUTOMATIC;
    }
    AIncidentReportArgs_setPrivacyPolicy(args, dest);

    AIncidentReportArgs_setReceiverPackage(args, config.receiver_pkg().c_str());

    AIncidentReportArgs_setReceiverClass(args, config.receiver_cls().c_str());

    int err = AIncidentReportArgs_takeReport(args);
    AIncidentReportArgs_delete(args);

    return err == NO_ERROR;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
