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

#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

#include <utils/Errors.h>
#include <log/log_event_list.h>
#include <log/log_read.h>

#include <memory>
#include <string>
#include <vector>

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::vector;

/**
 * Wrapper for the log_msg structure.
 */
class LogEvent {
public:
    /**
     * Read a LogEvent from a log_msg.
     */
    explicit LogEvent(log_msg msg);

    /**
     * Constructs a LogEvent with the specified tag and creates an android_log_event_list in write
     * mode. Obtain this list with the getter. Make sure to call init() before attempting to read
     * any of the values. This constructor is useful for unit-testing since we can't pass in an
     * android_log_event_list since there is no copy constructor or assignment operator available.
     */
    explicit LogEvent(int tag);

    ~LogEvent();

    /**
     * Get the timestamp associated with this event.
     */
    uint64_t GetTimestampNs() const { return mTimestampNs; }

    /**
     * Get the tag for this event.
     */
    int GetTagId() const { return mTagId; }

    /**
     * Get the nth value, starting at 1.
     *
     * Returns BAD_INDEX if the index is larger than the number of elements.
     * Returns BAD_TYPE if the index is available but the data is the wrong type.
     */
    int64_t GetLong(size_t key, status_t* err) const;
    const char* GetString(size_t key, status_t* err) const;
    bool GetBool(size_t key, status_t* err) const;
    float GetFloat(size_t key, status_t* err) const;

    /**
     * Return a string representation of this event.
     */
    string ToString() const;

    /**
     * Write this object as an EventMetricData proto object.
     * TODO: Use the streaming output generator to do this instead of this proto lite object?
     */
    void ToProto(EventMetricData* out) const;

    /*
     * Get a KeyValuePair proto object.
     */
    KeyValuePair GetKeyValueProto(size_t key) const;

    /**
     * A pointer to the contained log_event_list.
     *
     * @return The android_log_event_list contained within.
     */
    android_log_event_list* GetAndroidLogEventList();

    /**
     * Used with the constructor where tag is passed in. Converts the log_event_list to read mode
     * and prepares the list for reading.
     */
    void init();

private:
    /**
     * Don't copy, it's slower. If we really need this we can add it but let's try to
     * avoid it.
     */
    explicit LogEvent(const LogEvent&);

    /**
     * Parses a log_msg into a LogEvent object.
     */
    void init(const log_msg& msg);

    /**
     * Parses a log_msg into a LogEvent object.
     */
    void init(int64_t timestampNs, android_log_event_list* reader);

    vector<android_log_list_element> mElements;
    // Need a copy of the android_log_event_list so the strings are not cleared.
    android_log_event_list mList;
    long mTimestampNs;
    int mTagId;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

