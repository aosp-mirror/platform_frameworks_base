// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#pragma once

#include "src/condition/ConditionWizard.h"
#include "src/external/StatsPullerManager.h"
#include "src/packages/UidMap.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

namespace android {
namespace os {
namespace statsd {

class MockConditionWizard : public ConditionWizard {
public:
    MOCK_METHOD3(query,
                 ConditionState(const int conditionIndex, const ConditionKey& conditionParameters,
                                const bool isPartialLink));
};

class MockStatsPullerManager : public StatsPullerManager {
public:
    MOCK_METHOD5(RegisterReceiver,
                 void(int tagId, const ConfigKey& key, wp<PullDataReceiver> receiver,
                      int64_t nextPulltimeNs, int64_t intervalNs));
    MOCK_METHOD3(UnRegisterReceiver,
                 void(int tagId, const ConfigKey& key, wp<PullDataReceiver> receiver));
    MOCK_METHOD5(Pull, bool(const int pullCode, const ConfigKey& key, const int64_t eventTimeNs,
                            vector<std::shared_ptr<LogEvent>>* data, bool useUids));
    MOCK_METHOD5(Pull,
                 bool(const int pullCode, const vector<int32_t>& uids, const int64_t eventTimeNs,
                      vector<std::shared_ptr<LogEvent>>* data, bool useUids));
    MOCK_METHOD2(RegisterPullUidProvider,
                 void(const ConfigKey& configKey, wp<PullUidProvider> provider));
    MOCK_METHOD2(UnregisterPullUidProvider,
                 void(const ConfigKey& configKey, wp<PullUidProvider> provider));
};

HashableDimensionKey getMockedDimensionKey(int tagId, int key, std::string value);
MetricDimensionKey getMockedMetricDimensionKey(int tagId, int key, std::string value);

HashableDimensionKey getMockedDimensionKeyLongValue(int tagId, int key, int64_t value);
MetricDimensionKey getMockedStateDimensionKey(int tagId, int key, int64_t value);

// Utils to build FieldMatcher proto for simple one-depth atoms.
void buildSimpleAtomFieldMatcher(const int tagId, const int atomFieldNum, FieldMatcher* matcher);
void buildSimpleAtomFieldMatcher(const int tagId, FieldMatcher* matcher);

}  // namespace statsd
}  // namespace os
}  // namespace android
