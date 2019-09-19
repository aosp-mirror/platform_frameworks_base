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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/matcher_util.h"
#include "stats_log_util.h"
#include "stats_util.h"

#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <log/logprint.h>

#include <stdio.h>

using namespace android::os::statsd;
using std::unordered_map;
using std::vector;

const int32_t TAG_ID = 123;
const int32_t TAG_ID_2 = 28;  // hardcoded tag of atom with uid field
const int FIELD_ID_1 = 1;
const int FIELD_ID_2 = 2;
const int FIELD_ID_3 = 2;

const int ATTRIBUTION_UID_FIELD_ID = 1;
const int ATTRIBUTION_TAG_FIELD_ID = 2;


#ifdef __ANDROID__
TEST(AtomMatcherTest, TestSimpleMatcher) {
    UidMap uidMap;

    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    LogEvent event(TAG_ID, 0);
    EXPECT_TRUE(event.write(11));
    event.init();

    // Test
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Wrong tag id.
    simpleMatcher->set_atom_id(TAG_ID + 1);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestAttributionMatcher) {
    UidMap uidMap;
    AttributionNodeInternal attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("location1");

    AttributionNodeInternal attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("location2");

    AttributionNodeInternal attribution_node3;
    attribution_node3.set_uid(3333);
    attribution_node3.set_tag("location3");
    std::vector<AttributionNodeInternal> attribution_nodes = {attribution_node1, attribution_node2,
                                                              attribution_node3};

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(attribution_nodes);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    // Match first node.
    auto attributionMatcher = simpleMatcher->add_field_value_matcher();
    attributionMatcher->set_field(FIELD_ID_1);
    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->add_field_value_matcher()->set_field(
        ATTRIBUTION_TAG_FIELD_ID);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string("tag");

    auto fieldMatcher = simpleMatcher->add_field_value_matcher();
    fieldMatcher->set_field(FIELD_ID_2);
    fieldMatcher->set_eq_string("some value");

    // Tag not matched.
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Match last node.
    attributionMatcher->set_position(Position::LAST);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Match any node.
    attributionMatcher->set_position(Position::ANY);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location4");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // Attribution match but primitive field not match.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("location2");
    fieldMatcher->set_eq_string("wrong value");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    fieldMatcher->set_eq_string("some value");

    // Uid match.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_field(
        ATTRIBUTION_UID_FIELD_ID);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string("pkg0");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    uidMap.updateMap(
            1, {1111, 1111, 2222, 3333, 3333} /* uid list */, {1, 1, 2, 1, 2} /* version list */,
            {android::String16("v1"), android::String16("v1"), android::String16("v2"),
             android::String16("v1"), android::String16("v2")},
            {android::String16("pkg0"), android::String16("pkg1"), android::String16("pkg1"),
             android::String16("Pkg2"), android::String16("PkG3")} /* package name list */,
            {android::String16(""), android::String16(""), android::String16(""),
             android::String16(""), android::String16("")});

    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::LAST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // Uid + tag.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->add_field_value_matcher()->set_field(
        ATTRIBUTION_TAG_FIELD_ID);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::LAST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)
        ->set_eq_string("pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)
        ->set_eq_string("location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestUidFieldMatcher) {
    UidMap uidMap;
    uidMap.updateMap(
        1, {1111, 1111, 2222, 3333, 3333} /* uid list */, {1, 1, 2, 1, 2} /* version list */,
        {android::String16("v1"), android::String16("v1"), android::String16("v2"),
         android::String16("v1"), android::String16("v2")},
        {android::String16("pkg0"), android::String16("pkg1"), android::String16("pkg1"),
         android::String16("Pkg2"), android::String16("PkG3")} /* package name list */,
        {android::String16(""), android::String16(""), android::String16(""),
         android::String16(""), android::String16("")});

    // Set up matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);
    simpleMatcher->add_field_value_matcher()->set_field(1);
    simpleMatcher->mutable_field_value_matcher(0)->set_eq_string("pkg0");

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(1111);
    event.init();

    LogEvent event2(TAG_ID_2, 0);
    event2.write(1111);
    event2.write("some value");
    event2.init();

    // Tag not in kAtomsWithUidField
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // Tag found in kAtomsWithUidField and has matching uid
    simpleMatcher->set_atom_id(TAG_ID_2);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event2));

    // Tag found in kAtomsWithUidField but has non-matching uid
    simpleMatcher->mutable_field_value_matcher(0)->set_eq_string("Pkg2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event2));
}

TEST(AtomMatcherTest, TestNeqAnyStringMatcher) {
    UidMap uidMap;
    uidMap.updateMap(
            1, {1111, 1111, 2222, 3333, 3333} /* uid list */, {1, 1, 2, 1, 2} /* version list */,
            {android::String16("v1"), android::String16("v1"), android::String16("v2"),
             android::String16("v1"), android::String16("v2")},
            {android::String16("pkg0"), android::String16("pkg1"), android::String16("pkg1"),
             android::String16("Pkg2"), android::String16("PkG3")} /* package name list */,
            {android::String16(""), android::String16(""), android::String16(""),
             android::String16(""), android::String16("")});

    AttributionNodeInternal attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("location1");

    AttributionNodeInternal attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("location2");

    AttributionNodeInternal attribution_node3;
    attribution_node3.set_uid(3333);
    attribution_node3.set_tag("location3");

    AttributionNodeInternal attribution_node4;
    attribution_node4.set_uid(1066);
    attribution_node4.set_tag("location3");
    std::vector<AttributionNodeInternal> attribution_nodes = {attribution_node1, attribution_node2,
                                                              attribution_node3, attribution_node4};

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(attribution_nodes);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    // Match first node.
    auto attributionMatcher = simpleMatcher->add_field_value_matcher();
    attributionMatcher->set_field(FIELD_ID_1);
    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->add_field_value_matcher()->set_field(
            ATTRIBUTION_UID_FIELD_ID);
    auto neqStringList = attributionMatcher->mutable_matches_tuple()
                                 ->mutable_field_value_matcher(0)
                                 ->mutable_neq_any_string();
    neqStringList->add_str_value("pkg2");
    neqStringList->add_str_value("pkg3");

    auto fieldMatcher = simpleMatcher->add_field_value_matcher();
    fieldMatcher->set_field(FIELD_ID_2);
    fieldMatcher->set_eq_string("some value");

    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    neqStringList->Clear();
    neqStringList->add_str_value("pkg1");
    neqStringList->add_str_value("pkg3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::ANY);
    neqStringList->Clear();
    neqStringList->add_str_value("maps.com");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    neqStringList->Clear();
    neqStringList->add_str_value("PkG3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::LAST);
    neqStringList->Clear();
    neqStringList->add_str_value("AID_STATSD");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestEqAnyStringMatcher) {
    UidMap uidMap;
    uidMap.updateMap(
            1, {1111, 1111, 2222, 3333, 3333} /* uid list */, {1, 1, 2, 1, 2} /* version list */,
            {android::String16("v1"), android::String16("v1"), android::String16("v2"),
             android::String16("v1"), android::String16("v2")},
            {android::String16("pkg0"), android::String16("pkg1"), android::String16("pkg1"),
             android::String16("Pkg2"), android::String16("PkG3")} /* package name list */,
            {android::String16(""), android::String16(""), android::String16(""),
             android::String16(""), android::String16("")});

    AttributionNodeInternal attribution_node1;
    attribution_node1.set_uid(1067);
    attribution_node1.set_tag("location1");

    AttributionNodeInternal attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("location2");

    AttributionNodeInternal attribution_node3;
    attribution_node3.set_uid(3333);
    attribution_node3.set_tag("location3");

    AttributionNodeInternal attribution_node4;
    attribution_node4.set_uid(1066);
    attribution_node4.set_tag("location3");
    std::vector<AttributionNodeInternal> attribution_nodes = {attribution_node1, attribution_node2,
                                                              attribution_node3, attribution_node4};

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(attribution_nodes);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    // Match first node.
    auto attributionMatcher = simpleMatcher->add_field_value_matcher();
    attributionMatcher->set_field(FIELD_ID_1);
    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->add_field_value_matcher()->set_field(
            ATTRIBUTION_UID_FIELD_ID);
    auto eqStringList = attributionMatcher->mutable_matches_tuple()
                                ->mutable_field_value_matcher(0)
                                ->mutable_eq_any_string();
    eqStringList->add_str_value("AID_ROOT");
    eqStringList->add_str_value("AID_INCIDENTD");

    auto fieldMatcher = simpleMatcher->add_field_value_matcher();
    fieldMatcher->set_field(FIELD_ID_2);
    fieldMatcher->set_eq_string("some value");

    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::ANY);
    eqStringList->Clear();
    eqStringList->add_str_value("AID_STATSD");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    eqStringList->Clear();
    eqStringList->add_str_value("pkg1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    auto normalStringField = fieldMatcher->mutable_eq_any_string();
    normalStringField->add_str_value("some value123");
    normalStringField->add_str_value("some value");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    normalStringField->Clear();
    normalStringField->add_str_value("AID_STATSD");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    eqStringList->Clear();
    eqStringList->add_str_value("maps.com");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestBoolMatcher) {
    UidMap uidMap;
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);
    auto keyValue1 = simpleMatcher->add_field_value_matcher();
    keyValue1->set_field(FIELD_ID_1);
    auto keyValue2 = simpleMatcher->add_field_value_matcher();
    keyValue2->set_field(FIELD_ID_2);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    EXPECT_TRUE(event.write(true));
    EXPECT_TRUE(event.write(false));
    // Convert to a LogEvent
    event.init();

    // Test
    keyValue1->set_eq_bool(true);
    keyValue2->set_eq_bool(false);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    keyValue1->set_eq_bool(false);
    keyValue2->set_eq_bool(false);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    keyValue1->set_eq_bool(false);
    keyValue2->set_eq_bool(true);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    keyValue1->set_eq_bool(true);
    keyValue2->set_eq_bool(true);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestStringMatcher) {
    UidMap uidMap;
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);
    auto keyValue = simpleMatcher->add_field_value_matcher();
    keyValue->set_field(FIELD_ID_1);
    keyValue->set_eq_string("some value");

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    // Test
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestMultiFieldsMatcher) {
    UidMap uidMap;
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);
    auto keyValue1 = simpleMatcher->add_field_value_matcher();
    keyValue1->set_field(FIELD_ID_1);
    auto keyValue2 = simpleMatcher->add_field_value_matcher();
    keyValue2->set_field(FIELD_ID_2);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(2);
    event.write(3);

    // Convert to a LogEvent
    event.init();

    // Test
    keyValue1->set_eq_int(2);
    keyValue2->set_eq_int(3);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    keyValue1->set_eq_int(2);
    keyValue2->set_eq_int(4);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    keyValue1->set_eq_int(4);
    keyValue2->set_eq_int(3);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestIntComparisonMatcher) {
    UidMap uidMap;
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();

    simpleMatcher->set_atom_id(TAG_ID);
    auto keyValue = simpleMatcher->add_field_value_matcher();
    keyValue->set_field(FIELD_ID_1);

    // Set up the event
    LogEvent event(TAG_ID, 0);
    event.write(11);
    event.init();

    // Test

    // eq_int
    keyValue->set_eq_int(10);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_eq_int(11);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_eq_int(12);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // lt_int
    keyValue->set_lt_int(10);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_lt_int(11);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_lt_int(12);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // lte_int
    keyValue->set_lte_int(10);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_lte_int(11);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_lte_int(12);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // gt_int
    keyValue->set_gt_int(10);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_gt_int(11);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_gt_int(12);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // gte_int
    keyValue->set_gte_int(10);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_gte_int(11);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    keyValue->set_gte_int(12);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestFloatComparisonMatcher) {
    UidMap uidMap;
    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    auto keyValue = simpleMatcher->add_field_value_matcher();
    keyValue->set_field(FIELD_ID_1);

    LogEvent event1(TAG_ID, 0);
    keyValue->set_lt_float(10.0);
    event1.write(10.1f);
    event1.init();
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event1));

    LogEvent event2(TAG_ID, 0);
    event2.write(9.9f);
    event2.init();
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event2));

    LogEvent event3(TAG_ID, 0);
    event3.write(10.1f);
    event3.init();
    keyValue->set_gt_float(10.0);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event3));

    LogEvent event4(TAG_ID, 0);
    event4.write(9.9f);
    event4.init();
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event4));
}

// Helper for the composite matchers.
void addSimpleMatcher(SimpleAtomMatcher* simpleMatcher, int tag, int key, int val) {
    simpleMatcher->set_atom_id(tag);
    auto keyValue = simpleMatcher->add_field_value_matcher();
    keyValue->set_field(key);
    keyValue->set_eq_int(val);
}

TEST(AtomMatcherTest, TestAndMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::AND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));
}

TEST(AtomMatcherTest, TestOrMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::OR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);
    children.push_back(2);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}

TEST(AtomMatcherTest, TestNotMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOT;

    vector<int> children;
    children.push_back(0);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));
}

TEST(AtomMatcherTest, TestNandMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NAND;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}

TEST(AtomMatcherTest, TestNorMatcher) {
    // Set up the matcher
    LogicalOperation operation = LogicalOperation::NOR;

    vector<int> children;
    children.push_back(0);
    children.push_back(1);

    vector<MatchingState> matcherResults;
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kNotMatched);

    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kNotMatched);
    matcherResults.push_back(MatchingState::kNotMatched);
    EXPECT_TRUE(combinationMatch(children, operation, matcherResults));

    matcherResults.clear();
    matcherResults.push_back(MatchingState::kMatched);
    matcherResults.push_back(MatchingState::kMatched);
    EXPECT_FALSE(combinationMatch(children, operation, matcherResults));
}
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
