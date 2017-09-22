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

#ifndef DROPBOX_WRITER_H
#define DROPBOX_WRITER_H

#include <frameworks/base/cmds/statsd/src/stats_log.pb.h>

using std::string;

namespace android {
namespace os {
namespace statsd {

class DropboxWriter {
public:
    /* tag will be part of the file name, and used as the key to build the file index inside
       DropBoxManagerService.
     */
    DropboxWriter(const string& tag);

    void addStatsLogReport(const StatsLogReport& log);

    /* Request a flush to dropbox. */
    void flush();

private:
    /* Max *serialized* size of the logs kept in memory before flushing to dropbox.
       Proto lite does not implement the SpaceUsed() function which gives the in memory byte size.
       So we cap memory usage by limiting the serialized size. Note that protobuf's in memory size
       is higher than its serialized size. DropboxManager will compress the file when the data is
       larger than 4KB. So the final file size is less than this number.
     */
    static const size_t kMaxSerializedBytes = 16 * 1024;

    const string mTag;

    /* Data that was captured for a single metric over a given interval of time. */
    StatsLogReport mLogReport;

    /* Current *serialized* size of the logs kept in memory.
       To save computation, we will not calculate the size of the StatsLogReport every time when a
       new entry is added, which would recursively call ByteSize() on every log entry. Instead, we
       keep the sum of all individual stats log entry sizes. The size of a proto is approximately
       the sum of the size of all member protos.
     */
    size_t mBufferSize = 0;

    /* Check if the buffer size exceeds the max buffer size when the new entry is added, and flush
       the logs to dropbox if true. */
    void flushIfNecessary(const StatsLogReport& log);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // DROPBOX_WRITER_H
