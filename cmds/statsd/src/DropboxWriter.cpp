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
#include <cutils/log.h>

#include "DropboxWriter.h"

using android::os::DropBoxManager;
using android::binder::Status;
using android::sp;
using android::String16;
using std::vector;

DropboxWriter::DropboxWriter(const string& tag)
    : mTag(tag), mLogList(), mBufferSize(0) {
}

void DropboxWriter::addEntry(const StatsLogEntry& entry) {
    flushIfNecessary(entry);
    StatsLogEntry* newEntry = mLogList.add_stats_log_entry();
    newEntry->CopyFrom(entry);
    mBufferSize += entry.ByteSize();
}

void DropboxWriter::flushIfNecessary(const StatsLogEntry& entry) {
    // The serialized size of the StatsLogList is approximately the sum of the serialized size of
    // every StatsLogEntry inside it.
    if (entry.ByteSize() + mBufferSize > kMaxSerializedBytes) {
        flush();
    }
}

void DropboxWriter::flush() {
    // now we get an exact byte size of the output
    const int numBytes = mLogList.ByteSize();
    vector<uint8_t> buffer(numBytes);
    sp<DropBoxManager> dropbox = new DropBoxManager();
    mLogList.SerializeToArray(&buffer[0], numBytes);
    Status status = dropbox->addData(String16(mTag.c_str()), &buffer[0],
            numBytes, 0 /* no flag */);
    if (!status.isOk()) {
        ALOGE("failed to write to dropbox");
        //TODO: What to do if flush fails??
    }
    mLogList.Clear();
    mBufferSize = 0;
}
