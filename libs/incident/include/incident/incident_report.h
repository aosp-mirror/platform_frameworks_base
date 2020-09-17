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

/**
 * @file incident_report.h
 */

#pragma once

#include <stdbool.h>
#include <stdint.h>

#if __cplusplus
extern "C" {
#endif // __cplusplus

struct AIncidentReportArgs;
/**
 * Opaque class to represent the arguments to an incident report request.
 * Incident reports contain debugging data about the device at runtime.
 * For more information see the android.os.IncidentManager java class.
 */
typedef struct AIncidentReportArgs AIncidentReportArgs;

// Privacy policy enum value, sync with frameworks/base/core/proto/android/privacy.proto,
// IncidentReportArgs.h and IncidentReportArgs.java.
enum {
    /**
     * Flag marking fields and incident reports than can be taken
     * off the device only via adb.
     */
    INCIDENT_REPORT_PRIVACY_POLICY_LOCAL = 0,

    /**
     * Flag marking fields and incident reports than can be taken
     * off the device with contemporary consent.
     */
    INCIDENT_REPORT_PRIVACY_POLICY_EXPLICIT = 100,

    /**
     * Flag marking fields and incident reports than can be taken
     * off the device with prior consent.
     */
    INCIDENT_REPORT_PRIVACY_POLICY_AUTOMATIC = 200,

    /**
     * Flag to indicate that a given field has not been marked
     * with a privacy policy.
     */
    INCIDENT_REPORT_PRIVACY_POLICY_UNSET = 255
};

/**
 * Allocate and initialize an AIncidentReportArgs object.
 */
AIncidentReportArgs* AIncidentReportArgs_init();

/**
 * Duplicate an existing AIncidentReportArgs object.
 */
AIncidentReportArgs* AIncidentReportArgs_clone(AIncidentReportArgs* that);

/**
 * Clean up and delete an AIncidentReportArgs object.
 */
void AIncidentReportArgs_delete(AIncidentReportArgs* args);

/**
 * Set this incident report to include all sections.
 */
void AIncidentReportArgs_setAll(AIncidentReportArgs* args, bool all);

/**
 * Set this incident report privacy policy spec.
 */
void AIncidentReportArgs_setPrivacyPolicy(AIncidentReportArgs* args, int privacyPolicy);

/**
 * Add this section to the incident report. The section IDs are the field numbers
 * from the android.os.IncidentProto protobuf message.
 */
void AIncidentReportArgs_addSection(AIncidentReportArgs* args, int section);

/**
 * Set the apk package name that will be sent a broadcast when the incident
 * report completes.  Must be called in conjunction with AIncidentReportArgs_setReceiverClass.
 */
void AIncidentReportArgs_setReceiverPackage(AIncidentReportArgs* args, char const* pkg);

/**
 * Set the fully qualified class name of the java BroadcastReceiver class that will be
 * sent a broadcast when the report completes.  Must be called in conjunction with
 * AIncidentReportArgs_setReceiverPackage.
 */
void AIncidentReportArgs_setReceiverClass(AIncidentReportArgs* args, char const* cls);

/**
 * Add protobuf data as a header to the incident report. The buffer should be a serialized
 * android.os.IncidentHeaderProto object.
 */
void AIncidentReportArgs_addHeader(AIncidentReportArgs* args, uint8_t const* buf, size_t size);

/**
 * Initiate taking the report described in the args object.  Returns 0 on success,
 * and non-zero otherwise.
 */
int AIncidentReportArgs_takeReport(AIncidentReportArgs* args);

#if __cplusplus
} // extern "C"
#endif // __cplusplus

