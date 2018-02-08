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
#include "field_util.h"
#include "puller_util.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::shared_ptr;
using std::vector;

DimensionsValue* getFieldValue(shared_ptr<LogEvent> event, int tagId, int fieldNum) {
  Field field;
  buildSimpleAtomField(tagId, fieldNum, &field);
  return event->findFieldValueOrNull(field);
}

bool shouldMerge(shared_ptr<LogEvent>& lhs, shared_ptr<LogEvent>& rhs,
                 const vector<int>& nonAdditiveFields, int tagId) {
  for (int f : nonAdditiveFields) {
    DimensionsValue* lValue = getFieldValue(lhs, tagId, f);
    DimensionsValue* rValue = getFieldValue(rhs, tagId, f);
    if (!compareDimensionsValue(*lValue, *rValue)) {
      return false;
    }
  }
  return true;
}

// merge rhs to lhs
void mergeEvent(shared_ptr<LogEvent>& lhs, shared_ptr<LogEvent>& rhs,
                const vector<int>& additiveFields, int tagId) {
  for (int f : additiveFields) {
    DimensionsValue* lValue = getFieldValue(lhs, tagId, f);
    DimensionsValue* rValue = getFieldValue(rhs, tagId, f);
    if (lValue->has_value_int()) {
      lValue->set_value_int(lValue->value_int() + rValue->value_int());
    } else if (lValue->has_value_long()) {
      lValue->set_value_long(lValue->value_long() + rValue->value_long());
    }
  }
}

// process all data and merge isolated with host if necessary
void mergeIsolatedUidsToHostUid(vector<shared_ptr<LogEvent>>& data,
                                const sp<UidMap>& uidMap, int tagId) {
  if (StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId) ==
      StatsPullerManagerImpl::kAllPullAtomInfo.end()) {
    VLOG("Unknown pull atom id %d", tagId);
    return;
  }
  if (android::util::kAtomsWithUidField.find(tagId) ==
      android::util::kAtomsWithUidField.end()) {
    VLOG("No uid to merge for atom %d", tagId);
    return;
  }
  const vector<int>& additiveFields =
      StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId)
          ->second.additiveFields;
  const vector<int>& nonAdditiveFields =
      StatsPullerManagerImpl::kAllPullAtomInfo.find(tagId)
          ->second.nonAdditiveFields;

  // map of host uid to isolated uid data index in the original vector.
  // because of non additive fields, there could be multiple of them that can't
  // be merged into one
  map<int, vector<int>> hostToIsolated;
  // map of host uid to their position in the original vector
  map<int, vector<int>> hostPosition;
  vector<int> isolatedUidPos;
  // all uids in the original vector
  vector<int> allUids;
  for (size_t i = 0; i < data.size(); i++) {
    // uid field is always first primitive filed, if present
    DimensionsValue* uidField = getFieldValue(data[i], tagId, 1);
    if (!uidField) {
      VLOG("Bad data for %d, %s", tagId, data[i]->ToString().c_str());
      return;
    }
    int uid = uidField->value_int();
    allUids.push_back(uid);
    const int hostUid = uidMap->getHostUidOrSelf(uid);
    if (hostUid != uid) {
      uidField->set_value_int(hostUid);
      hostToIsolated[hostUid].push_back(i);
      isolatedUidPos.push_back(i);
    }
  }
  vector<shared_ptr<LogEvent>> mergedData;
  for (size_t i = 0; i < allUids.size(); i++) {
    if (hostToIsolated.find(allUids[i]) != hostToIsolated.end()) {
      hostPosition[allUids[i]].push_back(i);
    } else if (std::find(isolatedUidPos.begin(), isolatedUidPos.end(), i) != isolatedUidPos.end()) {
      continue;
    } else {
      mergedData.push_back(data[i]);
    }
  }
  for (auto iter = hostToIsolated.begin(); iter != hostToIsolated.end();
       iter++) {
    int uid = iter->first;
    vector<int>& isolated = hostToIsolated[uid];
    vector<int> toBeMerged;
    toBeMerged.insert(toBeMerged.begin(), isolated.begin(), isolated.end());
    if (hostPosition.find(uid) != hostPosition.end()) {
      vector<int>& host = hostPosition[uid];
      toBeMerged.insert(toBeMerged.end(), host.begin(), host.end());
    }
    vector<bool> used(toBeMerged.size());
    for (size_t i = 0; i < toBeMerged.size(); i++) {
      if (used[i] == true) {
        continue;
      }
      for (size_t j = i + 1; j < toBeMerged.size(); j++) {
        shared_ptr<LogEvent>& lhs = data[toBeMerged[i]];
        shared_ptr<LogEvent>& rhs = data[toBeMerged[j]];
        if (shouldMerge(lhs, rhs, nonAdditiveFields, tagId)) {
          mergeEvent(lhs, rhs, additiveFields, tagId);
          used[j] = true;
        }
      }
    }
    for (size_t i = 0; i < toBeMerged.size(); i++) {
      if (used[i] == false) {
      mergedData.push_back(data[i]);
    }
    }
  }
  data.clear();
  data = mergedData;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
