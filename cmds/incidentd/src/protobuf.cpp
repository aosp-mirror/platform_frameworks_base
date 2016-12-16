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

#include "protobuf.h"

uint8_t*
write_raw_varint(uint8_t* buf, uint32_t val)
{
    uint8_t* p = buf;
    while (true) {
        if ((val & ~0x7F) == 0) {
            *p++ = (uint8_t)val;
            return p;
        } else {
            *p++ = (uint8_t)((val & 0x7F) | 0x80);
            val >>= 7;
        }
    }
}

uint8_t*
write_length_delimited_tag_header(uint8_t* buf, uint32_t fieldId, size_t size)
{
    buf = write_raw_varint(buf, (fieldId << 3) | 2);
    buf = write_raw_varint(buf, size);
    return buf;
}

