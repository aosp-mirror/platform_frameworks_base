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

#define DEBUG false  // STOPSHIP if true
#include "logd/LogEvent.h"

#include "stats_log_util.h"

namespace android {
namespace os {
namespace statsd {

using namespace android::util;
using android::util::ProtoOutputStream;
using std::string;
using std::vector;

LogEvent::LogEvent(log_msg& msg) {
    mContext =
            create_android_log_parser(msg.msg() + sizeof(uint32_t), msg.len() - sizeof(uint32_t));
    mLogdTimestampNs = msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec;
    mLogUid = msg.entry_v4.uid;
    init(mContext);
    if (mContext) {
        // android_log_destroy will set mContext to NULL
        android_log_destroy(&mContext);
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t wallClockTimestampNs, int64_t elapsedTimestampNs) {
    mLogdTimestampNs = wallClockTimestampNs;
    mTagId = tagId;
    mLogUid = 0;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, elapsedTimestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

LogEvent::LogEvent(int32_t tagId, int64_t timestampNs) {
    mLogdTimestampNs = timestampNs;
    mTagId = tagId;
    mLogUid = 0;
    mContext = create_android_logger(1937006964); // the event tag shared by all stats logs
    if (mContext) {
        android_log_write_int64(mContext, timestampNs);
        android_log_write_int32(mContext, tagId);
    }
}

void LogEvent::init() {
    if (mContext) {
        const char* buffer;
        size_t len = android_log_write_list_buffer(mContext, &buffer);
        // turns to reader mode
        android_log_context contextForRead = create_android_log_parser(buffer, len);
        if (contextForRead) {
            init(contextForRead);
            // destroy the context to save memory.
            // android_log_destroy will set mContext to NULL
            android_log_destroy(&contextForRead);
        }
        android_log_destroy(&mContext);
    }
}

LogEvent::~LogEvent() {
    if (mContext) {
        // This is for the case when LogEvent is created using the test interface
        // but init() isn't called.
        android_log_destroy(&mContext);
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

bool LogEvent::write(int64_t value) {
    if (mContext) {
        return android_log_write_int64(mContext, value) >= 0;
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

bool LogEvent::write(const std::vector<AttributionNodeInternal>& nodes) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         for (size_t i = 0; i < nodes.size(); ++i) {
             if (!write(nodes[i])) {
                return false;
             }
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

bool LogEvent::write(const AttributionNodeInternal& node) {
    if (mContext) {
         if (android_log_write_list_begin(mContext) < 0) {
            return false;
         }
         if (android_log_write_int32(mContext, node.uid()) < 0) {
            return false;
         }
         if (android_log_write_string8(mContext, node.tag().c_str()) < 0) {
            return false;
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 *
 * The idea here is to read through the log items once, we get as much information we need for
 * matching as possible. Because this log will be matched against lots of matchers.
 */
void LogEvent::init(android_log_context context) {
    android_log_list_element elem;
    int i = 0;
    int depth = -1;
    int pos[] = {1, 1, 1};
    do {
        elem = android_log_read_next(context);
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
                // elem at [0] is EVENT_TYPE_LIST, [1] is the timestamp, [2] is tag id.
                if (i == 2) {
                    mTagId = elem.data.int32;
                } else {
                    if (depth < 0 || depth > 2) {
                        return;
                    }

                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int32_t)elem.data.int32)));

                    pos[depth]++;
                }
                break;
            case EVENT_TYPE_FLOAT: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                mValues.push_back(FieldValue(Field(mTagId, pos, depth), Value(elem.data.float32)));

                pos[depth]++;

            } break;
            case EVENT_TYPE_STRING: {
                if (depth < 0 || depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }

                mValues.push_back(FieldValue(Field(mTagId, pos, depth),
                                             Value(string(elem.data.string, elem.len))));

                pos[depth]++;

            } break;
            case EVENT_TYPE_LONG: {
                if (i == 1) {
                    mElapsedTimestampNs = elem.data.int64;
                } else {
                    if (depth < 0 || depth > 2) {
                        ALOGE("Depth > 2. Not supported!");
                        return;
                    }
                    mValues.push_back(
                            FieldValue(Field(mTagId, pos, depth), Value((int64_t)elem.data.int64)));

                    pos[depth]++;
                }
            } break;
            case EVENT_TYPE_LIST:
                depth++;
                if (depth > 2) {
                    ALOGE("Depth > 2. Not supported!");
                    return;
                }
                pos[depth] = 1;

                break;
            case EVENT_TYPE_LIST_STOP: {
                int prevDepth = depth;
                depth--;
                if (depth >= 0 && depth < 2) {
                    // Now go back to decorate the previous items that are last at prevDepth.
                    // So that we can later easily match them with Position=Last matchers.
                    pos[prevDepth]--;
                    int path = getEncodedField(pos, prevDepth, false);
                    for (auto it = mValues.rbegin(); it != mValues.rend(); ++it) {
                        if (it->mField.getDepth() >= prevDepth &&
                            it->mField.getPath(prevDepth) == path) {
                            it->mField.decorateLastPos(prevDepth);
                        } else {
                            // Safe to break, because the items are in DFS order.
                            break;
                        }
                    }
                    pos[depth]++;
                }
                break;
            }
            case EVENT_TYPE_UNKNOWN:
                break;
            default:
                break;
        }
        i++;
    } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);
}

int64_t LogEvent::GetLong(size_t key, status_t* err) const {
    // TODO: encapsulate the magical operations all in Field struct as a static function.
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == LONG) {
                return value.mValue.long_value;
            } else if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

int LogEvent::GetInt(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value;
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0;
}

const char* LogEvent::GetString(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == STRING) {
                return value.mValue.str_value.c_str();
            } else {
                *err = BAD_TYPE;
                return 0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return NULL;
}

bool LogEvent::GetBool(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == INT) {
                return value.mValue.int_value != 0;
            } else if (value.mValue.getType() == LONG) {
                return value.mValue.long_value != 0;
            } else {
                *err = BAD_TYPE;
                return false;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return false;
}

float LogEvent::GetFloat(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
        if (value.mField.getField() == field) {
            if (value.mValue.getType() == FLOAT) {
                return value.mValue.float_value;
            } else {
                *err = BAD_TYPE;
                return 0.0;
            }
        }
        if ((size_t)value.mField.getPosAtDepth(0) > key) {
            break;
        }
    }

    *err = BAD_INDEX;
    return 0.0;
}

string LogEvent::ToString() const {
    string result;
    result += StringPrintf("{ %lld %lld (%d)", (long long)mLogdTimestampNs,
                           (long long)mElapsedTimestampNs, mTagId);
    for (const auto& value : mValues) {
        result +=
                StringPrintf("%#x", value.mField.getField()) + "->" + value.mValue.toString() + " ";
    }
    result += " }";
    return result;
}

void LogEvent::ToProto(ProtoOutputStream& protoOutput) const {
    writeFieldValueTreeToStream(mTagId, getValues(), &protoOutput);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
