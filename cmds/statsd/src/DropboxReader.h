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

#ifndef DROPBOX_READER_H
#define DROPBOX_READER_H

#include <frameworks/base/cmds/statsd/src/stats_log.pb.h>

#include <stdint.h>
#include <stdio.h>

using android::base::unique_fd;
using android::os::statsd::StatsLogList;
using android::status_t;
using std::string;

class DropboxReader {
public:
    // msec is the start timestamp.
    static status_t readStatsLogs(FILE* out, const string& tag, long msec);

private:
    static bool parseFromFile(const unique_fd& fd, StatsLogList& list);
    static bool parseFromGzipFile(const unique_fd& fd, StatsLogList& list);
    static void printLog(FILE* out, const StatsLogList& list);
    enum {
      kCompressStored = 0,    // no compression
      kCompressDeflated = 8,  // standard deflate
    };
};

#endif //DROPBOX_READER_H