/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include "src/HashableDimensionKey.h"

#include <gtest/gtest.h>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "statsd_test_util.h"

#ifdef __ANDROID__

using android::util::ProtoReader;

namespace android {
namespace os {
namespace statsd {

/**
 * Test that #containsLinkedStateValues returns false when the whatKey is
 * smaller than the primaryKey.
 */
TEST(HashableDimensionKeyTest, TestContainsLinkedStateValues_WhatKeyTooSmall) {
    std::vector<Metric2State> mMetric2StateLinks;

    int32_t uid1 = 1000;
    HashableDimensionKey whatKey = DEFAULT_DIMENSION_KEY;
    HashableDimensionKey primaryKey;
    getUidProcessKey(uid1, &primaryKey);

    EXPECT_FALSE(containsLinkedStateValues(whatKey, primaryKey, mMetric2StateLinks,
                                           UID_PROCESS_STATE_ATOM_ID));
}

/**
 * Test that #containsLinkedStateValues returns false when the linked values
 * are not equal.
 */
TEST(HashableDimensionKeyTest, TestContainsLinkedStateValues_UnequalLinkedValues) {
    int stateAtomId = UID_PROCESS_STATE_ATOM_ID;

    FieldMatcher whatMatcher;
    whatMatcher.set_field(util::OVERLAY_STATE_CHANGED);
    FieldMatcher* child11 = whatMatcher.add_child();
    child11->set_field(1);

    FieldMatcher stateMatcher;
    stateMatcher.set_field(stateAtomId);
    FieldMatcher* child21 = stateMatcher.add_child();
    child21->set_field(1);

    std::vector<Metric2State> mMetric2StateLinks;
    Metric2State ms;
    ms.stateAtomId = stateAtomId;
    translateFieldMatcher(whatMatcher, &ms.metricFields);
    translateFieldMatcher(stateMatcher, &ms.stateFields);
    mMetric2StateLinks.push_back(ms);

    int32_t uid1 = 1000;
    int32_t uid2 = 1001;
    HashableDimensionKey whatKey;
    getOverlayKey(uid2, "package", &whatKey);
    HashableDimensionKey primaryKey;
    getUidProcessKey(uid1, &primaryKey);

    EXPECT_FALSE(containsLinkedStateValues(whatKey, primaryKey, mMetric2StateLinks, stateAtomId));
}

/**
 * Test that #containsLinkedStateValues returns false when there is no link
 * between the key values.
 */
TEST(HashableDimensionKeyTest, TestContainsLinkedStateValues_MissingMetric2StateLinks) {
    int stateAtomId = UID_PROCESS_STATE_ATOM_ID;

    std::vector<Metric2State> mMetric2StateLinks;

    int32_t uid1 = 1000;
    HashableDimensionKey whatKey;
    getOverlayKey(uid1, "package", &whatKey);
    HashableDimensionKey primaryKey;
    getUidProcessKey(uid1, &primaryKey);

    EXPECT_FALSE(containsLinkedStateValues(whatKey, primaryKey, mMetric2StateLinks, stateAtomId));
}

/**
 * Test that #containsLinkedStateValues returns true when the key values are
 * linked and equal.
 */
TEST(HashableDimensionKeyTest, TestContainsLinkedStateValues_AllConditionsMet) {
    int stateAtomId = UID_PROCESS_STATE_ATOM_ID;

    FieldMatcher whatMatcher;
    whatMatcher.set_field(util::OVERLAY_STATE_CHANGED);
    FieldMatcher* child11 = whatMatcher.add_child();
    child11->set_field(1);

    FieldMatcher stateMatcher;
    stateMatcher.set_field(stateAtomId);
    FieldMatcher* child21 = stateMatcher.add_child();
    child21->set_field(1);

    std::vector<Metric2State> mMetric2StateLinks;
    Metric2State ms;
    ms.stateAtomId = stateAtomId;
    translateFieldMatcher(whatMatcher, &ms.metricFields);
    translateFieldMatcher(stateMatcher, &ms.stateFields);
    mMetric2StateLinks.push_back(ms);

    int32_t uid1 = 1000;
    HashableDimensionKey whatKey;
    getOverlayKey(uid1, "package", &whatKey);
    HashableDimensionKey primaryKey;
    getUidProcessKey(uid1, &primaryKey);

    EXPECT_TRUE(containsLinkedStateValues(whatKey, primaryKey, mMetric2StateLinks, stateAtomId));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
