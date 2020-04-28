/*
 * Copyright (C) 2018 The Android Open Source Project
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
#include <vector>
#include "benchmark/benchmark.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

using std::vector;

/* Special markers for android_log_list_element type */
static const char EVENT_TYPE_LIST_STOP = '\n'; /* declare end of list  */
static const char EVENT_TYPE_UNKNOWN = '?';    /* protocol error       */

static const char EVENT_TYPE_INT = 0;
static const char EVENT_TYPE_LONG = 1;
static const char EVENT_TYPE_STRING = 2;
static const char EVENT_TYPE_LIST = 3;
static const char EVENT_TYPE_FLOAT = 4;

static const int kLogMsgHeaderSize = 28;

static void write4Bytes(int val, vector<char>* buffer) {
    buffer->push_back(static_cast<char>(val));
    buffer->push_back(static_cast<char>((val >> 8) & 0xFF));
    buffer->push_back(static_cast<char>((val >> 16) & 0xFF));
    buffer->push_back(static_cast<char>((val >> 24) & 0xFF));
}

static void getSimpleLogMsgData(log_msg* msg) {
    vector<char> buffer;
    // stats_log tag id
    write4Bytes(1937006964, &buffer);
    buffer.push_back(EVENT_TYPE_LIST);
    buffer.push_back(2);  // field counts;
    buffer.push_back(EVENT_TYPE_INT);
    write4Bytes(10 /* atom id */, &buffer);
    buffer.push_back(EVENT_TYPE_INT);
    write4Bytes(99 /* a value to log*/, &buffer);
    buffer.push_back(EVENT_TYPE_LIST_STOP);

    msg->entry_v1.len = buffer.size();
    msg->entry.hdr_size = kLogMsgHeaderSize;
    msg->entry_v1.sec = time(nullptr);
    std::copy(buffer.begin(), buffer.end(), msg->buf + kLogMsgHeaderSize);
}

static void BM_LogEventCreation(benchmark::State& state) {
    log_msg msg;
    getSimpleLogMsgData(&msg);
    while (state.KeepRunning()) {
        benchmark::DoNotOptimize(LogEvent(msg));
    }
}
BENCHMARK(BM_LogEventCreation);

}  //  namespace statsd
}  //  namespace os
}  //  namespace android
