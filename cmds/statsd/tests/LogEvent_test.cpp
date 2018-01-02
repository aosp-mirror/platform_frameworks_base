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
#include "src/logd/LogEvent.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

TEST(LogEventTest, testEmptyEvent) {
    const int32_t TAG_ID = 123;
    LogEvent event(TAG_ID, 0);
    event.init();

    DimensionsValue dimensionsValue;
    EXPECT_FALSE(event.GetSimpleAtomDimensionsValueProto(234, &dimensionsValue));
    FieldMatcher dimensions;
    dimensions.set_field(event.GetTagId());
    EXPECT_FALSE(event.GetAtomDimensionsValueProto(dimensions, &dimensionsValue));

    dimensions.add_child()->set_field(3);
    dimensions.mutable_child(0)->set_position(Position::FIRST);
    EXPECT_FALSE(event.GetAtomDimensionsValueProto(dimensions, &dimensionsValue));

    dimensions.mutable_child(0)->set_position(Position::ANY);
    EXPECT_FALSE(event.GetAtomDimensionsValueProto(dimensions, &dimensionsValue));

    dimensions.mutable_child(0)->set_position(Position::LAST);
    EXPECT_FALSE(event.GetAtomDimensionsValueProto(dimensions, &dimensionsValue));
}

TEST(LogEventTest, testRepeatedAttributionNode) {
    const int32_t TAG_ID = 123;
    LogEvent event(TAG_ID, 0);
    AttributionNode attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("locationService");

    AttributionNode attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("locationService2");

    AttributionNode attribution_node3;
    attribution_node3.set_uid(3333);
    attribution_node3.set_tag("locationService3");
    std::vector<AttributionNode> attribution_nodes =
        {attribution_node1, attribution_node2, attribution_node3};

    // 1nd field: int32.
    EXPECT_TRUE(event.write(int32_t(11)));
    // 2rd field: float.
    EXPECT_TRUE(event.write(3.45f));
    // Here it assume that the atom proto contains a repeated AttributionNode field.
    // 3rd field: attribution node. This is repeated field.
    EXPECT_TRUE(event.write(attribution_nodes));
    // 4th field: bool.
    EXPECT_TRUE(event.write(true));
    // 5th field: long.
    EXPECT_TRUE(event.write(uint64_t(1234)));

    event.init();

    DimensionsValue dimensionsValue;
    // Query single primitive fields.
    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), 11);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_float(), 3.45f);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(4, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 4);
    // The bool value is stored in value_int field as logD does not support bool.
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), true);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(5, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 5);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_long(), long(1234));

    // First attribution.
    FieldMatcher first_uid_dimensions;
    first_uid_dimensions.set_field(event.GetTagId());
    first_uid_dimensions.add_child()->set_field(3);
    first_uid_dimensions.mutable_child(0)->set_position(Position::FIRST);
    first_uid_dimensions.mutable_child(0)->add_child()->set_field(1);
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(first_uid_dimensions, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);

    FieldMatcher first_tag_dimensions = first_uid_dimensions;
    first_tag_dimensions.mutable_child(0)->mutable_child(0)->set_field(2);
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(first_tag_dimensions, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_str(), "locationService");

    FieldMatcher first_attribution_dimensions = first_uid_dimensions;
    first_attribution_dimensions.mutable_child(0)->add_child()->set_field(2);
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(first_attribution_dimensions, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService");

    FieldMatcher last_attribution_dimensions = first_attribution_dimensions;
    last_attribution_dimensions.mutable_child(0)->set_position(Position::LAST);
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(last_attribution_dimensions, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 3333);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService3");

    FieldMatcher any_attribution_dimensions = first_attribution_dimensions;
    any_attribution_dimensions.mutable_child(0)->set_position(Position::ANY);
    std::vector<DimensionsValue> dimensionsValues;
    event.GetAtomDimensionsValueProtos(any_attribution_dimensions, &dimensionsValues);
    EXPECT_EQ(dimensionsValues.size(), 3u);
    EXPECT_EQ(dimensionsValues[0].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService");
    EXPECT_EQ(dimensionsValues[1].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 2222);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService2");
    EXPECT_EQ(dimensionsValues[2].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 3333);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService3");

    FieldMatcher mixed_dimensions = any_attribution_dimensions;
    mixed_dimensions.add_child()->set_field(1000);
    mixed_dimensions.add_child()->set_field(6); // missing field.
    mixed_dimensions.add_child()->set_field(3); // position not set.
    mixed_dimensions.add_child()->set_field(5);
    mixed_dimensions.add_child()->set_field(1);
    dimensionsValues.clear();
    event.GetAtomDimensionsValueProtos(mixed_dimensions, &dimensionsValues);
    EXPECT_EQ(dimensionsValues.size(), 3u);
    EXPECT_EQ(dimensionsValues[0].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value_size(), 3);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple().dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).value_int(),
              1111);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).value_str(),
              "locationService");
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(1).field(), 5);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(1).value_long(), long(1234));
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(2).field(), 1);
    EXPECT_EQ(dimensionsValues[0].value_tuple().dimensions_value(2).value_int(), 11);

    EXPECT_EQ(dimensionsValues[1].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value_size(), 3);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple().dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).value_int(),
              2222);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).value_str(),
              "locationService2");
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(1).field(), 5);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(1).value_long(), long(1234));
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(2).field(), 1);
    EXPECT_EQ(dimensionsValues[1].value_tuple().dimensions_value(2).value_int(), 11);

    EXPECT_EQ(dimensionsValues[2].field(), event.GetTagId());
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value_size(), 3);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple().dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple().dimensions_value(0).value_int(),
              3333);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(0).value_tuple().dimensions_value(1).value_str(),
              "locationService3");
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(1).field(), 5);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(1).value_long(), long(1234));
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(2).field(), 1);
    EXPECT_EQ(dimensionsValues[2].value_tuple().dimensions_value(2).value_int(), 11);

    FieldMatcher wrong_dimensions = mixed_dimensions;
    // Wrong tagId.
    wrong_dimensions.set_field(event.GetTagId() + 100);
    dimensionsValues.clear();
    event.GetAtomDimensionsValueProtos(wrong_dimensions, &dimensionsValues);
    EXPECT_TRUE(dimensionsValues.empty());
}

TEST(LogEventTest, testMessageField) {
    const int32_t TAG_ID = 123;
    LogEvent event(TAG_ID, 0);
    AttributionNode attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("locationService");

    AttributionNode attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("locationService2");

    // 1nd field: int32.
    EXPECT_TRUE(event.write(int32_t(11)));
    // 2rd field: float.
    EXPECT_TRUE(event.write(3.45f));
    // Here it assume that the atom proto contains two optional AttributionNode fields.
    // 3rd field: attribution node. This is not repeated field.
    EXPECT_TRUE(event.write(attribution_node1));
    // 4th field: another attribution field. This is not repeated field.
    EXPECT_TRUE(event.write(attribution_node2));
    // 5th field: bool.
    EXPECT_TRUE(event.write(true));
    // 6th field: long.
    EXPECT_TRUE(event.write(uint64_t(1234)));

    event.init();

    FieldMatcher uid_dimensions1;
    uid_dimensions1.set_field(event.GetTagId());
    uid_dimensions1.add_child()->set_field(3);
    uid_dimensions1.mutable_child(0)->add_child()->set_field(1);

    FieldMatcher tag_dimensions1;
    tag_dimensions1.set_field(event.GetTagId());
    tag_dimensions1.add_child()->set_field(3);
    tag_dimensions1.mutable_child(0)->add_child()->set_field(2);

    FieldMatcher attribution_dimensions1;
    attribution_dimensions1.set_field(event.GetTagId());
    attribution_dimensions1.add_child()->set_field(3);
    attribution_dimensions1.mutable_child(0)->add_child()->set_field(1);
    attribution_dimensions1.mutable_child(0)->add_child()->set_field(2);

    FieldMatcher uid_dimensions2 = uid_dimensions1;
    uid_dimensions2.mutable_child(0)->set_field(4);

    FieldMatcher tag_dimensions2 = tag_dimensions1;
    tag_dimensions2.mutable_child(0)->set_field(4);

    FieldMatcher attribution_dimensions2 = attribution_dimensions1;
    attribution_dimensions2.mutable_child(0)->set_field(4);

    FieldMatcher mixed_dimensions = attribution_dimensions1;
    mixed_dimensions.add_child()->set_field(4);
    mixed_dimensions.mutable_child(1)->add_child()->set_field(1);
    mixed_dimensions.add_child()->set_field(1000);
    mixed_dimensions.add_child()->set_field(5);
    mixed_dimensions.add_child()->set_field(1);

    DimensionsValue dimensionsValue;

    // Query single primitive fields.
    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), 11);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_float(), 3.45f);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(5, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 5);
    // The bool value is stored in value_int field as logD does not support bool.
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), true);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(6, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 6);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_long(), long(1234));

    // Query atom field 3: attribution node uid field only.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(uid_dimensions1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);

    // Query atom field 3: attribution node tag field only.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(tag_dimensions1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_str(), "locationService");

    // Query atom field 3: attribution node uid + tag fields.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(attribution_dimensions1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService");

    // Query atom field 4: attribution node uid field only.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(uid_dimensions2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 4);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 2222);

    // Query atom field 4: attribution node tag field only.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(tag_dimensions2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 4);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_str(), "locationService2");

    // Query atom field 4: attribution node uid + tag fields.
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(attribution_dimensions2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 4);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 2222);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService2");

    // Query multiple fields:
    // 1/ Field 3: attribution uid + tag.
    // 2/ Field 4: attribution uid only.
    // 3/ Field not exist.
    // 4/ Primitive fields #5
    // 5/ Primitive fields #1
    EXPECT_TRUE(event.GetAtomDimensionsValueProto(mixed_dimensions, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 4);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value_size(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(0).value_int(), 1111);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_tuple()
        .dimensions_value(1).value_str(), "locationService");
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(1).field(), 4);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(1).value_tuple()
        .dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(1).value_tuple()
        .dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(1).value_tuple()
        .dimensions_value(0).value_int(), 2222);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(2).field(), 5);
    // The bool value is stored in value_int field as logD does not support bool.
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(2).value_int(), true);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(3).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(3).value_int(), 11);
}

TEST(LogEventTest, testAllPrimitiveFields) {
    const int32_t TAG_ID = 123;
    LogEvent event(TAG_ID, 0);

    // 1nd field: int32.
    EXPECT_TRUE(event.write(int32_t(11)));
    // 2rd field: float.
    EXPECT_TRUE(event.write(3.45f));
    // 3th field: string.
    EXPECT_TRUE(event.write("test"));
    // 4th field: bool.
    EXPECT_TRUE(event.write(true));
    // 5th field: bool.
    EXPECT_TRUE(event.write(false));
    // 6th field: long.
    EXPECT_TRUE(event.write(uint64_t(1234)));

    event.init();

    DimensionsValue dimensionsValue;
    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(1, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), 11);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(2, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 2);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_float(), 3.45f);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(3, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 3);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_str(), "test");

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(4, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 4);
    // The bool value is stored in value_int field as logD does not support bool.
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), true);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(5, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 5);
    // The bool value is stored in value_int field as logD does not support bool.
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_int(), false);

    EXPECT_TRUE(event.GetSimpleAtomDimensionsValueProto(6, &dimensionsValue));
    EXPECT_EQ(dimensionsValue.field(), event.GetTagId());
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value_size(), 1);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).field(), 6);
    EXPECT_EQ(dimensionsValue.value_tuple().dimensions_value(0).value_long(), long(1234));

    // Field not exist.
    EXPECT_FALSE(event.GetSimpleAtomDimensionsValueProto(7, &dimensionsValue));
}

TEST(LogEventTest, testWriteAtomProtoToStream) {
    AttributionNode attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("locationService");

    AttributionNode attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("locationService2");

    AttributionNode attribution_node3;
    attribution_node3.set_uid(3333);
    attribution_node3.set_tag("locationService3");
    std::vector<AttributionNode> attribution_nodes =
        {attribution_node1, attribution_node2, attribution_node3};

    LogEvent event(1, 0);
    EXPECT_TRUE(event.write("222"));
    EXPECT_TRUE(event.write(attribution_nodes));
    EXPECT_TRUE(event.write(345));
    EXPECT_TRUE(event.write(attribution_node3));
    EXPECT_TRUE(event.write("hello"));
    event.init();

    util::ProtoOutputStream protoOutput;
    // For now only see whether it will crash.
    // TODO(yanglu): test parsing from stream.
    event.ToProto(protoOutput);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif