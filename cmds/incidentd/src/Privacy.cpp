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
#include <strstream>


namespace android {
namespace os {
namespace incidentd {

using namespace android::os;
using std::strstream;

uint64_t encode_field_id(const Privacy* p) { return (uint64_t)p->type << 32 | p->field_id; }

string Privacy::toString() const {
    if (this == NULL) {
        return "Privacy{null}";
    }
    strstream os;
    os << "Privacy{field_id=" << field_id << " type=" << ((int)type)
            << " children=" << ((void*)children) << " policy=" << ((int)policy) << "}";
    return os.str();
}

const Privacy* lookup(const Privacy* p, uint32_t fieldId) {
    if (p->children == NULL) return NULL;
    for (int i = 0; p->children[i] != NULL; i++) {  // NULL-terminated.
        if (p->children[i]->field_id == fieldId) return p->children[i];
        // Incident section gen tool guarantees field ids in ascending order.
        if (p->children[i]->field_id > fieldId) return NULL;
    }
    return NULL;
}

static bool isAllowed(const uint8_t policy, const uint8_t check) {
    switch (check) {
        case PRIVACY_POLICY_LOCAL:
            return policy == PRIVACY_POLICY_LOCAL;
        case PRIVACY_POLICY_EXPLICIT:
        case PRIVACY_POLICY_UNSET:
            return policy == PRIVACY_POLICY_LOCAL
                    || policy == PRIVACY_POLICY_EXPLICIT
                    || policy == PRIVACY_POLICY_UNSET;
        case PRIVACY_POLICY_AUTOMATIC:
            return true;
        default:
            return false;
    }
}

PrivacySpec::PrivacySpec(uint8_t argPolicy) {
    // TODO: Why on earth do we have two definitions of policy.  Maybe
    // it's not too late to clean this up.
    switch (argPolicy) {
        case android::os::PRIVACY_POLICY_AUTOMATIC:
        case android::os::PRIVACY_POLICY_EXPLICIT:
        case android::os::PRIVACY_POLICY_LOCAL:
            mPolicy = argPolicy;
            break;
        default:
            mPolicy = android::os::PRIVACY_POLICY_AUTOMATIC;
            break;
    }
}

bool PrivacySpec::operator<(const PrivacySpec& that) const {
    return mPolicy < that.mPolicy;
}

bool PrivacySpec::CheckPremission(const Privacy* privacy, const uint8_t defaultDest) const {
    uint8_t check = privacy != NULL ? privacy->policy : defaultDest;
    return isAllowed(mPolicy, check);
}

bool PrivacySpec::RequireAll() const {
    return mPolicy == android::os::PRIVACY_POLICY_LOCAL;
}

uint8_t cleanup_privacy_policy(uint8_t policy) {
    if (policy >= PRIVACY_POLICY_AUTOMATIC) {
        return PRIVACY_POLICY_AUTOMATIC;
    }
    if (policy >= PRIVACY_POLICY_EXPLICIT) {
        return PRIVACY_POLICY_EXPLICIT;
    }
    return PRIVACY_POLICY_LOCAL;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
