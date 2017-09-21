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

#ifndef LOG_ENTRY_PRINTER_H
#define LOG_ENTRY_PRINTER_H

#include "LogReader.h"

#include <log/logprint.h>

#include <stdio.h>

namespace android {
namespace os {
namespace statsd {

/**
 * Decodes the log entry and prints it to the supplied file descriptor.
 */
class LogEntryPrinter : public LogListener
{
public:
    LogEntryPrinter(int out);
    virtual ~LogEntryPrinter();

    virtual void OnLogEvent(const log_msg& msg);

private:
    /**
     * Where to write to.
     */
    int m_out;

    /**
     * Numeric to string tag name mapping.
     */
    EventTagMap* m_tags;

    /**
     * Pretty printing format.
     */
    AndroidLogFormat* m_format;
};

} // namespace statsd
} // namespace os
} // namespace android

#endif // LOG_ENTRY_PRINTER_H
