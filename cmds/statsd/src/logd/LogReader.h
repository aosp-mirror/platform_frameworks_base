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

#ifndef LOGREADER_H
#define LOGREADER_H

#include "logd/LogListener.h"

#include <utils/RefBase.h>

#include <vector>

namespace android {
namespace os {
namespace statsd {

/**
 * Class to read logs from logd.
 */
class LogReader : public virtual android::RefBase {
public:
    /**
     * Construct the LogReader with the event listener. (Which is StatsService)
     */
    LogReader(const sp<LogListener>& listener);

    /**
     * Destructor.
     */
    virtual ~LogReader();

    /**
     * Run the main LogReader loop
     */
    void Run();

private:
    /**
     * Who is going to get the events when they're read.
     */
    sp<LogListener> mListener;

    /**
     * Connect to a single instance of logd, and read until there's a read error.
     * Logd can crash, exit, be killed etc.
     *
     * Returns the number of lines that were read.
     */
    int connect_and_read();
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // LOGREADER_H
