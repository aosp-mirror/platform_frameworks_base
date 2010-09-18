/*
 * Copyright (C) 2010 The Android Open Source Project
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


#include <stdlib.h>
#include <unistd.h>
#include <cutils/log.h>
#include <cutils/properties.h>
#include <utils/Endian.h>
#include <utils/Timers.h>

#include <ui/GraphicLog.h>

namespace android {

ANDROID_SINGLETON_STATIC_INSTANCE(GraphicLog)

static inline
void writeInt32(uint8_t* base, size_t& pos, int32_t value) {
#ifdef HAVE_LITTLE_ENDIAN
    int32_t v = value;
#else
    int32_t v = htole32(value);
#endif
    base[pos] = EVENT_TYPE_INT;
    memcpy(&base[pos+1], &v, sizeof(int32_t));
    pos += 1+sizeof(int32_t);
}

static inline
void writeInt64(uint8_t* base,  size_t& pos, int64_t value) {
#ifdef HAVE_LITTLE_ENDIAN
    int64_t v = value;
#else
    int64_t v = htole64(value);
#endif
    base[pos] = EVENT_TYPE_LONG;
    memcpy(&base[pos+1], &v, sizeof(int64_t));
    pos += 1+sizeof(int64_t);
}

void GraphicLog::logImpl(int32_t tag, int32_t buffer)
{
    uint8_t scratch[2 + 2 + sizeof(int32_t) + sizeof(int64_t)];
    size_t pos = 0;
    scratch[pos++] = EVENT_TYPE_LIST;
    scratch[pos++] = 2;
    writeInt32(scratch, pos, buffer);
    writeInt64(scratch, pos, ns2ms( systemTime( SYSTEM_TIME_MONOTONIC ) ));
    android_bWriteLog(tag, scratch, sizeof(scratch));
}

void GraphicLog::logImpl(int32_t tag, int32_t identity, int32_t buffer)
{
    uint8_t scratch[2 + 3 + sizeof(int32_t) + sizeof(int32_t) + sizeof(int64_t)];
    size_t pos = 0;
    scratch[pos++] = EVENT_TYPE_LIST;
    scratch[pos++] = 3;
    writeInt32(scratch, pos, buffer);
    writeInt32(scratch, pos, identity);
    writeInt64(scratch, pos, ns2ms( systemTime( SYSTEM_TIME_MONOTONIC ) ));
    android_bWriteLog(tag, scratch, sizeof(scratch));
}

GraphicLog::GraphicLog()
    : mEnabled(0)
{
    char property[PROPERTY_VALUE_MAX];
    if (property_get("debug.graphic_log", property, NULL) > 0) {
        mEnabled = atoi(property);
    }
}

void GraphicLog::setEnabled(bool enable)
{
    mEnabled = enable;
}

}
