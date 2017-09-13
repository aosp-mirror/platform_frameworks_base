/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef PRIVACY_H
#define PRIVACY_H

#include <stdint.h>

// This is the default value of DEST enum
const uint8_t DEST_DEFAULT_VALUE = 1;

/*
 * In order not to depend on libprotobuf-cpp-full nor libplatformprotos in incidentd,
 * privacy options's data structure are explicitly redefined in this file.
 */
struct Privacy {
    uint32_t field_id;
    uint8_t type;
    // ignore parent's privacy flags if children are set, NULL-terminated
    Privacy** children;

    // the following fields are identitical to
    // frameworks/base/libs/incident/proto/android/privacy.proto
    uint8_t dest;
    const char** patterns; // only set when type is string

    bool IsMessageType() const;
    bool IsStringType() const;
    bool HasChildren() const;
    const Privacy* lookup(uint32_t fieldId) const;
};

/**
 * PrivacySpec defines the request has what level of privacy authorization.
 * For example, a device without user consent should only be able to upload AUTOMATIC fields.
 */
class PrivacySpec {
public:
    const uint8_t dest;

    PrivacySpec() : dest(DEST_DEFAULT_VALUE) {}
    PrivacySpec(uint8_t dest) : dest(dest) {}

    bool operator<(const PrivacySpec& other) const;

    bool CheckPremission(const Privacy* privacy) const;
    bool RequireAll() const;
};

PrivacySpec new_spec_from_args(int dest);
PrivacySpec get_default_dropbox_spec();

#endif // PRIVACY_H
