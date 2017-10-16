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

#pragma once

#include <log/log_read.h>
#include <utils/RefBase.h>
#include <vector>

namespace android {
namespace os {
namespace statsd {

/**
 * Callback for LogReader
 */
class LogListener : public virtual android::RefBase {
public:
    LogListener();
    virtual ~LogListener();

    // TODO: Rather than using log_msg, which doesn't have any real internal structure
    // here, we should pull this out into our own LogEntry class.
    virtual void OnLogEvent(const log_msg& msg) = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
