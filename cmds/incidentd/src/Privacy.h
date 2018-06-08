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
#pragma once

#ifndef PRIVACY_H
#define PRIVACY_H

#include <stdint.h>

namespace android {
namespace os {
namespace incidentd {

// This is the default value of DEST enum, sync with privacy.proto
const uint8_t DEST_UNSET = 255;  // DEST_UNSET is not exposed to libincident
const uint8_t DEST_DEFAULT_VALUE = DEST_UNSET;

/*
 * In order to NOT auto-generate large chuck of code by proto compiler in incidentd,
 * privacy options's data structure are explicitly redefined here and
 * the values are populated by incident_section_gen tool.
 *
 * Each proto field will have a Privacy when it is different from its parent, otherwise
 * it uses its parent's tag. A message type will have an array of Privacy.
 */
struct Privacy {
    // The field number
    uint32_t field_id;

    // The field type, see external/protobuf/src/google/protobuf/descriptor.h
    uint8_t type;

    // If children is null, it is a primitive field,
    // otherwise it is a message field which could have overridden privacy tags here.
    // This array is NULL-terminated.
    Privacy** children;

    // DESTINATION Enum in frameworks/base/libs/incident/proto/android/privacy.proto.
    uint8_t dest;
    // A list of regexp rules for stripping string fields in proto.
    const char** patterns;
};

// Encode field id used by ProtoOutputStream.
uint64_t encode_field_id(const Privacy* p);

// Look up the child with given fieldId, if not found, return NULL.
const Privacy* lookup(const Privacy* p, uint32_t fieldId);

/**
 * PrivacySpec defines the request has what level of privacy authorization.
 * For example, a device without user consent should only be able to upload AUTOMATIC fields.
 * DEST_UNSET are treated as DEST_EXPLICIT.
 */
class PrivacySpec {
public:
    const uint8_t dest;

    PrivacySpec() : dest(DEST_DEFAULT_VALUE) {}
    bool operator<(const PrivacySpec& other) const;

    // check permission of a policy, if returns true, don't strip the data.
    bool CheckPremission(const Privacy* privacy,
                         const uint8_t defaultDest = DEST_DEFAULT_VALUE) const;

    // if returns true, no data need to be stripped.
    bool RequireAll() const;

    // Constructs spec using static methods below.
    static PrivacySpec new_spec(int dest);

private:
    PrivacySpec(uint8_t dest) : dest(dest) {}
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // PRIVACY_H
