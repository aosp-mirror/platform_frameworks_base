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

#ifndef CONDITION_UTIL_H
#define CONDITION_UTIL_H

#include <vector>
#include "../matchers/matcher_util.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

enum ConditionState {
    kNotEvaluated = -2,
    kUnknown = -1,
    kFalse = 0,
    kTrue = 1,
};

ConditionState operator|(ConditionState l, ConditionState r);

ConditionState evaluateCombinationCondition(const std::vector<int>& children,
                                            const LogicalOperation& operation,
                                            const std::vector<ConditionState>& conditionCache);
}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // CONDITION_UTIL_H
