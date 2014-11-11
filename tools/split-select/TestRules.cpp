/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "TestRules.h"

#include <utils/String8.h>

using android::String8;
using android::sp;

namespace split {
namespace test {

const Rule EqRule(Rule::Key key, long value) {
    Rule rule;
    rule.op = Rule::EQUALS;
    rule.key = key;
    rule.longArgs.add(value);
    return rule;
}

const Rule GtRule(Rule::Key key, long value) {
    Rule rule;
    rule.op = Rule::GREATER_THAN;
    rule.key = key;
    rule.longArgs.add(value);
    return rule;
}

const Rule LtRule(Rule::Key key, long value) {
    Rule rule;
    rule.op = Rule::LESS_THAN;
    rule.key = key;
    rule.longArgs.add(value);
    return rule;
}

const Rule ContainsAnyRule(Rule::Key key, const char* str1) {
    Rule rule;
    rule.op = Rule::CONTAINS_ANY;
    rule.key = key;
    rule.stringArgs.add(String8(str1));
    return rule;
}

const Rule ContainsAnyRule(Rule::Key key, const char* str1, const char* str2) {
    Rule rule;
    rule.op = Rule::CONTAINS_ANY;
    rule.key = key;
    rule.stringArgs.add(String8(str1));
    rule.stringArgs.add(String8(str2));
    return rule;
}

const Rule AlwaysTrue() {
    Rule rule;
    rule.op = Rule::ALWAYS_TRUE;
    return rule;
}

::testing::AssertionResult RulePredFormat(
        const char*, const char*,
        const sp<Rule>& actual, const Rule& expected) {
    const String8 expectedStr(expected.toJson());
    const String8 actualStr(actual != NULL ? actual->toJson() : String8());

    if (expectedStr != actualStr) {
        return ::testing::AssertionFailure()
                << "Expected: " << expectedStr.string() << "\n"
                << "  Actual: " << actualStr.string();
    }
    return ::testing::AssertionSuccess();
}


} // namespace test
} // namespace split
