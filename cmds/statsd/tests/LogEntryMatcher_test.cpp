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

#include <gtest/gtest.h>
#include <log/log_event_list.h>
#include <log/log_read.h>
#include <log/logprint.h>
#include <stdio.h>

#include "annotations.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/matcher_util.h"
#include "stats_event.h"
#include "stats_log_util.h"
#include "stats_util.h"
#include "statsd_test_util.h"

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

namespace {

void makeIntLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                     const int32_t value) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);
    AStatsEvent_writeInt32(statsEvent, value);

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeFloatLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                       const float floatValue) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);
    AStatsEvent_writeFloat(statsEvent, floatValue);

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeStringLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                        const string& name) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);
    AStatsEvent_writeString(statsEvent, name.c_str());

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeIntWithBoolAnnotationLogEvent(LogEvent* logEvent, const int32_t atomId,
                                       const int32_t field, const uint8_t annotationId,
                                       const bool annotationValue) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_writeInt32(statsEvent, field);
    AStatsEvent_addBoolAnnotation(statsEvent, annotationId, annotationValue);

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeAttributionLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                             const vector<int>& attributionUids,
                             const vector<string>& attributionTags, const string& name) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);

    writeAttribution(statsEvent, attributionUids, attributionTags);
    AStatsEvent_writeString(statsEvent, name.c_str());

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeBoolLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                      const bool bool1, const bool bool2) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);

    AStatsEvent_writeBool(statsEvent, bool1);
    AStatsEvent_writeBool(statsEvent, bool2);

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

}  // anonymous namespace

TEST(AtomMatcherTest, TestSimpleMatcher) {
    UidMap uidMap;

    // Set up the matcher
    AtomMatcher matcher;
    auto simpleMatcher = matcher.mutable_simple_atom_matcher();
    simpleMatcher->set_atom_id(TAG_ID);

    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeIntLogEvent(&event, TAG_ID, 0, 11);

    // Test
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Wrong tag id.
    simpleMatcher->set_atom_id(TAG_ID + 1);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
}

TEST(AtomMatcherTest, TestAttributionMatcher) {
    UidMap uidMap;
    std::vector<int> attributionUids = {1111, 2222, 3333};
    std::vector<string> attributionTags = {"location1", "location2", "location3"};

    // Set up the log event.
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeAttributionLogEvent(&event, TAG_ID, 0, attributionUids, attributionTags, "some value");

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
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "tag");

    auto fieldMatcher = simpleMatcher->add_field_value_matcher();
    fieldMatcher->set_field(FIELD_ID_2);
    fieldMatcher->set_eq_string("some value");

    // Tag not matched.
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Match last node.
    attributionMatcher->set_position(Position::LAST);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    // Match any node.
    attributionMatcher->set_position(Position::ANY);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location4");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // Attribution match but primitive field not match.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "location2");
    fieldMatcher->set_eq_string("wrong value");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    fieldMatcher->set_eq_string("some value");

    // Uid match.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_field(
            ATTRIBUTION_UID_FIELD_ID);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
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
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::LAST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    // Uid + tag.
    attributionMatcher->set_position(Position::ANY);
    attributionMatcher->mutable_matches_tuple()->add_field_value_matcher()->set_field(
            ATTRIBUTION_TAG_FIELD_ID);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location2");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::FIRST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));

    attributionMatcher->set_position(Position::LAST);
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg0");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg1");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location2");
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg2");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location3");
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event));
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(0)->set_eq_string(
            "pkg3");
    attributionMatcher->mutable_matches_tuple()->mutable_field_value_matcher(1)->set_eq_string(
            "location1");
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

    // Make event without is_uid annotation.
    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeIntLogEvent(&event1, TAG_ID, 0, 1111);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event1));

    // Make event with is_uid annotation.
    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeIntWithBoolAnnotationLogEvent(&event2, TAG_ID_2, 1111, ANNOTATION_ID_IS_UID, true);

    // Event has is_uid annotation, so mapping from uid to package name occurs.
    simpleMatcher->set_atom_id(TAG_ID_2);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event2));

    // Event has is_uid annotation, but uid maps to different package name.
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

    std::vector<int> attributionUids = {1111, 2222, 3333, 1066};
    std::vector<string> attributionTags = {"location1", "location2", "location3", "location3"};

    // Set up the event
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeAttributionLogEvent(&event, TAG_ID, 0, attributionUids, attributionTags, "some value");

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

    std::vector<int> attributionUids = {1067, 2222, 3333, 1066};
    std::vector<string> attributionTags = {"location1", "location2", "location3", "location3"};

    // Set up the event
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeAttributionLogEvent(&event, TAG_ID, 0, attributionUids, attributionTags, "some value");

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
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeBoolLogEvent(&event, TAG_ID, 0, true, false);

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
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeStringLogEvent(&event, TAG_ID, 0, "some value");

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
    LogEvent event(/*uid=*/0, /*pid=*/0);
    CreateTwoValueLogEvent(&event, TAG_ID, 0, 2, 3);

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
    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeIntLogEvent(&event, TAG_ID, 0, 11);

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

    LogEvent event1(/*uid=*/0, /*pid=*/0);
    makeFloatLogEvent(&event1, TAG_ID, 0, 10.1f);
    keyValue->set_lt_float(10.0);
    EXPECT_FALSE(matchesSimple(uidMap, *simpleMatcher, event1));

    LogEvent event2(/*uid=*/0, /*pid=*/0);
    makeFloatLogEvent(&event2, TAG_ID, 0, 9.9f);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event2));

    LogEvent event3(/*uid=*/0, /*pid=*/0);
    makeFloatLogEvent(&event3, TAG_ID, 0, 10.1f);
    keyValue->set_gt_float(10.0);
    EXPECT_TRUE(matchesSimple(uidMap, *simpleMatcher, event3));

    LogEvent event4(/*uid=*/0, /*pid=*/0);
    makeFloatLogEvent(&event4, TAG_ID, 0, 9.9f);
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
