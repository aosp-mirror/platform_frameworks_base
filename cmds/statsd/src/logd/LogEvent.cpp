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

#include "logd/LogEvent.h"

#include <sstream>

namespace android {
namespace os {
namespace statsd {

using std::ostringstream;

LogEvent::LogEvent(const log_msg& msg) {
    init(msg);
}

LogEvent::LogEvent(int64_t timestampNs, android_log_event_list* reader) {
    init(timestampNs, reader);
}

LogEvent::~LogEvent() {
}

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 */
void LogEvent::init(const log_msg& msg) {

    android_log_event_list list(const_cast<log_msg&>(msg));
    init(msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec, &list);
}

void LogEvent::init(int64_t timestampNs, android_log_event_list* reader) {
    mTimestampNs = timestampNs;
    mTagId = reader->tag();

    mElements.clear();
    android_log_list_element elem;

    // TODO: The log is actually structured inside one list.  This is convenient
    // because we'll be able to use it to put the attribution (WorkSource) block first
    // without doing our own tagging scheme.  Until that change is in, just drop the
    // list-related log elements and the order we get there is our index-keyed data
    // structure.
    do {
        elem = android_log_read_next(reader->context());
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
            case EVENT_TYPE_FLOAT:
            case EVENT_TYPE_STRING:
            case EVENT_TYPE_LONG:
                mElements.push_back(elem);
                break;
            case EVENT_TYPE_LIST:
                break;
            case EVENT_TYPE_LIST_STOP:
                break;
            case EVENT_TYPE_UNKNOWN:
                break;
            default:
                break;
        }
    } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
}

int64_t LogEvent::GetLong(size_t key, status_t* err) const {
    if (key < 1 || (key - 1)  >= mElements.size()) {
        *err = BAD_INDEX;
        return 0;
    }
    key--;
    const android_log_list_element& elem = mElements[key];
    if (elem.type == EVENT_TYPE_INT) {
        return elem.data.int32;
    } else if (elem.type == EVENT_TYPE_LONG) {
        return elem.data.int64;
    } else if (elem.type == EVENT_TYPE_FLOAT) {
        return (int64_t)elem.data.float32;
    } else {
        *err = BAD_TYPE;
        return 0;
    }
}

const char* LogEvent::GetString(size_t key, status_t* err) const {
    if (key < 1 || (key - 1)  >= mElements.size()) {
        *err = BAD_INDEX;
        return NULL;
    }
    key--;
    const android_log_list_element& elem = mElements[key];
    if (elem.type != EVENT_TYPE_STRING) {
        *err = BAD_TYPE;
        return NULL;
    }
    return elem.data.string;
}

bool LogEvent::GetBool(size_t key, status_t* err) const {
    if (key < 1 || (key - 1)  >= mElements.size()) {
        *err = BAD_INDEX;
        return 0;
    }
    key--;
    const android_log_list_element& elem = mElements[key];
    if (elem.type == EVENT_TYPE_INT) {
        return elem.data.int32 != 0;
    } else if (elem.type == EVENT_TYPE_LONG) {
        return elem.data.int64 != 0;
    } else if (elem.type == EVENT_TYPE_FLOAT) {
        return elem.data.float32 != 0;
    } else {
        *err = BAD_TYPE;
        return 0;
    }
}

float LogEvent::GetFloat(size_t key, status_t* err) const {
    if (key < 1 || (key - 1)  >= mElements.size()) {
        *err = BAD_INDEX;
        return 0;
    }
    key--;
    const android_log_list_element& elem = mElements[key];
    if (elem.type == EVENT_TYPE_INT) {
        return (float)elem.data.int32;
    } else if (elem.type == EVENT_TYPE_LONG) {
        return (float)elem.data.int64;
    } else if (elem.type == EVENT_TYPE_FLOAT) {
        return elem.data.float32;
    } else {
        *err = BAD_TYPE;
        return 0;
    }
}

KeyValuePair LogEvent::GetKeyValueProto(size_t key) const {
    KeyValuePair pair;
    pair.set_key(key);
    // If the value is not valid, return the KeyValuePair without assigning the value.
    // Caller can detect the error by checking the enum for "one of" proto type.
    if (key < 1 || (key - 1) >= mElements.size()) {
        return pair;
    }
    key--;

    const android_log_list_element& elem = mElements[key];
    if (elem.type == EVENT_TYPE_INT) {
        pair.set_value_int(elem.data.int32);
    } else if (elem.type == EVENT_TYPE_LONG) {
        pair.set_value_int(elem.data.int64);
    } else if (elem.type == EVENT_TYPE_STRING) {
        pair.set_value_str(elem.data.string);
    } else if (elem.type == EVENT_TYPE_FLOAT) {
        pair.set_value_float(elem.data.float32);
    }
    return pair;
}

string LogEvent::ToString() const {
    ostringstream result;
    result << "{ " << mTimestampNs << " (" << mTagId << ")";
    const size_t N = mElements.size();
    for (size_t i=0; i<N; i++) {
        result << " ";
        result << (i + 1);
        result << "->";
        const android_log_list_element& elem = mElements[i];
        if (elem.type == EVENT_TYPE_INT) {
            result << elem.data.int32;
        } else if (elem.type == EVENT_TYPE_LONG) {
            result << elem.data.int64;
        } else if (elem.type == EVENT_TYPE_FLOAT) {
            result << elem.data.float32;
        } else if (elem.type == EVENT_TYPE_STRING) {
            result << elem.data.string;
        }
    }
    result << " }";
    return result.str();
}

void LogEvent::ToProto(EventMetricData* out) const {
    // TODO: Implement this when we have the ProtoOutputStream version.

    // set timestamp of the event.
    out->set_timestamp_nanos(mTimestampNs);

    // uint64_t token = proto->StartObject(EventMetricData.FIELD);
    const size_t N = mElements.size();
    for (size_t i=0; i<N; i++) {
        const int key = i + 1;

        const android_log_list_element& elem = mElements[i];
        if (elem.type == EVENT_TYPE_INT) {
            // proto->Write(key, elem.data.int32);
        } else if (elem.type == EVENT_TYPE_LONG) {
            // proto->Write(key, elem.data.int64);
        } else if (elem.type == EVENT_TYPE_FLOAT) {
            // proto->Write(key, elem.data.float32);
        } else if (elem.type == EVENT_TYPE_STRING) {
            // proto->Write(key, elem.data.string);
        }
    }

    //proto->EndObject(token);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
