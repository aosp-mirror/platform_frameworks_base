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

#include "annotations.h"
#include "stats_log_util.h"
#include "statslog_statsd.h"

#include <android/binder_ibinder.h>
#include <android-base/stringprintf.h>
#include <private/android_filesystem_config.h>

namespace android {
namespace os {
namespace statsd {

// for TrainInfo experiment id serialization
const int FIELD_ID_EXPERIMENT_ID = 1;

using namespace android::util;
using android::base::StringPrintf;
using android::util::ProtoOutputStream;
using std::string;
using std::vector;

// stats_event.h socket types. Keep in sync.
/* ERRORS */
#define ERROR_NO_TIMESTAMP 0x1
#define ERROR_NO_ATOM_ID 0x2
#define ERROR_OVERFLOW 0x4
#define ERROR_ATTRIBUTION_CHAIN_TOO_LONG 0x8
#define ERROR_TOO_MANY_KEY_VALUE_PAIRS 0x10
#define ERROR_ANNOTATION_DOES_NOT_FOLLOW_FIELD 0x20
#define ERROR_INVALID_ANNOTATION_ID 0x40
#define ERROR_ANNOTATION_ID_TOO_LARGE 0x80
#define ERROR_TOO_MANY_ANNOTATIONS 0x100
#define ERROR_TOO_MANY_FIELDS 0x200
#define ERROR_INVALID_VALUE_TYPE 0x400
#define ERROR_STRING_NOT_NULL_TERMINATED 0x800

/* TYPE IDS */
#define INT32_TYPE 0x00
#define INT64_TYPE 0x01
#define STRING_TYPE 0x02
#define LIST_TYPE 0x03
#define FLOAT_TYPE 0x04
#define BOOL_TYPE 0x05
#define BYTE_ARRAY_TYPE 0x06
#define OBJECT_TYPE 0x07
#define KEY_VALUE_PAIRS_TYPE 0x08
#define ATTRIBUTION_CHAIN_TYPE 0x09
#define ERROR_TYPE 0x0F

LogEvent::LogEvent(const LogEvent& event) {
    mTagId = event.mTagId;
    mLogUid = event.mLogUid;
    mLogPid = event.mLogPid;
    mElapsedTimestampNs = event.mElapsedTimestampNs;
    mLogdTimestampNs = event.mLogdTimestampNs;
    mValues = event.mValues;
}

LogEvent::LogEvent(int32_t uid, int32_t pid)
    : mLogdTimestampNs(time(nullptr)),
      mLogUid(uid),
      mLogPid(pid) {
}

LogEvent::LogEvent(const string& trainName, int64_t trainVersionCode, bool requiresStaging,
                   bool rollbackEnabled, bool requiresLowLatencyMonitor, int32_t state,
                   const std::vector<uint8_t>& experimentIds, int32_t userId) {
    mLogdTimestampNs = getWallClockNs();
    mElapsedTimestampNs = getElapsedRealtimeNs();
    mTagId = util::BINARY_PUSH_STATE_CHANGED;
    mLogUid = AIBinder_getCallingUid();
    mLogPid = AIBinder_getCallingPid();

    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(1)), Value(trainName)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(trainVersionCode)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)), Value((int)requiresStaging)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)), Value((int)rollbackEnabled)));
    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(5)), Value((int)requiresLowLatencyMonitor)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(6)), Value(state)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(7)), Value(experimentIds)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(8)), Value(userId)));
}

LogEvent::LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                   const InstallTrainInfo& trainInfo) {
    mLogdTimestampNs = wallClockTimestampNs;
    mElapsedTimestampNs = elapsedTimestampNs;
    mTagId = util::TRAIN_INFO;

    mValues.push_back(
            FieldValue(Field(mTagId, getSimpleField(1)), Value(trainInfo.trainVersionCode)));
    std::vector<uint8_t> experimentIdsProto;
    writeExperimentIdsToProto(trainInfo.experimentIds, &experimentIdsProto);
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(2)), Value(experimentIdsProto)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(3)), Value(trainInfo.trainName)));
    mValues.push_back(FieldValue(Field(mTagId, getSimpleField(4)), Value(trainInfo.status)));
}

LogEvent::~LogEvent() {
    if (mContext) {
        // This is for the case when LogEvent is created using the test interface
        // but init() isn't called.
        android_log_destroy(&mContext);
    }
}

void LogEvent::parseInt32(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    int32_t value = readNextValue<int32_t>();
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseInt64(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    int64_t value = readNextValue<int64_t>();
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseString(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    int32_t numBytes = readNextValue<int32_t>();
    if ((uint32_t)numBytes > mRemainingLen) {
        mValid = false;
        return;
    }

    string value = string((char*)mBuf, numBytes);
    mBuf += numBytes;
    mRemainingLen -= numBytes;
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseFloat(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    float value = readNextValue<float>();
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseBool(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    // cast to int32_t because FieldValue does not support bools
    int32_t value = (int32_t)readNextValue<uint8_t>();
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseByteArray(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    int32_t numBytes = readNextValue<int32_t>();
    if ((uint32_t)numBytes > mRemainingLen) {
        mValid = false;
        return;
    }

    vector<uint8_t> value(mBuf, mBuf + numBytes);
    mBuf += numBytes;
    mRemainingLen -= numBytes;
    addToValues(pos, depth, value, last);
    parseAnnotations(numAnnotations);
}

void LogEvent::parseKeyValuePairs(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations) {
    int32_t numPairs = readNextValue<uint8_t>();

    for (pos[1] = 1; pos[1] <= numPairs; pos[1]++) {
        last[1] = (pos[1] == numPairs);

        // parse key
        pos[2] = 1;
        parseInt32(pos, /*depth=*/2, last, /*numAnnotations=*/0);

        // parse value
        last[2] = true;

        uint8_t typeInfo = readNextValue<uint8_t>();
        switch (getTypeId(typeInfo)) {
            case INT32_TYPE:
                pos[2] = 2; // pos[2] determined by index of type in KeyValuePair in atoms.proto
                parseInt32(pos, /*depth=*/2, last, /*numAnnotations=*/0);
                break;
            case INT64_TYPE:
                pos[2] = 3;
                parseInt64(pos, /*depth=*/2, last, /*numAnnotations=*/0);
                break;
            case STRING_TYPE:
                pos[2] = 4;
                parseString(pos, /*depth=*/2, last, /*numAnnotations=*/0);
                break;
            case FLOAT_TYPE:
                pos[2] = 5;
                parseFloat(pos, /*depth=*/2, last, /*numAnnotations=*/0);
                break;
            default:
                mValid = false;
        }
    }

    parseAnnotations(numAnnotations);

    pos[1] = pos[2] = 1;
    last[1] = last[2] = false;
}

void LogEvent::parseAttributionChain(int32_t* pos, int32_t depth, bool* last,
                                     uint8_t numAnnotations) {
    int firstUidInChainIndex = mValues.size();
    int32_t numNodes = readNextValue<uint8_t>();
    for (pos[1] = 1; pos[1] <= numNodes; pos[1]++) {
        last[1] = (pos[1] == numNodes);

        // parse uid
        pos[2] = 1;
        parseInt32(pos, /*depth=*/2, last, /*numAnnotations=*/0);

        // parse tag
        pos[2] = 2;
        last[2] = true;
        parseString(pos, /*depth=*/2, last, /*numAnnotations=*/0);
    }

    parseAnnotations(numAnnotations, firstUidInChainIndex);

    pos[1] = pos[2] = 1;
    last[1] = last[2] = false;
}

void LogEvent::parseIsUidAnnotation(uint8_t annotationType) {
    if (mValues.empty() || annotationType != BOOL_TYPE) {
        mValid = false;
        return;
    }

    bool isUid = readNextValue<uint8_t>();
    if (isUid) mUidFieldIndex = mValues.size() - 1;
}

void LogEvent::parseTruncateTimestampAnnotation(uint8_t annotationType) {
    if (!mValues.empty() || annotationType != BOOL_TYPE) {
        mValid = false;
        return;
    }

    mTruncateTimestamp = readNextValue<uint8_t>();
}

void LogEvent::parseStateOptionAnnotation(uint8_t annotationType, int firstUidInChainIndex) {
    if (mValues.empty() || annotationType != INT32_TYPE) {
        mValid = false;
        return;
    }

    int32_t stateOption = readNextValue<int32_t>();
    switch (stateOption) {
        case STATE_OPTION_EXCLUSIVE_STATE:
            mValues[mValues.size() - 1].mAnnotations.setExclusiveState(true);
            break;
        case STATE_OPTION_PRIMARY_FIELD:
            mValues[mValues.size() - 1].mAnnotations.setPrimaryField(true);
            break;
        case STATE_OPTION_PRIMARY_FIELD_FIRST_UID:
            if (firstUidInChainIndex == -1) {
                mValid = false;
            } else {
                mValues[firstUidInChainIndex].mAnnotations.setPrimaryField(true);
            }
            break;
        default:
            mValid = false;
    }
}

void LogEvent::parseResetStateAnnotation(uint8_t annotationType) {
    if (mValues.empty() || annotationType != INT32_TYPE) {
        mValid = false;
        return;
    }

    int32_t resetState = readNextValue<int32_t>();
    mValues[mValues.size() - 1].mAnnotations.setResetState(resetState);
}

void LogEvent::parseStateNestedAnnotation(uint8_t annotationType) {
    if (mValues.empty() || annotationType != BOOL_TYPE) {
        mValid = false;
        return;
    }

    bool nested = readNextValue<uint8_t>();
    mValues[mValues.size() - 1].mAnnotations.setNested(nested);
}

// firstUidInChainIndex is a default parameter that is only needed when parsing
// annotations for attribution chains.
void LogEvent::parseAnnotations(uint8_t numAnnotations, int firstUidInChainIndex) {
    for (uint8_t i = 0; i < numAnnotations; i++) {
        uint8_t annotationId = readNextValue<uint8_t>();
        uint8_t annotationType = readNextValue<uint8_t>();

        switch (annotationId) {
            case ANNOTATION_ID_IS_UID:
                parseIsUidAnnotation(annotationType);
                break;
            case ANNOTATION_ID_TRUNCATE_TIMESTAMP:
                parseTruncateTimestampAnnotation(annotationType);
                break;
            case ANNOTATION_ID_STATE_OPTION:
                parseStateOptionAnnotation(annotationType, firstUidInChainIndex);
                break;
            case ANNOTATION_ID_RESET_STATE:
                parseResetStateAnnotation(annotationType);
                break;
            case ANNOTATION_ID_STATE_NESTED:
                parseStateNestedAnnotation(annotationType);
                break;
            default:
                mValid = false;
                return;
        }
    }
}

// This parsing logic is tied to the encoding scheme used in StatsEvent.java and
// stats_event.c
bool LogEvent::parseBuffer(uint8_t* buf, size_t len) {
    mBuf = buf;
    mRemainingLen = (uint32_t)len;

    int32_t pos[] = {1, 1, 1};
    bool last[] = {false, false, false};

    // Beginning of buffer is OBJECT_TYPE | NUM_FIELDS | TIMESTAMP | ATOM_ID
    uint8_t typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != OBJECT_TYPE) mValid = false;

    uint8_t numElements = readNextValue<uint8_t>();
    if (numElements < 2 || numElements > 127) mValid = false;

    typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != INT64_TYPE) mValid = false;
    mElapsedTimestampNs = readNextValue<int64_t>();
    numElements--;

    typeInfo = readNextValue<uint8_t>();
    if (getTypeId(typeInfo) != INT32_TYPE) mValid = false;
    mTagId = readNextValue<int32_t>();
    numElements--;
    parseAnnotations(getNumAnnotations(typeInfo)); // atom-level annotations


    for (pos[0] = 1; pos[0] <= numElements && mValid; pos[0]++) {
        last[0] = (pos[0] == numElements);

        typeInfo = readNextValue<uint8_t>();
        uint8_t typeId = getTypeId(typeInfo);

        // TODO(b/144373276): handle errors passed to the socket
        switch(typeId) {
            case BOOL_TYPE:
                parseBool(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case INT32_TYPE:
                parseInt32(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case INT64_TYPE:
                parseInt64(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case FLOAT_TYPE:
                parseFloat(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case BYTE_ARRAY_TYPE:
                parseByteArray(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case STRING_TYPE:
                parseString(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case KEY_VALUE_PAIRS_TYPE:
                parseKeyValuePairs(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                break;
            case ATTRIBUTION_CHAIN_TYPE:
                parseAttributionChain(pos, /*depth=*/0, last, getNumAnnotations(typeInfo));
                if (mAttributionChainIndex == -1) mAttributionChainIndex = pos[0];
                break;
            default:
                mValid = false;
        }
    }

    if (mRemainingLen != 0) mValid = false;
    mBuf = nullptr;
    return mValid;
}

uint8_t LogEvent::getTypeId(uint8_t typeInfo) {
    return typeInfo & 0x0F; // type id in lower 4 bytes
}

uint8_t LogEvent::getNumAnnotations(uint8_t typeInfo) {
    return (typeInfo >> 4) & 0x0F; // num annotations in upper 4 bytes
}

int64_t LogEvent::GetLong(size_t key, status_t* err) const {
    // TODO(b/110561208): encapsulate the magical operations in Field struct as static functions
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

std::vector<uint8_t> LogEvent::GetStorage(size_t key, status_t* err) const {
    int field = getSimpleField(key);
    for (const auto& value : mValues) {
      if (value.mField.getField() == field) {
        if (value.mValue.getType() == STORAGE) {
          return value.mValue.storage_value;
        } else {
          *err = BAD_TYPE;
          return vector<uint8_t>();
        }
      }
      if ((size_t)value.mField.getPosAtDepth(0) > key) {
        break;
      }
    }

    *err = BAD_INDEX;
    return vector<uint8_t>();
}

string LogEvent::ToString() const {
    string result;
    result += StringPrintf("{ uid(%d) %lld %lld (%d)", mLogUid, (long long)mLogdTimestampNs,
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

void writeExperimentIdsToProto(const std::vector<int64_t>& experimentIds,
                               std::vector<uint8_t>* protoOut) {
    ProtoOutputStream proto;
    for (const auto& expId : experimentIds) {
        proto.write(FIELD_TYPE_INT64 | FIELD_COUNT_REPEATED | FIELD_ID_EXPERIMENT_ID,
                    (long long)expId);
    }

    protoOut->resize(proto.size());
    size_t pos = 0;
    sp<ProtoReader> reader = proto.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(protoOut->data() + pos, reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
