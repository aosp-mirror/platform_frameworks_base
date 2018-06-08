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

#include "Privacy.h"

#include <android/os/IncidentReportArgs.h>
#include <stdlib.h>

namespace android {
namespace os {
namespace incidentd {

uint64_t encode_field_id(const Privacy* p) { return (uint64_t)p->type << 32 | p->field_id; }

const Privacy* lookup(const Privacy* p, uint32_t fieldId) {
    if (p->children == NULL) return NULL;
    for (int i = 0; p->children[i] != NULL; i++) {  // NULL-terminated.
        if (p->children[i]->field_id == fieldId) return p->children[i];
        // Incident section gen tool guarantees field ids in ascending order.
        if (p->children[i]->field_id > fieldId) return NULL;
    }
    return NULL;
}

static bool allowDest(const uint8_t dest, const uint8_t policy) {
    switch (policy) {
        case android::os::DEST_LOCAL:
            return dest == android::os::DEST_LOCAL;
        case android::os::DEST_EXPLICIT:
        case DEST_UNSET:
            return dest == android::os::DEST_LOCAL || dest == android::os::DEST_EXPLICIT ||
                   dest == DEST_UNSET;
        case android::os::DEST_AUTOMATIC:
            return true;
        default:
            return false;
    }
}

bool PrivacySpec::operator<(const PrivacySpec& other) const { return dest < other.dest; }

bool PrivacySpec::CheckPremission(const Privacy* privacy, const uint8_t defaultDest) const {
    uint8_t policy = privacy != NULL ? privacy->dest : defaultDest;
    return allowDest(dest, policy);
}

bool PrivacySpec::RequireAll() const { return dest == android::os::DEST_LOCAL; }

PrivacySpec PrivacySpec::new_spec(int dest) {
    switch (dest) {
        case android::os::DEST_AUTOMATIC:
        case android::os::DEST_EXPLICIT:
        case android::os::DEST_LOCAL:
            return PrivacySpec(dest);
        default:
            return PrivacySpec(android::os::DEST_AUTOMATIC);
    }
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
