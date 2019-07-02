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

#include <utils/Errors.h>

#include <google/protobuf/message_lite.h>

#include <vector>

namespace android {
namespace os {
namespace incidentd {

using std::vector;
using google::protobuf::MessageLite;

/**
 * Write the IncidentHeaderProto section
 */
status_t write_header_section(int fd, const uint8_t* buf, size_t bufSize);

/**
 * Write the prologue for a section in the incident report
 * (This is the proto length-prefixed field format).
 */
status_t write_section_header(int fd, int sectionId, size_t size);

/**
 * Write the given protobuf object as a section.
 */
status_t write_section(int fd, int sectionId, const MessageLite& message);

}  // namespace incidentd
}  // namespace os
}  // namespace android


