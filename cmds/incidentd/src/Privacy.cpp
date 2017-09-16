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

#include <stdlib.h>

// DESTINATION enum value
const uint8_t DEST_LOCAL = 0;
const uint8_t DEST_EXPLICIT = 1;
const uint8_t DEST_AUTOMATIC = 2;

// type of the field, identitical to protobuf definition
const uint8_t TYPE_STRING = 9;
const uint8_t TYPE_MESSAGE = 11;

bool
Privacy::IsMessageType() const { return type == TYPE_MESSAGE; }

bool
Privacy::IsStringType() const { return type == TYPE_STRING; }

bool
Privacy::HasChildren() const { return children != NULL && children[0] != NULL; }

const Privacy*
Privacy::lookup(uint32_t fieldId) const
{
    if (children == NULL) return NULL;
    for (int i=0; children[i] != NULL; i++) {
        if (children[i]->field_id == fieldId) return children[i];
        // This assumes the list's field id is in ascending order and must be true.
        if (children[i]->field_id > fieldId) return NULL;
    }
    return NULL;
}

static bool allowDest(const uint8_t dest, const uint8_t policy)
{
    switch (policy) {
    case DEST_LOCAL:
        return dest == DEST_LOCAL;
    case DEST_EXPLICIT:
        return dest == DEST_LOCAL || dest == DEST_EXPLICIT;
    case DEST_AUTOMATIC:
        return true;
    default:
        return false;
    }
}

bool
PrivacySpec::operator<(const PrivacySpec& other) const
{
  return dest < other.dest;
}

bool
PrivacySpec::CheckPremission(const Privacy* privacy) const
{
    uint8_t policy = privacy == NULL ? DEST_DEFAULT_VALUE : privacy->dest;
    return allowDest(dest, policy);
}

bool
PrivacySpec::RequireAll() const { return dest == DEST_LOCAL; }

PrivacySpec new_spec_from_args(int dest) {
  if (dest < 0) return PrivacySpec();
  return PrivacySpec(dest);
}

PrivacySpec get_default_dropbox_spec() { return PrivacySpec(DEST_AUTOMATIC); }