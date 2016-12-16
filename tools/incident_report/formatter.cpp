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

#include "proto_format.h"

#include <string.h>

extern int const PROTO_FORMAT_STRING_POOL_SIZE;
extern int const PROTO_FORMAT_ENUM_LABELS_LENGTH;
extern int const PROTO_FORMAT_MESSAGES_LENGTH;
extern int const PROTO_FORMAT_FIELDS_LENGTH;

extern char const PROTO_FORMAT_STRING_POOL[];
extern ProtoFieldFormat const PROTO_FORMAT_FIELDS[];
extern ProtoEnumLabel const PROTO_FORMAT_ENUM_LABELS[];
extern ProtoMessageFormat const PROTO_FORMAT_MESSAGES[];

static const char*
get_string(int index)
{
    if (index >= 0 && index < PROTO_FORMAT_STRING_POOL_SIZE) {
        return PROTO_FORMAT_STRING_POOL + index;
    } else {
        // These indices all come from within the generated table, so just crash now.
        *(int*)NULL = 42;
        return NULL;
    }
}

static ProtoMessageFormat const*
get_message(int index)
{
    if (index >= 0 && index < PROTO_FORMAT_MESSAGES_LENGTH) {
        return PROTO_FORMAT_MESSAGES + index;
    } else {
        // These indices all come from within the generated table, so just crash now.
        *(int*)NULL = 42;
        return NULL;
    }
}

static int
compare_name(const char* full, const char* package, const char* clazz)
{
    int const packageLen = strlen(package);
    int cmp = strncmp(full, package, packageLen);
    if (cmp == 0) {
        cmp = full[packageLen] - '.';
        if (cmp == 0) {
            return strcmp(full + packageLen, clazz);
        }
    }
    return cmp;
}

int
find_message_index(const char* name)
{
    size_t low = 0;
    size_t high = PROTO_FORMAT_FIELDS_LENGTH - 1;

    while (low <= high) {
        size_t mid = (low + high) >> 1;
        ProtoMessageFormat const* msg = get_message(mid);

        int cmp = compare_name(name, get_string(msg->package_name), get_string(msg->package_name));
        if (cmp < 0) {
            low = mid + 1;
        } else if (cmp > 0) {
            high = mid - 1;
        } else {
            return mid;
        }
    }
    return -1;
}
