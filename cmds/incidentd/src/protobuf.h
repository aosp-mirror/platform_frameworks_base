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

#ifndef PROTOBUF_H
#define PROTOBUF_H

#include <stdint.h>

/**
 * Write a varint into the buffer. Return the next position to write at.
 * There must be 10 bytes in the buffer. The same as EncodedBuffer.writeRawVarint32
 */
uint8_t* write_raw_varint(uint8_t* buf, uint32_t val);

/**
 * Write a protobuf WIRE_TYPE_LENGTH_DELIMITED header. Return the next position to write at.
 * There must be 20 bytes in the buffer.
 */
uint8_t* write_length_delimited_tag_header(uint8_t* buf, uint32_t fieldId, size_t size);

enum {
    // IncidentProto.header
    FIELD_ID_INCIDENT_HEADER = 1
};

#endif // PROTOBUF_H

