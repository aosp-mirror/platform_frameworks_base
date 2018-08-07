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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "StatsPullerManagerImpl.h"
#include "puller_util.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::shared_ptr;
using std::vector;

namespace {
bool shouldMerge(shared_ptr<LogEvent>& lhs, shared_ptr<LogEvent>& rhs,
                 const vector<int>& nonAdditiveFields) {
    const auto& l_values = lhs->getValues();
    const auto& r_values = rhs->getValues();

    for (size_t i : nonAdditiveFields) {
        // We store everything starting from index 0, so we need to use i-1
        if (!(l_values.size() > i - 1 && r_values.size() > i - 1 &&
              l_values[i - 1].mValue == r_values[i - 1].mValue)) {
            return false;
        }
    }
    return true;
}

// merge rhs to lhs
// when calling this function, all sanity check should be done already.
// e.g., index boundary, nonAdditiveFields matching etc.
bool mergeEvent(shared_ptr<LogEvent>& lhs, shared_ptr<LogEvent>& rhs,
                const vector<int>& additiveFields) {
    vector<FieldValue>* host_values = lhs->getMutableValues();
    const auto& child_values = rhs->getValues();
    for (int i : additiveFields) {
        Value& host = (*host_values)[i - 1].mValue;
        const Value& child = (child_values[i - 1]).mValue;
        if (child.getType() != host.getType()) {
            return false;
        }
        switch (child.getType()) {
            case INT:
                host.setInt(host.int_value + child.int_value);
                break;
            case LONG:
                host.setLong(host.long_value + child.long_value);
                break;
            default:
                ALOGE("Tried to merge 2 fields with unsupported type");
                return false;
        }
    }
    return true;
}

bool tryMerge(vector<shared_ptr<LogEvent>>& data, int child_pos, const vector<int>& host_pos,
              const vector<int>& nonAdditiveFields, const vector<int>& additiveFields) {
    for (const auto& pos : host_pos) {
        if (shouldMerge(data[pos], data[child_pos], nonAdditiveFields) &&
            mergeEvent(data[pos], data[child_pos], additiveFields)) {
            return true;
        }
    }
    return false;
}

}  // namespace

/**
 * Process all data and merge isolated with host if necessary.
 * For example:
 *   NetworkBytesAtom {
 *       int uid = 1;
 *       State process_state = 2;
 *       int byte_send = 3;
 *       int byte_recv = 4;
 *   }
 *   additive fields are {3, 4}, non-additive field is {2}
 * If we pulled the following events (uid1_child is an isolated uid which maps to uid1):
 * [uid1, fg, 100, 200]
 * [uid1_child, fg, 100, 200]
 * [uid1, bg, 100, 200]
 *
 * We want to merge them and results should be:
 * [uid1, fg, 200, 400]
 * [uid1, bg, 100, 200]
 */
void mergeIsolatedUidsToHostUid(vector<shared_ptr<LogEvent>>& data, const sp<UidMap>& uidMap,
                                int tagId) {
    if (StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId) ==
        StatsPullerManagerImpl::kAllPullAtomInfo.end()) {
        VLOG("Unknown pull atom id %d", tagId);
        return;
    }
    int uidField;
    auto it = android::util::AtomsInfo::kAtomsWithUidField.find(tagId);
    if (it == android::util::AtomsInfo::kAtomsWithUidField.end()) {
        VLOG("No uid to merge for atom %d", tagId);
        return;
    } else {
        uidField = it->second;  // uidField is the field number in proto,
    }
    const vector<int>& additiveFields =
            StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId)->second.additiveFields;
    const vector<int>& nonAdditiveFields =
            StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId)->second.nonAdditiveFields;

    // map of host uid to their position in the original vector
    map<int, vector<int>> hostPosition;
    vector<bool> toRemove = vector<bool>(data.size(), false);

    for (size_t i = 0; i < data.size(); i++) {
        vector<FieldValue>* valueList = data[i]->getMutableValues();

        int uid;
        if (uidField > 0 && (int)data[i]->getValues().size() >= uidField &&
            (data[i]->getValues())[uidField - 1].mValue.getType() == INT) {
            uid = (*data[i]->getMutableValues())[uidField - 1].mValue.int_value;
        } else {
            ALOGE("Malformed log, uid not found. %s", data[i]->ToString().c_str());
            continue;
        }

        const int hostUid = uidMap->getHostUidOrSelf(uid);

        if (hostUid != uid) {
            (*valueList)[0].mValue.setInt(hostUid);
        }
        if (hostPosition.find(hostUid) == hostPosition.end()) {
            hostPosition[hostUid].push_back(i);
        } else {
            if (tryMerge(data, i, hostPosition[hostUid], nonAdditiveFields, additiveFields)) {
                toRemove[i] = true;
            } else {
                hostPosition[hostUid].push_back(i);
            }
        }
    }

    vector<shared_ptr<LogEvent>> mergedData;
    for (size_t i = 0; i < toRemove.size(); i++) {
        if (!toRemove[i]) {
            mergedData.push_back(data[i]);
        }
    }
    data.clear();
    data = mergedData;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
