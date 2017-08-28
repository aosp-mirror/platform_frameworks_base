/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef SECTION_LIST_H
#define SECTION_LIST_H

#include "Section.h"

/**
 * This is the mapping of section IDs to the commands that are run to get those commands.
 * The section IDs are guaranteed in ascending order
 */
extern const Section* SECTION_LIST[];

/*
 * In order not to use libprotobuf-cpp-full nor libplatformprotos in incidentd
 * privacy options's data structure are explicityly redefined in this file.
 */

// DESTINATION enum
extern const uint8_t DEST_LOCAL;
extern const uint8_t DEST_EXPLICIT;
extern const uint8_t DEST_AUTOMATIC;

// This is the default value of DEST enum
// field with this value doesn't generate Privacy to save too much generated code
extern const uint8_t DEST_DEFAULT_VALUE;

// type of the field, identitical to protobuf definition
extern const uint8_t TYPE_STRING;
extern const uint8_t TYPE_MESSAGE;

struct Privacy {
    int field_id;
    uint8_t type;

    // the following two fields are identitical to
    // frameworks/base/libs/incident/proto/android/privacy.proto
    uint8_t dest;
    const char** patterns;

    // ignore parent's privacy flags if children are set, NULL-terminated
    const Privacy** children;
};

/**
 * This is the mapping of section IDs to each section's privacy policy.
 * The section IDs are guaranteed in ascending order
 */
extern const Privacy* PRIVACY_POLICY_LIST[];

#endif // SECTION_LIST_H

