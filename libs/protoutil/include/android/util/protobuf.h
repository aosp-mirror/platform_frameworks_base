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

#ifndef ANDROID_UTIL_PROTOBUF_H
#define ANDROID_UTIL_PROTOBUF_H

#include <stdint.h>

namespace android {
namespace util {

const int FIELD_ID_SHIFT = 3;
const uint8_t WIRE_TYPE_MASK = (1 << FIELD_ID_SHIFT) - 1;

const uint8_t WIRE_TYPE_VARINT = 0;
const uint8_t WIRE_TYPE_FIXED64 = 1;
const uint8_t WIRE_TYPE_LENGTH_DELIMITED = 2;
const uint8_t WIRE_TYPE_FIXED32 = 5;

/**
 * Read the wire type from varint, it is the smallest 3 bits.
 */
uint8_t read_wire_type(uint32_t varint);

/**
 * Read field id from varint, it is varint >> 3;
 */
uint32_t read_field_id(uint32_t varint);

/**
 * Get the size of a varint.
 */
size_t get_varint_size(uint64_t varint);

/**
 * Write a varint into the buffer. Return the next position to write at.
 * There must be 10 bytes in the buffer.
 */
uint8_t* write_raw_varint(uint8_t* buf, uint64_t val);

/**
 * Write a protobuf WIRE_TYPE_LENGTH_DELIMITED header. Return the next position
 * to write at. There must be 20 bytes in the buffer.
 */
uint8_t* write_length_delimited_tag_header(uint8_t* buf, uint32_t fieldId, size_t size);

} // util
} // android

#endif  // ANDROID_UTIL_PROTOUBUF_H
