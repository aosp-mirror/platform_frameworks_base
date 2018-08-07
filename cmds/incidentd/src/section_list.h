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
#pragma once

#ifndef SECTION_LIST_H
#define SECTION_LIST_H

#include <log/log_event_list.h>  // include log_id_t enums.

#include "Privacy.h"
#include "Section.h"

namespace android {
namespace os {
namespace incidentd {

/**
 * This is the mapping of section IDs to the commands that are run to get those commands.
 * The section IDs are guaranteed in ascending order, NULL-terminated.
 */
extern const Section* SECTION_LIST[];

/**
 * This is the mapping of section IDs to each section's privacy policy.
 * The section IDs are guaranteed in ascending order, not NULL-terminated since size is provided.
 */
extern const Privacy** PRIVACY_POLICY_LIST;

extern const int PRIVACY_POLICY_COUNT;

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // SECTION_LIST_H
