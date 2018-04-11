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

#include "logd/LogReader.h"

#include "guardrail/StatsdStats.h"

#include <time.h>
#include <unistd.h>
#include <utils/Errors.h>

using namespace android;
using namespace std;

namespace android {
namespace os {
namespace statsd {

#define SNOOZE_INITIAL_MS 100
#define SNOOZE_MAX_MS (10 * 60 * 1000)  // Ten minutes

LogReader::LogReader(const sp<LogListener>& listener) : mListener(listener) {
}

LogReader::~LogReader() {
}

void LogReader::Run() {
    int nextSnoozeMs = SNOOZE_INITIAL_MS;

    // In an ideal world, this outer loop will only ever run one iteration, but it
    // exists to handle crashes in logd.  The inner loop inside connect_and_read()
    // reads from logd forever, but if that read fails, we fall out to the outer
    // loop, do the backoff (resetting the backoff timeout if we successfully read
    // something), and then try again.
    while (true) {
        // Connect and read
        int lineCount = connect_and_read();

        // Figure out how long to sleep.
        if (lineCount > 0) {
            // If we managed to read at least one line, reset the backoff
            nextSnoozeMs = SNOOZE_INITIAL_MS;
        } else {
            // Otherwise, expontial backoff
            nextSnoozeMs *= 1.5f;
            if (nextSnoozeMs > 10 * 60 * 1000) {
                // Don't wait for toooo long.
                nextSnoozeMs = SNOOZE_MAX_MS;
            }
        }

        // Sleep
        timespec ts;
        timespec rem;
        ts.tv_sec = nextSnoozeMs / 1000;
        ts.tv_nsec = (nextSnoozeMs % 1000) * 1000000L;
        while (nanosleep(&ts, &rem) == -1) {
            if (errno == EINTR) {
                ts = rem;
            }
            // other errors are basically impossible
        }
    }
}

int LogReader::connect_and_read() {
    int lineCount = 0;
    status_t err;
    logger_list* loggers;
    logger* eventLogger;

    // Prepare the logging context
    loggers = android_logger_list_alloc(ANDROID_LOG_RDONLY,
                                        /* don't stop after N lines */ 0,
                                        /* no pid restriction */ 0);

    // Open the buffer(s)
    eventLogger = android_logger_open(loggers, LOG_ID_STATS);

    // Read forever
    if (eventLogger) {
        log_msg msg;
        while (true) {
            // Read a message
            err = android_logger_list_read(loggers, &msg);
            // err = 0 - no content, unexpected connection drop or EOF.
            // err = +ive number - size of retrieved data from logger
            // err = -ive number, OS supplied error _except_ for -EAGAIN
            if (err <= 0) {
                StatsdStats::getInstance().noteLoggerError(err);
                fprintf(stderr, "logcat read failure: %s\n", strerror(err));
                break;
            }

            // Record that we read one (used above to know how to snooze).
            lineCount++;

            // Wrap it in a LogEvent object
            LogEvent event(msg);

            // Call the listener
            mListener->OnLogEvent(&event,
                                  lineCount == 1 /* indicate whether it's a new connection */);
        }
    }

    // Free the logger list and close the individual loggers
    android_logger_list_free(loggers);

    return lineCount;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
