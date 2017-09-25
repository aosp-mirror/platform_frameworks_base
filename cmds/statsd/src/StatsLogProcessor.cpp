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

#include <log/log_event_list.h>
#include <parse_util.h>
#include <utils/Errors.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

StatsLogProcessor::StatsLogProcessor() : m_dropbox_writer("all-logs") {
    // Initialize the EventTagMap, which is how we know the names of the numeric event tags.
    // If this fails, we can't print well, but something will print.
    m_tags = android_openEventTagMap(NULL);

    // Printing format
    m_format = android_log_format_new();
    android_log_setPrintFormat(m_format, FORMAT_THREADTIME);
}

StatsLogProcessor::~StatsLogProcessor() {
    if (m_tags != NULL) {
        android_closeEventTagMap(m_tags);
    }
    android_log_format_free(m_format);
}

void StatsLogProcessor::OnLogEvent(const log_msg& msg) {
    status_t err;
    AndroidLogEntry entry;
    char buf[1024];

    err = android_log_processBinaryLogBuffer(&(const_cast<log_msg*>(&msg)->entry_v1), &entry,
                                             m_tags, buf, sizeof(buf));

    // dump all statsd logs to dropbox for now.
    // TODO: Add filtering, aggregation, etc.
    if (err == NO_ERROR) {
        EventMetricData eventMetricData = parse(msg);
        m_dropbox_writer.addEventMetricData(eventMetricData);
    }
}

void StatsLogProcessor::UpdateConfig(const int config_source, StatsdConfig config) {
    m_configs[config_source] = config;
    ALOGD("Updated configuration for source %i", config_source);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
