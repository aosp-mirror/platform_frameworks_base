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

#include <StatsLogProcessor.h>

#include <log/event_tag_map.h>
#include <log/logprint.h>
#include <utils/Errors.h>

#include <frameworks/base/cmds/statsd/src/stats_log.pb.h>

using namespace android;
using android::os::statsd::StatsLogEntry;

StatsLogProcessor::StatsLogProcessor() : m_dropbox_writer("all-logs")
{
    // Initialize the EventTagMap, which is how we know the names of the numeric event tags.
    // If this fails, we can't print well, but something will print.
    m_tags = android_openEventTagMap(NULL);

    // Printing format
    m_format = android_log_format_new();
    android_log_setPrintFormat(m_format, FORMAT_THREADTIME);
}

StatsLogProcessor::~StatsLogProcessor()
{
    if (m_tags != NULL) {
        android_closeEventTagMap(m_tags);
    }
    android_log_format_free(m_format);
}

void
StatsLogProcessor::OnLogEvent(const log_msg& msg)
{
    status_t err;
    AndroidLogEntry entry;
    char buf[1024];

    err = android_log_processBinaryLogBuffer(&(const_cast<log_msg*>(&msg)->entry_v1),
                &entry, m_tags, buf, sizeof(buf));

    // dump all statsd logs to dropbox for now.
    // TODO: Add filtering, aggregation, etc.
    if (err == NO_ERROR) {
        StatsLogEntry logEntry;
        logEntry.set_uid(entry.uid);
        logEntry.set_pid(entry.pid);
        logEntry.set_start_report_millis(entry.tv_sec / 1000 + entry.tv_nsec / 1000 / 1000);
        logEntry.add_pairs()->set_value_str(entry.message, entry.messageLen);
        m_dropbox_writer.addEntry(logEntry);
    }
}

