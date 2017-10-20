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

#include "Log.h"

#include "condition_util.h"

#include <log/event_tag_map.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <utils/Errors.h>
#include <unordered_map>
#include "../matchers/matcher_util.h"
#include "ConditionTracker.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

using std::set;
using std::string;
using std::unordered_map;
using std::vector;


ConditionState evaluateCombinationCondition(const std::vector<int>& children,
                                            const LogicalOperation& operation,
                                            const std::vector<ConditionState>& conditionCache) {
    ConditionState newCondition;

    bool hasUnknown = false;
    bool hasFalse = false;
    bool hasTrue = false;

    for (auto childIndex : children) {
        ConditionState childState = conditionCache[childIndex];
        if (childState == ConditionState::kUnknown) {
            hasUnknown = true;
            break;
        }
        if (childState == ConditionState::kFalse) {
            hasFalse = true;
        }
        if (childState == ConditionState::kTrue) {
            hasTrue = true;
        }
    }

    // If any child condition is in unknown state, the condition is unknown too.
    if (hasUnknown) {
        return ConditionState::kUnknown;
    }

    switch (operation) {
        case LogicalOperation::AND: {
            newCondition = hasFalse ? ConditionState::kFalse : ConditionState::kTrue;
            break;
        }
        case LogicalOperation::OR: {
            newCondition = hasTrue ? ConditionState::kTrue : ConditionState::kFalse;
            break;
        }
        case LogicalOperation::NOT:
            newCondition = (conditionCache[children[0]] == ConditionState::kFalse)
                                   ? ConditionState::kTrue
                                   : ConditionState::kFalse;
            break;
        case LogicalOperation::NAND:
            newCondition = hasFalse ? ConditionState::kTrue : ConditionState::kFalse;
            break;
        case LogicalOperation::NOR:
            newCondition = hasTrue ? ConditionState::kFalse : ConditionState::kTrue;
            break;
    }
    return newCondition;
}

HashableDimensionKey getDimensionKeyForCondition(const LogEvent& event,
                                                 const EventConditionLink& link) {
    vector<KeyMatcher> eventKey;
    eventKey.reserve(link.key_in_main().size());

    for (const auto& key : link.key_in_main()) {
        eventKey.push_back(key);
    }

    vector<KeyValuePair> dimensionKey = getDimensionKey(event, eventKey);

    for (int i = 0; i < link.key_in_main_size(); i++) {
        auto& kv = dimensionKey[i];
        kv.set_key(link.key_in_condition(i).key());
    }

    return getHashableKey(dimensionKey);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
