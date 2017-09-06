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
#ifndef STATS_LOG_PROCESSOR_H
#define STATS_LOG_PROCESSOR_H

#include "LogReader.h"
#include "DropboxWriter.h"

#include <log/logprint.h>
#include <stdio.h>

class StatsLogProcessor : public LogListener
{
public:
    StatsLogProcessor();
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const log_msg& msg);

private:
    /**
     * Numeric to string tag name mapping.
     */
    EventTagMap* m_tags;

    /**
     * Pretty printing format.
     */
    AndroidLogFormat* m_format;

    DropboxWriter m_dropbox_writer;
};
#endif //STATS_LOG_PROCESSOR_H
