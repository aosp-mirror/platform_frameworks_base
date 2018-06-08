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

#include "FieldValue.h"

#include <android/util/ProtoOutputStream.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <private/android_logger.h>
#include <utils/Errors.h>

#include <string>
#include <vector>

namespace android {
namespace os {
namespace statsd {

struct AttributionNodeInternal {
    void set_uid(int32_t id) {
        mUid = id;
    }

    void set_tag(const std::string& value) {
        mTag = value;
    }

    int32_t uid() const {
        return mUid;
    }

    const std::string& tag() const {
        return mTag;
    }

    int32_t mUid;
    std::string mTag;
};
/**
 * Wrapper for the log_msg structure.
 */
class LogEvent {
public:
    /**
     * Read a LogEvent from a log_msg.
     */
    explicit LogEvent(log_msg& msg);

    /**
     * Constructs a LogEvent with synthetic data for testing. Must call init() before reading.
     */
    explicit LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs);

    // For testing. The timestamp is used as both elapsed real time and logd timestamp.
    explicit LogEvent(int32_t tagId, int64_t timestampNs);

    ~LogEvent();

    /**
     * Get the timestamp associated with this event.
     */
    inline int64_t GetLogdTimestampNs() const { return mLogdTimestampNs; }
    inline int64_t GetElapsedTimestampNs() const { return mElapsedTimestampNs; }

    /**
     * Get the tag for this event.
     */
    inline int GetTagId() const { return mTagId; }

    inline uint32_t GetUid() const {
        return mLogUid;
    }

    /**
     * Get the nth value, starting at 1.
     *
     * Returns BAD_INDEX if the index is larger than the number of elements.
     * Returns BAD_TYPE if the index is available but the data is the wrong type.
     */
    int64_t GetLong(size_t key, status_t* err) const;
    int GetInt(size_t key, status_t* err) const;
    const char* GetString(size_t key, status_t* err) const;
    bool GetBool(size_t key, status_t* err) const;
    float GetFloat(size_t key, status_t* err) const;

    /**
     * Write test data to the LogEvent. This can only be used when the LogEvent is constructed
     * using LogEvent(tagId, timestampNs). You need to call init() before you can read from it.
     */
    bool write(uint32_t value);
    bool write(int32_t value);
    bool write(uint64_t value);
    bool write(int64_t value);
    bool write(const std::string& value);
    bool write(float value);
    bool write(const std::vector<AttributionNodeInternal>& nodes);
    bool write(const AttributionNodeInternal& node);

    /**
     * Return a string representation of this event.
     */
    std::string ToString() const;

    /**
     * Write this object to a ProtoOutputStream.
     */
    void ToProto(android::util::ProtoOutputStream& out) const;

    /**
     * Used with the constructor where tag is passed in. Converts the log_event_list to read mode
     * and prepares the list for reading.
     */
    void init();

    /**
     * Set elapsed timestamp if the original timestamp is missing.
     */
    void setElapsedTimestampNs(int64_t timestampNs) {
        mElapsedTimestampNs = timestampNs;
    }

    /**
     * Set the timestamp if the original logd timestamp is missing.
     */
    void setLogdWallClockTimestampNs(int64_t timestampNs) {
        mLogdTimestampNs = timestampNs;
    }

    inline int size() const {
        return mValues.size();
    }

    const std::vector<FieldValue>& getValues() const {
        return mValues;
    }

    std::vector<FieldValue>* getMutableValues() {
        return &mValues;
    }

private:
    /**
     * Don't copy, it's slower. If we really need this we can add it but let's try to
     * avoid it.
     */
    explicit LogEvent(const LogEvent&);

    /**
     * Parses a log_msg into a LogEvent object.
     */
    void init(android_log_context context);

    // The items are naturally sorted in DFS order as we read them. this allows us to do fast
    // matching.
    std::vector<FieldValue> mValues;

    // This field is used when statsD wants to create log event object and write fields to it. After
    // calling init() function, this object would be destroyed to save memory usage.
    // When the log event is created from log msg, this field is never initiated.
    android_log_context mContext = NULL;

    // The timestamp set by the logd.
    int64_t mLogdTimestampNs;

    // The elapsed timestamp set by statsd log writer.
    int64_t mElapsedTimestampNs;

    int mTagId;

    uint32_t mLogUid;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

