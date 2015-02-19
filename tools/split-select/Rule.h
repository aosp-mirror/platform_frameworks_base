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

#ifndef H_ANDROID_SPLIT_RULE
#define H_ANDROID_SPLIT_RULE

#include "SplitDescription.h"

#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/String8.h>
#include <utils/Vector.h>

namespace split {

struct Rule : public virtual android::RefBase {
    inline Rule();
    Rule(const Rule& rhs);

    enum Operator {
        LESS_THAN = 1,
        GREATER_THAN,
        EQUALS,
        CONTAINS_ANY,
        CONTAINS_ALL,
        IS_TRUE,
        IS_FALSE,
        AND_SUBRULES,
        OR_SUBRULES,
        ALWAYS_TRUE,
    };

    Operator op;

    enum Key {
        NONE = 0,
        SDK_VERSION,
        SCREEN_DENSITY,
        LANGUAGE,
        NATIVE_PLATFORM,
        TOUCH_SCREEN,
        SCREEN_SIZE,
        SCREEN_LAYOUT,
    };

    Key key;
    bool negate;

    android::Vector<android::String8> stringArgs;
    android::Vector<int> longArgs;
    android::Vector<double> doubleArgs;
    android::Vector<android::sp<Rule> > subrules;

    android::String8 toJson(int indent=0) const;

    static android::sp<Rule> simplify(android::sp<Rule> rule);
};

Rule::Rule()
: op(ALWAYS_TRUE)
, key(NONE)
, negate(false) {}

} // namespace split

#endif // H_ANDROID_SPLIT_RULE
