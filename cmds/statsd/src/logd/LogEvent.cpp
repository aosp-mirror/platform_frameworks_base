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

#define DEBUG true  // STOPSHIP if true
#include "logd/LogEvent.h"

#include <sstream>
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

using namespace android::util;
using std::ostringstream;
using std::string;
using android::util::ProtoOutputStream;

LogEvent::LogEvent(log_msg& msg) {
    mContext =
            create_android_log_parser(msg.msg() + sizeof(uint32_t), msg.len() - sizeof(uint32_t));
    mTimestampNs = msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec;
    init(mContext);
}

LogEvent::LogEvent(int32_t tagId, uint64_t timestampNs) {
    mTimestampNs = timestampNs;
    mTagId = tagId;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int32(mContext, tagId);
    }
}

void LogEvent::init() {
    if (mContext) {
        const char* buffer;
        size_t len = android_log_write_list_buffer(mContext, &buffer);
        // turns to reader mode
        mContext = create_android_log_parser(buffer, len);
        init(mContext);
    }
}

bool LogEvent::write(int32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint32_t value) {
    if (mContext) {
        return android_log_write_int32(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(uint64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
    }
    return false;
}

bool LogEvent::write(const string& value) {
    if (mContext) {
        return android_log_write_string8_len(mContext, value.c_str(), value.length()) >= 0;
    }
    return false;
}

bool LogEvent::write(float value) {
    if (mContext) {
        return android_log_write_float32(mContext, value) >= 0;
    }
    return false;
}

LogEvent::~LogEvent() {
    if (mContext) {
        android_log_destroy(&mContext);
    }
}

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 */
void LogEvent::init(android_log_context context) {
    mElements.clear();
    android_log_list_element elem;
    // TODO: The log is actually structured inside one list.  This is convenient
    // because we'll be able to use it to put the attribution (WorkSource) block first
    // without doing our own tagging scheme.  Until that change is in, just drop the
    // list-related log elements and the order we get there is our index-keyed data
    // structure.
    int i = 0;
    do {
        elem = android_log_read_next(context);
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
                // elem at [0] is EVENT_TYPE_LIST, [1] is the tag id. If we add WorkSource, it would
                // be the list starting at [2].
                if (i == 1) {
                    mTagId = elem.data.int32;
                    break;
                }
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
        i++;
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
    // Need to add the '/0' at the end by specifying the length of the string.
    return string(elem.data.string, elem.len).c_str();
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
            // Need to add the '/0' at the end by specifying the length of the string.
            result << string(elem.data.string, elem.len).c_str();
        }
    }
    result << " }";
    return result.str();
}

void LogEvent::ToProto(ProtoOutputStream& proto) const {
    long long atomToken = proto.start(FIELD_TYPE_MESSAGE | mTagId);
    const size_t N = mElements.size();
    for (size_t i=0; i<N; i++) {
        const int key = i + 1;

        const android_log_list_element& elem = mElements[i];
        if (elem.type == EVENT_TYPE_INT) {
            proto.write(FIELD_TYPE_INT32 | key, elem.data.int32);
        } else if (elem.type == EVENT_TYPE_LONG) {
            proto.write(FIELD_TYPE_INT64 | key, (long long)elem.data.int64);
        } else if (elem.type == EVENT_TYPE_FLOAT) {
            proto.write(FIELD_TYPE_FLOAT | key, elem.data.float32);
        } else if (elem.type == EVENT_TYPE_STRING) {
            proto.write(FIELD_TYPE_STRING | key, elem.data.string);
        }
    }
    proto.end(atomToken);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
