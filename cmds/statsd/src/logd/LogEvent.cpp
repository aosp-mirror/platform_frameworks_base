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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <set>
#include <sstream>

#include "field_util.h"
#include "dimension.h"
#include "stats_log_util.h"

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
    mLogUid = msg.entry_v4.uid;
    init(mContext);
    if (mContext) {
        // android_log_destroy will set mContext to NULL
        android_log_destroy(&mContext);
    }
}

LogEvent::LogEvent(int32_t tagId, uint64_t timestampNs) {
    mTimestampNs = timestampNs;
    mTagId = tagId;
    mLogUid = 0;
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

bool LogEvent::write(const std::vector<AttributionNode>& nodes) {
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

bool LogEvent::write(const AttributionNode& node) {
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
         if (android_log_write_int32(mContext, node.uid()) < 0) {
            return false;
         }
         if (android_log_write_list_end(mContext) < 0) {
            return false;
         }
         return true;
    }
    return false;
}

namespace {

void increaseField(Field *field, bool is_child) {
    if (is_child) {
        if (field->child_size() <= 0) {
            field->add_child();
        }
    } else {
        field->clear_child();
    }
    Field* curr = is_child ? field->mutable_child(0) : field;
    if (!curr->has_field()) {
        curr->set_field(1);
    } else {
        curr->set_field(curr->field() + 1);
    }
}

}  // namespace

/**
 * The elements of each log event are stored as a vector of android_log_list_elements.
 * The goal is to do as little preprocessing as possible, because we read a tiny fraction
 * of the elements that are written to the log.
 */
void LogEvent::init(android_log_context context) {
    if (!context) {
        return;
    }
    android_log_list_element elem;
    // TODO: The log is actually structured inside one list.  This is convenient
    // because we'll be able to use it to put the attribution (WorkSource) block first
    // without doing our own tagging scheme.  Until that change is in, just drop the
    // list-related log elements and the order we get there is our index-keyed data
    // structure.
    int i = 0;

    int seenListStart = 0;

    Field fieldTree;
    Field* atomField = fieldTree.add_child();
    do {
        elem = android_log_read_next(context);
        switch ((int)elem.type) {
            case EVENT_TYPE_INT:
                // elem at [0] is EVENT_TYPE_LIST, [1] is the tag id.
                if (i == 1) {
                    mTagId = elem.data.int32;
                    fieldTree.set_field(mTagId);
                } else {
                    increaseField(atomField, seenListStart > 0/* is_child */);
                    mFieldValueMap[fieldTree].set_value_int(elem.data.int32);
                }
                break;
            case EVENT_TYPE_FLOAT:
                {
                    increaseField(atomField, seenListStart > 0/* is_child */);
                    mFieldValueMap[fieldTree].set_value_float(elem.data.float32);
                }
                break;
            case EVENT_TYPE_STRING:
                {
                    increaseField(atomField, seenListStart > 0/* is_child */);
                    mFieldValueMap[fieldTree].set_value_str(
                        string(elem.data.string, elem.len).c_str());
                }
                break;
            case EVENT_TYPE_LONG:
                {
                    increaseField(atomField, seenListStart > 0 /* is_child */);
                    mFieldValueMap[fieldTree].set_value_long(elem.data.int64);
                }
                break;
            case EVENT_TYPE_LIST:
                if (i >= 1) {
                    if (seenListStart > 0) {
                       increasePosition(atomField);
                    } else {
                        increaseField(atomField, false /* is_child */);
                    }
                    seenListStart++;
                    if (seenListStart >= 3) {
                        ALOGE("Depth > 2. Not supported!");
                        return;
                    }
                }
                break;
            case EVENT_TYPE_LIST_STOP:
                seenListStart--;
                if (seenListStart == 0) {
                    atomField->clear_position_index();
                } else {
                    if (atomField->child_size() > 0) {
                       atomField->mutable_child(0)->clear_field();
                    }
                }
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
    DimensionsValue value;
    if (!GetSimpleAtomDimensionsValueProto(key, &value)) {
        *err = BAD_INDEX;
        return 0;
    }
    const DimensionsValue* leafValue = getSingleLeafValue(&value);
    switch (leafValue->value_case()) {
        case DimensionsValue::ValueCase::kValueInt:
            return (int64_t)leafValue->value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return leafValue->value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return leafValue->value_bool() ? 1 : 0;
        case DimensionsValue::ValueCase::kValueFloat:
            return (int64_t)leafValue->value_float();
        case DimensionsValue::ValueCase::kValueTuple:
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::VALUE_NOT_SET: {
            *err = BAD_TYPE;
            return 0;
        }
    }
}

const char* LogEvent::GetString(size_t key, status_t* err) const {
    DimensionsValue value;
    if (!GetSimpleAtomDimensionsValueProto(key, &value)) {
        *err = BAD_INDEX;
        return 0;
    }
    const DimensionsValue* leafValue = getSingleLeafValue(&value);
    switch (leafValue->value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return leafValue->value_str().c_str();
        case DimensionsValue::ValueCase::kValueInt:
        case DimensionsValue::ValueCase::kValueLong:
        case DimensionsValue::ValueCase::kValueBool:
        case DimensionsValue::ValueCase::kValueFloat:
        case DimensionsValue::ValueCase::kValueTuple:
        case DimensionsValue::ValueCase::VALUE_NOT_SET: {
            *err = BAD_TYPE;
            return 0;
        }
    }
}

bool LogEvent::GetBool(size_t key, status_t* err) const {
    DimensionsValue value;
    if (!GetSimpleAtomDimensionsValueProto(key, &value)) {
        *err = BAD_INDEX;
        return 0;
    }
    const DimensionsValue* leafValue = getSingleLeafValue(&value);
    switch (leafValue->value_case()) {
        case DimensionsValue::ValueCase::kValueInt:
            return leafValue->value_int() != 0;
        case DimensionsValue::ValueCase::kValueLong:
            return leafValue->value_long() != 0;
        case DimensionsValue::ValueCase::kValueBool:
            return leafValue->value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return leafValue->value_float() != 0;
        case DimensionsValue::ValueCase::kValueTuple:
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::VALUE_NOT_SET: {
            *err = BAD_TYPE;
            return 0;
        }
    }
}

float LogEvent::GetFloat(size_t key, status_t* err) const {
    DimensionsValue value;
    if (!GetSimpleAtomDimensionsValueProto(key, &value)) {
        *err = BAD_INDEX;
        return 0;
    }
    const DimensionsValue* leafValue = getSingleLeafValue(&value);
    switch (leafValue->value_case()) {
        case DimensionsValue::ValueCase::kValueInt:
            return (float)leafValue->value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return (float)leafValue->value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return leafValue->value_bool() ? 1.0f : 0.0f;
        case DimensionsValue::ValueCase::kValueFloat:
            return leafValue->value_float();
        case DimensionsValue::ValueCase::kValueTuple:
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::VALUE_NOT_SET: {
            *err = BAD_TYPE;
            return 0;
        }
    }
}

void LogEvent::GetAtomDimensionsValueProtos(const FieldMatcher& matcher,
                                            std::vector<DimensionsValue> *dimensionsValues) const {
    findDimensionsValues(mFieldValueMap, matcher, dimensionsValues);
}

bool LogEvent::GetAtomDimensionsValueProto(const FieldMatcher& matcher,
                                           DimensionsValue* dimensionsValue) const {
    std::vector<DimensionsValue> rootDimensionsValues;
    findDimensionsValues(mFieldValueMap, matcher, &rootDimensionsValues);
    if (rootDimensionsValues.size() != 1) {
        return false;
    }
    *dimensionsValue = rootDimensionsValues.front();
    return true;
}

bool LogEvent::GetSimpleAtomDimensionsValueProto(size_t atomField,
                                                 DimensionsValue* dimensionsValue) const {
    FieldMatcher matcher;
    buildSimpleAtomFieldMatcher(mTagId, atomField, &matcher);
    return GetAtomDimensionsValueProto(matcher, dimensionsValue);
}

DimensionsValue* LogEvent::findFieldValueOrNull(const Field& field) {
    auto it = mFieldValueMap.find(field);
    if (it == mFieldValueMap.end()) {
        return nullptr;
    }
    return &it->second;
}

string LogEvent::ToString() const {
    ostringstream result;
    result << "{ " << mTimestampNs << " (" << mTagId << ")";
    for (const auto& itr : mFieldValueMap) {
        result << FieldToString(itr.first);
        result << "->";
        result << DimensionsValueToString(itr.second);
        result << " ";
    }
    result << " }";
    return result.str();
}

void LogEvent::ToProto(ProtoOutputStream& protoOutput) const {
    writeFieldValueTreeToStream(getFieldValueMap(), &protoOutput);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
