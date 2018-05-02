/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

class HashableDimensionKey;
struct Matcher;
struct Field;
struct FieldValue;

const int32_t kAttributionField = 1;
const int32_t kMaxLogDepth = 2;
const int32_t kLastBitMask = 0x80;
const int32_t kClearLastBitDeco = 0x7f;
const int32_t kClearAllPositionMatcherMask = 0xffff00ff;

enum Type { UNKNOWN, INT, LONG, FLOAT, STRING };

int32_t getEncodedField(int32_t pos[], int32_t depth, bool includeDepth);

int32_t encodeMatcherMask(int32_t mask[], int32_t depth);

// Get the encoded field for a leaf with a [field] number at depth 0;
inline int32_t getSimpleField(size_t field) {
    return ((int32_t)field << 8 * 2);
}

/**
 * Field is a wrapper class for 2 integers that represents the field of a log element in its Atom
 * proto.
 * [mTag]: the atom id.
 * [mField]: encoded path from the root (atom) to leaf.
 *
 * For example:
 * WakeLockStateChanged {
 *    repeated AttributionNode = 1;
 *    int state = 2;
 *    string tag = 3;
 * }
 * Read from logd, the items are structured as below:
 * [[[1000, "tag"], [2000, "tag2"],], 2,"hello"]
 *
 * When we read through the list, we will encode each field in a 32bit integer.
 * 8bit segments   |--------|--------|--------|--------|
 *                    Depth   field0 [L]field1 [L]field1
 *
 *  The first 8 bits are the depth of the field. for example, the uid 1000 has depth 2.
 *  The following 3 8-bit are for the item's position at each level.
 *  The first bit of each 8bits field is reserved to mark if the item is the last item at that level
 *  this is to make matching easier later.
 *
 *  The above wakelock event is translated into FieldValue pairs.
 *  0x02010101->1000
 *  0x02010182->tag
 *  0x02018201->2000
 *  0x02018282->tag2
 *  0x00020000->2
 *  0x00030000->"hello"
 *
 *  This encoding is the building block for the later operations.
 *  Please see the definition for Matcher below to see how the matching is done.
 */
struct Field {
private:
    int32_t mTag;
    int32_t mField;

public:
    Field() {}

    Field(int32_t tag, int32_t pos[], int32_t depth) : mTag(tag) {
        mField = getEncodedField(pos, depth, true);
    }

    Field(const Field& from) : mTag(from.getTag()), mField(from.getField()) {
    }

    Field(int32_t tag, int32_t field) : mTag(tag), mField(field){};

    inline void setField(int32_t field) {
        mField = field;
    }

    inline void setTag(int32_t tag) {
        mTag = tag;
    }

    inline void decorateLastPos(int32_t depth) {
        int32_t mask = kLastBitMask << 8 * (kMaxLogDepth - depth);
        mField |= mask;
    }

    inline int32_t getTag() const {
        return mTag;
    }

    inline int32_t getDepth() const {
        return (mField >> 24);
    }

    inline int32_t getPath(int32_t depth) const {
        if (depth > 2 || depth < 0) return 0;

        int32_t field = (mField & 0x00ffffff);
        int32_t mask = 0xffffffff;
        return (field & (mask << 8 * (kMaxLogDepth - depth)));
    }

    inline int32_t getPrefix(int32_t depth) const {
        if (depth == 0) return 0;
        return getPath(depth - 1);
    }

    inline int32_t getField() const {
        return mField;
    }

    inline int32_t getRawPosAtDepth(int32_t depth) const {
        int32_t field = (mField & 0x00ffffff);
        int32_t shift = 8 * (kMaxLogDepth - depth);
        int32_t mask = 0xff << shift;

        return (field & mask) >> shift;
    }

    inline int32_t getPosAtDepth(int32_t depth) const {
        return getRawPosAtDepth(depth) & kClearLastBitDeco;
    }

    // Check if the first bit of the 8-bit segment for depth is 1
    inline bool isLastPos(int32_t depth) const {
        int32_t field = (mField & 0x00ffffff);
        int32_t mask = kLastBitMask << 8 * (kMaxLogDepth - depth);
        return (field & mask) != 0;
    }

    // if the 8-bit segment is all 0's
    inline bool isAnyPosMatcher(int32_t depth) const {
        return getDepth() >= depth && getRawPosAtDepth(depth) == 0;
    }
    // if the 8bit is 0x80 (1000 0000)
    inline bool isLastPosMatcher(int32_t depth) const {
        return getDepth() >= depth && getRawPosAtDepth(depth) == kLastBitMask;
    }

    inline bool operator==(const Field& that) const {
        return mTag == that.getTag() && mField == that.getField();
    };

    inline bool operator!=(const Field& that) const {
        return mTag != that.getTag() || mField != that.getField();
    };

    bool operator<(const Field& that) const {
        if (mTag != that.getTag()) {
            return mTag < that.getTag();
        }

        if (mField != that.getField()) {
            return mField < that.getField();
        }

        return false;
    }
    bool matches(const Matcher& that) const;
};

/**
 * Matcher represents a leaf matcher in the FieldMatcher in statsd_config.
 *
 * It contains all information needed to match one or more leaf node.
 * All information is encoded in a Field(2 ints) and a bit mask(1 int).
 *
 * For example, to match the first/any/last uid field in attribution chain in Atom 10,
 * we have the following FieldMatcher in statsd_config
 *    FieldMatcher {
 *        field:10
 *         FieldMatcher {
 *              field:1
 *              position: any/last/first
 *              FieldMatcher {
 *                  field:1
 *              }
 *          }
 *     }
 *
 * We translate the FieldMatcher into a Field, and mask
 * First: [Matcher Field] 0x02010101  [Mask]0xff7f7f7f
 * Last:  [Matcher Field] 0x02018001  [Mask]0xff7f807f
 * Any:   [Matcher Field] 0x02010001  [Mask]0xff7f007f
 * All:   [Matcher Field] 0x02010001  [Mask]0xff7f7f7f
 *
 * [To match a log Field with a Matcher] we apply the bit mask to the log Field and check if
 * the result is equal to the Matcher Field. That's a bit wise AND operation + check if 2 ints are
 * equal. Nothing can beat the performance of this matching algorithm.
 *
 * TODO: ADD EXAMPLE HERE.
 */
struct Matcher {
    Matcher(const Field& matcher, int32_t mask) : mMatcher(matcher), mMask(mask){};

    const Field mMatcher;
    const int32_t mMask;

    inline const Field& getMatcher() const {
        return mMatcher;
    }

    inline int32_t getMask() const {
        return mMask;
    }

    inline int32_t getRawMaskAtDepth(int32_t depth) const {
        int32_t field = (mMask & 0x00ffffff);
        int32_t shift = 8 * (kMaxLogDepth - depth);
        int32_t mask = 0xff << shift;

        return (field & mask) >> shift;
    }

    bool hasAllPositionMatcher() const {
        return mMatcher.getDepth() == 2 && getRawMaskAtDepth(1) == 0x7f;
    }

    bool hasAnyPositionMatcher(int* prefix) const {
        if (mMatcher.getDepth() == 2 && mMatcher.getRawPosAtDepth(1) == 0) {
            (*prefix) = mMatcher.getPrefix(1);
            return true;
        }
        return false;
    }

    inline bool operator!=(const Matcher& that) const {
        return mMatcher != that.getMatcher() || mMask != that.getMask();
    }

    inline bool operator==(const Matcher& that) const {
        return mMatcher == that.mMatcher && mMask == that.mMask;
    }
};

inline Matcher getSimpleMatcher(int32_t tag, size_t field) {
    return Matcher(Field(tag, getSimpleField(field)), 0xff7f0000);
}

/**
 * A wrapper for a union type to contain multiple types of values.
 *
 */
struct Value {
    Value() : type(UNKNOWN) {}

    Value(int32_t v) {
        int_value = v;
        type = INT;
    }

    Value(int64_t v) {
        long_value = v;
        type = LONG;
    }

    Value(float v) {
        float_value = v;
        type = FLOAT;
    }

    Value(const std::string& v) {
        str_value = v;
        type = STRING;
    }

    void setInt(int32_t v) {
        int_value = v;
        type = INT;
    }

    void setLong(int64_t v) {
        long_value = v;
        type = LONG;
    }

    union {
        int32_t int_value;
        int64_t long_value;
        float float_value;
    };
    std::string str_value;

    Type type;

    std::string toString() const;

    Type getType() const {
        return type;
    }

    Value(const Value& from);

    bool operator==(const Value& that) const;
    bool operator!=(const Value& that) const;

    bool operator<(const Value& that) const;
};

/**
 * Represents a log item, or a dimension item (They are essentially the same).
 */
struct FieldValue {
    FieldValue() {}
    FieldValue(const Field& field, const Value& value) : mField(field), mValue(value) {
    }
    bool operator==(const FieldValue& that) const {
        return mField == that.mField && mValue == that.mValue;
    }
    bool operator!=(const FieldValue& that) const {
        return mField != that.mField || mValue != that.mValue;
    }
    bool operator<(const FieldValue& that) const {
        if (mField != that.mField) {
            return mField < that.mField;
        }

        if (mValue != that.mValue) {
            return mValue < that.mValue;
        }

        return false;
    }

    Field mField;
    Value mValue;
};

bool HasPositionANY(const FieldMatcher& matcher);
bool HasPositionALL(const FieldMatcher& matcher);

bool isAttributionUidField(const FieldValue& value);

void translateFieldMatcher(const FieldMatcher& matcher, std::vector<Matcher>* output);

bool isAttributionUidField(const Field& field, const Value& value);

bool equalDimensions(const std::vector<Matcher>& dimension_a,
                     const std::vector<Matcher>& dimension_b);
}  // namespace statsd
}  // namespace os
}  // namespace android
