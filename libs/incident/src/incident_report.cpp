/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "libincident"

#include <incident/incident_report.h>

#include <android/os/IIncidentManager.h>
#include <android/os/IncidentReportArgs.h>
#include <binder/IServiceManager.h>
#include <binder/Status.h>
#include <log/log.h>

using android::sp;
using android::binder::Status;
using android::os::IncidentReportArgs;
using android::os::IIncidentManager;
using std::string;
using std::vector;

AIncidentReportArgs* AIncidentReportArgs_init() {
    return reinterpret_cast<AIncidentReportArgs*>(new IncidentReportArgs());
}

AIncidentReportArgs* AIncidentReportArgs_clone(AIncidentReportArgs* that) {
    return reinterpret_cast<AIncidentReportArgs*>(
            new IncidentReportArgs(*reinterpret_cast<IncidentReportArgs*>(that)));
}

void AIncidentReportArgs_delete(AIncidentReportArgs* args) {
    delete reinterpret_cast<IncidentReportArgs*>(args);
}

void AIncidentReportArgs_setAll(AIncidentReportArgs* args, bool all) {
    reinterpret_cast<IncidentReportArgs*>(args)->setAll(all);
}

void AIncidentReportArgs_setPrivacyPolicy(AIncidentReportArgs* args, int privacyPolicy) {
    reinterpret_cast<IncidentReportArgs*>(args)->setPrivacyPolicy(privacyPolicy);
}

void AIncidentReportArgs_addSection(AIncidentReportArgs* args, int section) {
    reinterpret_cast<IncidentReportArgs*>(args)->addSection(section);
}

void AIncidentReportArgs_setReceiverPackage(AIncidentReportArgs* args, char const* pkg) {
    reinterpret_cast<IncidentReportArgs*>(args)->setReceiverPkg(string(pkg));
}

void AIncidentReportArgs_setReceiverClass(AIncidentReportArgs* args, char const* cls) {
    reinterpret_cast<IncidentReportArgs*>(args)->setReceiverCls(string(cls));
}

void AIncidentReportArgs_addHeader(AIncidentReportArgs* args, uint8_t const* buf, size_t size) {
    vector<uint8_t> vec(buf, buf+size);
    reinterpret_cast<IncidentReportArgs*>(args)->addHeader(vec);
}

int AIncidentReportArgs_takeReport(AIncidentReportArgs* argp) {
    IncidentReportArgs* args = reinterpret_cast<IncidentReportArgs*>(argp);

    sp<IIncidentManager> service = android::interface_cast<IIncidentManager>(
            android::defaultServiceManager()->getService(android::String16("incident")));
    if (service == nullptr) {
        ALOGW("Failed to fetch incident service.");
        return false;
    }
    Status s = service->reportIncident(*args);
    return s.transactionError();
}
