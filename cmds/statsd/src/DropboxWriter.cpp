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

#include <android/os/DropBoxManager.h>

#include "DropboxWriter.h"

using android::binder::Status;
using android::os::DropBoxManager;
using android::sp;
using android::String16;
using std::vector;

namespace android {
namespace os {
namespace statsd {

DropboxWriter::DropboxWriter(const string& tag)
    : mTag(tag), mLogReport(), mBufferSize(0) {
}

void DropboxWriter::addStatsLogReport(const StatsLogReport& log) {
    mLogReport = log;
    flushIfNecessary(log);
    mBufferSize += log.ByteSize();
}

void DropboxWriter::flushIfNecessary(const StatsLogReport& log) {
    // TODO: Decide to flush depending on the serialized size of the StatsLogReport.
    // if (entry.ByteSize() + mBufferSize > kMaxSerializedBytes) {
    //     flush();
    // }
    flush();
}

void DropboxWriter::flush() {
    // now we get an exact byte size of the output
    const int numBytes = mLogReport.ByteSize();
    vector<uint8_t> buffer(numBytes);
    sp<DropBoxManager> dropbox = new DropBoxManager();
    mLogReport.SerializeToArray(&buffer[0], numBytes);
    Status status = dropbox->addData(String16(mTag.c_str()), &buffer[0],
            numBytes, 0 /* no flag */);
    if (!status.isOk()) {
        ALOGE("failed to write to dropbox");
        //TODO: What to do if flush fails??
    }
    mLogReport.Clear();
    mBufferSize = 0;
}

} // namespace statsd
} // namespace os
} // namespace android
