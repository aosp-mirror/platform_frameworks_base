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
#include <private/android_logger.h>

#include <string>
#include <vector>

namespace android {
namespace os {
namespace statsd {

struct InstallTrainInfo {
    int64_t trainVersionCode;
    std::string trainName;
    int32_t status;
    std::vector<int64_t> experimentIds;
    bool requiresStaging;
    bool rollbackEnabled;
    bool requiresLowLatencyMonitor;
};

/**
 * This class decodes the structured, serialized encoding of an atom into a
 * vector of FieldValues.
 */
class LogEvent {
public:
    /**
     * \param uid user id of the logging caller
     * \param pid process id of the logging caller
     */
    explicit LogEvent(int32_t uid, int32_t pid);

    /**
     * Parses the atomId, timestamp, and vector of values from a buffer
     * containing the StatsEvent/AStatsEvent encoding of an atom.
     *
     * \param buf a buffer that begins at the start of the serialized atom (it
     * should not include the android_log_header_t or the StatsEventTag)
     * \param len size of the buffer
     *
     * \return success of the initialization
     */
    bool parseBuffer(uint8_t* buf, size_t len);

    // Constructs a BinaryPushStateChanged LogEvent from API call.
    explicit LogEvent(const std::string& trainName, int64_t trainVersionCode, bool requiresStaging,
                      bool rollbackEnabled, bool requiresLowLatencyMonitor, int32_t state,
                      const std::vector<uint8_t>& experimentIds, int32_t userId);

    explicit LogEvent(int64_t wallClockTimestampNs, int64_t elapsedTimestampNs,
                      const InstallTrainInfo& installTrainInfo);

    ~LogEvent() {}

    /**
     * Get the timestamp associated with this event.
     */
    inline int64_t GetLogdTimestampNs() const { return mLogdTimestampNs; }
    inline int64_t GetElapsedTimestampNs() const { return mElapsedTimestampNs; }

    /**
     * Get the tag for this event.
     */
    inline int GetTagId() const { return mTagId; }

    /**
     * Get the uid of the logging client.
     * Returns -1 if the uid is unknown/has not been set.
     */
    inline int32_t GetUid() const { return mLogUid; }

    /**
     * Get the pid of the logging client.
     * Returns -1 if the pid is unknown/has not been set.
     */
    inline int32_t GetPid() const { return mLogPid; }

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
    std::vector<uint8_t> GetStorage(size_t key, status_t* err) const;

    /**
     * Return a string representation of this event.
     */
    std::string ToString() const;

    /**
     * Write this object to a ProtoOutputStream.
     */
    void ToProto(android::util::ProtoOutputStream& out) const;

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

    // Default value = false
    inline bool shouldTruncateTimestamp() const {
        return mTruncateTimestamp;
    }

    // Returns the index of the uid field within the FieldValues vector if the
    // uid exists. If there is no uid field, returns -1.
    //
    // If the index within the atom definition is desired, do the following:
    //    int vectorIndex = LogEvent.getUidFieldIndex();
    //    if (vectorIndex != -1) {
    //        FieldValue& v = LogEvent.getValues()[vectorIndex];
    //        int atomIndex = v.mField.getPosAtDepth(0);
    //    }
    // Note that atomIndex is 1-indexed.
    inline int getUidFieldIndex() {
        return static_cast<int>(mUidFieldIndex);
    }

    // Returns whether this LogEvent has an AttributionChain.
    // If it does and indexRange is not a nullptr, populate indexRange with the start and end index
    // of the AttributionChain within mValues.
    bool hasAttributionChain(std::pair<int, int>* indexRange = nullptr) const;

    // Returns the index of the exclusive state field within the FieldValues vector if
    // an exclusive state exists. If there is no exclusive state field, returns -1.
    //
    // If the index within the atom definition is desired, do the following:
    //    int vectorIndex = LogEvent.getExclusiveStateFieldIndex();
    //    if (vectorIndex != -1) {
    //        FieldValue& v = LogEvent.getValues()[vectorIndex];
    //        int atomIndex = v.mField.getPosAtDepth(0);
    //    }
    // Note that atomIndex is 1-indexed.
    inline int getExclusiveStateFieldIndex() const {
        return static_cast<int>(mExclusiveStateFieldIndex);
    }

    // If a reset state is not sent in the StatsEvent, returns -1. Note that a
    // reset state is sent if and only if a reset should be triggered.
    inline int getResetState() const {
        return mResetState;
    }

    inline LogEvent makeCopy() {
        return LogEvent(*this);
    }

    template <class T>
    status_t updateValue(size_t key, T& value, Type type) {
        int field = getSimpleField(key);
        for (auto& fieldValue : mValues) {
            if (fieldValue.mField.getField() == field) {
                if (fieldValue.mValue.getType() == type) {
                    fieldValue.mValue = Value(value);
                   return OK;
               } else {
                   return BAD_TYPE;
                }
            }
        }
        return BAD_INDEX;
    }

    bool isValid() const {
        return mValid;
    }

private:
    /**
     * Only use this if copy is absolutely needed.
     */
    LogEvent(const LogEvent&);

    void parseInt32(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseInt64(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseString(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseFloat(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseBool(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseByteArray(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseKeyValuePairs(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);
    void parseAttributionChain(int32_t* pos, int32_t depth, bool* last, uint8_t numAnnotations);

    void parseAnnotations(uint8_t numAnnotations, int firstUidInChainIndex = -1);
    void parseIsUidAnnotation(uint8_t annotationType);
    void parseTruncateTimestampAnnotation(uint8_t annotationType);
    void parsePrimaryFieldAnnotation(uint8_t annotationType);
    void parsePrimaryFieldFirstUidAnnotation(uint8_t annotationType, int firstUidInChainIndex);
    void parseExclusiveStateAnnotation(uint8_t annotationType);
    void parseTriggerStateResetAnnotation(uint8_t annotationType);
    void parseStateNestedAnnotation(uint8_t annotationType);
    bool checkPreviousValueType(Type expected);

    /**
     * The below two variables are only valid during the execution of
     * parseBuffer. There are no guarantees about the state of these variables
     * before/after.
     */
    uint8_t* mBuf;
    uint32_t mRemainingLen; // number of valid bytes left in the buffer being parsed

    bool mValid = true; // stores whether the event we received from the socket is valid

    /**
     * Side-effects:
     *    If there is enough space in buffer to read value of type T
     *        - move mBuf past the value that was just read
     *        - decrement mRemainingLen by size of T
     *    Else
     *        - set mValid to false
     */
    template <class T>
    T readNextValue() {
        T value;
        if (mRemainingLen < sizeof(T)) {
            mValid = false;
            value = 0; // all primitive types can successfully cast 0
        } else {
            // When alignof(T) == 1, hopefully the compiler can optimize away
            // this conditional as always true.
            if ((reinterpret_cast<uintptr_t>(mBuf) % alignof(T)) == 0) {
                // We're properly aligned, and can safely make this assignment.
                value = *((T*)mBuf);
            } else {
                // We need to use memcpy.  It's slower, but safe.
                memcpy(&value, mBuf, sizeof(T));
            }
            mBuf += sizeof(T);
            mRemainingLen -= sizeof(T);
        }
        return value;
    }

    template <class T>
    void addToValues(int32_t* pos, int32_t depth, T& value, bool* last) {
        Field f = Field(mTagId, pos, depth);
        // do not decorate last position at depth 0
        for (int i = 1; i < depth; i++) {
            if (last[i]) f.decorateLastPos(i);
        }

        Value v = Value(value);
        mValues.push_back(FieldValue(f, v));
    }

    uint8_t getTypeId(uint8_t typeInfo);
    uint8_t getNumAnnotations(uint8_t typeInfo);

    // The items are naturally sorted in DFS order as we read them. this allows us to do fast
    // matching.
    std::vector<FieldValue> mValues;

    // The timestamp set by the logd.
    int64_t mLogdTimestampNs;

    // The elapsed timestamp set by statsd log writer.
    int64_t mElapsedTimestampNs;

    // The atom tag of the event (defaults to 0 if client does not
    // appropriately set the atom id).
    int mTagId = 0;

    // The uid of the logging client (defaults to -1).
    int32_t mLogUid = -1;

    // The pid of the logging client (defaults to -1).
    int32_t mLogPid = -1;

    // Annotations
    bool mTruncateTimestamp = false;
    int mResetState = -1;

    // Indexes within the FieldValue vector can be stored in 7 bits because
    // that's the assumption enforced by the encoding used in FieldValue.
    int8_t mUidFieldIndex = -1;
    int8_t mAttributionChainStartIndex = -1;
    int8_t mAttributionChainEndIndex = -1;
    int8_t mExclusiveStateFieldIndex = -1;
};

void writeExperimentIdsToProto(const std::vector<int64_t>& experimentIds, std::vector<uint8_t>* protoOut);

}  // namespace statsd
}  // namespace os
}  // namespace android
