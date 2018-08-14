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

#define DEBUG false
#include "Log.h"
#include "FieldValue.h"
#include "HashableDimensionKey.h"

namespace android {
namespace os {
namespace statsd {

int32_t getEncodedField(int32_t pos[], int32_t depth, bool includeDepth) {
    int32_t field = 0;
    for (int32_t i = 0; i <= depth; i++) {
        int32_t shiftBits = 8 * (kMaxLogDepth - i);
        field |= (pos[i] << shiftBits);
    }

    if (includeDepth) {
        field |= (depth << 24);
    }
    return field;
}

int32_t encodeMatcherMask(int32_t mask[], int32_t depth) {
    return getEncodedField(mask, depth, false) | 0xff000000;
}

bool Field::matches(const Matcher& matcher) const {
    if (mTag != matcher.mMatcher.getTag()) {
        return false;
    }
    if ((mField & matcher.mMask) == matcher.mMatcher.getField()) {
        return true;
    }

    if (matcher.hasAllPositionMatcher() &&
        (mField & (matcher.mMask & kClearAllPositionMatcherMask)) == matcher.mMatcher.getField()) {
        return true;
    }

    return false;
}

void translateFieldMatcher(int tag, const FieldMatcher& matcher, int depth, int* pos, int* mask,
                           std::vector<Matcher>* output) {
    if (depth > kMaxLogDepth) {
        ALOGE("depth > 2");
        return;
    }

    pos[depth] = matcher.field();
    mask[depth] = 0x7f;

    if (matcher.has_position()) {
        depth++;
        if (depth > 2) {
            return;
        }
        switch (matcher.position()) {
            case Position::ALL:
                pos[depth] = 0x00;
                mask[depth] = 0x7f;
                break;
            case Position::ANY:
                pos[depth] = 0;
                mask[depth] = 0;
                break;
            case Position::FIRST:
                pos[depth] = 1;
                mask[depth] = 0x7f;
                break;
            case Position::LAST:
                pos[depth] = 0x80;
                mask[depth] = 0x80;
                break;
            case Position::POSITION_UNKNOWN:
                pos[depth] = 0;
                mask[depth] = 0;
                break;
        }
    }

    if (matcher.child_size() == 0) {
        output->push_back(Matcher(Field(tag, pos, depth), encodeMatcherMask(mask, depth)));
    } else {
        for (const auto& child : matcher.child()) {
            translateFieldMatcher(tag, child, depth + 1, pos, mask, output);
        }
    }
}

void translateFieldMatcher(const FieldMatcher& matcher, std::vector<Matcher>* output) {
    int pos[] = {1, 1, 1};
    int mask[] = {0x7f, 0x7f, 0x7f};
    int tag = matcher.field();
    for (const auto& child : matcher.child()) {
        translateFieldMatcher(tag, child, 0, pos, mask, output);
    }
}

bool isAttributionUidField(const FieldValue& value) {
    int field = value.mField.getField() & 0xff007f;
    if (field == 0x10001 && value.mValue.getType() == INT) {
        return true;
    }
    return false;
}

bool isAttributionUidField(const Field& field, const Value& value) {
    int f = field.getField() & 0xff007f;
    if (f == 0x10001 && value.getType() == INT) {
        return true;
    }
    return false;
}

Value::Value(const Value& from) {
    type = from.getType();
    switch (type) {
        case INT:
            int_value = from.int_value;
            break;
        case LONG:
            long_value = from.long_value;
            break;
        case FLOAT:
            float_value = from.float_value;
            break;
        case STRING:
            str_value = from.str_value;
            break;
        default:
            break;
    }
}

std::string Value::toString() const {
    switch (type) {
        case INT:
            return std::to_string(int_value) + "[I]";
        case LONG:
            return std::to_string(long_value) + "[L]";
        case FLOAT:
            return std::to_string(float_value) + "[F]";
        case STRING:
            return str_value + "[S]";
        default:
            return "[UNKNOWN]";
    }
}

bool Value::operator==(const Value& that) const {
    if (type != that.getType()) return false;

    switch (type) {
        case INT:
            return int_value == that.int_value;
        case LONG:
            return long_value == that.long_value;
        case FLOAT:
            return float_value == that.float_value;
        case STRING:
            return str_value == that.str_value;
        default:
            return false;
    }
}

bool Value::operator!=(const Value& that) const {
    if (type != that.getType()) return true;
    switch (type) {
        case INT:
            return int_value != that.int_value;
        case LONG:
            return long_value != that.long_value;
        case FLOAT:
            return float_value != that.float_value;
        case STRING:
            return str_value != that.str_value;
        default:
            return false;
    }
}

bool Value::operator<(const Value& that) const {
    if (type != that.getType()) return type < that.getType();

    switch (type) {
        case INT:
            return int_value < that.int_value;
        case LONG:
            return long_value < that.long_value;
        case FLOAT:
            return float_value < that.float_value;
        case STRING:
            return str_value < that.str_value;
        default:
            return false;
    }
}

bool equalDimensions(const std::vector<Matcher>& dimension_a,
                     const std::vector<Matcher>& dimension_b) {
    bool eq = dimension_a.size() == dimension_b.size();
    for (size_t i = 0; eq && i < dimension_a.size(); ++i) {
        if (dimension_b[i] != dimension_a[i]) {
            eq = false;
        }
    }
    return eq;
}

bool HasPositionANY(const FieldMatcher& matcher) {
    if (matcher.has_position() && matcher.position() == Position::ANY) {
        return true;
    }
    for (const auto& child : matcher.child()) {
        if (HasPositionANY(child)) {
            return true;
        }
    }
    return false;
}

bool HasPositionALL(const FieldMatcher& matcher) {
    if (matcher.has_position() && matcher.position() == Position::ALL) {
        return true;
    }
    for (const auto& child : matcher.child()) {
        if (HasPositionALL(child)) {
            return true;
        }
    }
    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android