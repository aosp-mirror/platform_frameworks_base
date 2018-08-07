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
#include <gtest/gtest.h>
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/matcher_util.h"
#include "src/logd/LogEvent.h"
#include "stats_log_util.h"
#include "stats_util.h"
#include "subscriber/SubscriberReporter.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

TEST(AtomMatcherTest, TestFieldTranslation) {
    FieldMatcher matcher1;
    matcher1.set_field(10);
    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);
    child->set_position(Position::ANY);

    child = child->add_child();
    child->set_field(1);

    vector<Matcher> output;
    translateFieldMatcher(matcher1, &output);

    EXPECT_EQ((size_t)1, output.size());

    const auto& matcher12 = output[0];
    EXPECT_EQ((int32_t)10, matcher12.mMatcher.getTag());
    EXPECT_EQ((int32_t)0x02010001, matcher12.mMatcher.getField());
    EXPECT_EQ((int32_t)0xff7f007f, matcher12.mMask);
}

TEST(AtomMatcherTest, TestFieldTranslation_ALL) {
    FieldMatcher matcher1;
    matcher1.set_field(10);
    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);
    child->set_position(Position::ALL);

    child = child->add_child();
    child->set_field(1);

    vector<Matcher> output;
    translateFieldMatcher(matcher1, &output);

    EXPECT_EQ((size_t)1, output.size());

    const auto& matcher12 = output[0];
    EXPECT_EQ((int32_t)10, matcher12.mMatcher.getTag());
    EXPECT_EQ((int32_t)0x02010001, matcher12.mMatcher.getField());
    EXPECT_EQ((int32_t)0xff7f7f7f, matcher12.mMask);
}

TEST(AtomMatcherTest, TestFilter_ALL) {
    FieldMatcher matcher1;
    matcher1.set_field(10);
    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);
    child->set_position(Position::ALL);

    child->add_child()->set_field(1);
    child->add_child()->set_field(2);

    child = matcher1.add_child();
    child->set_field(2);

    vector<Matcher> matchers;
    translateFieldMatcher(matcher1, &matchers);

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
    LogEvent event(10, 12345);
    event.write(attribution_nodes);
    event.write("some value");
    // Convert to a LogEvent
    event.init();
    HashableDimensionKey output;

    filterValues(matchers, event.getValues(), &output);

    EXPECT_EQ((size_t)7, output.getValues().size());
    EXPECT_EQ((int32_t)0x02010101, output.getValues()[0].mField.getField());
    EXPECT_EQ((int32_t)1111, output.getValues()[0].mValue.int_value);
    EXPECT_EQ((int32_t)0x02010102, output.getValues()[1].mField.getField());
    EXPECT_EQ("location1", output.getValues()[1].mValue.str_value);

    EXPECT_EQ((int32_t)0x02010201, output.getValues()[2].mField.getField());
    EXPECT_EQ((int32_t)2222, output.getValues()[2].mValue.int_value);
    EXPECT_EQ((int32_t)0x02010202, output.getValues()[3].mField.getField());
    EXPECT_EQ("location2", output.getValues()[3].mValue.str_value);

    EXPECT_EQ((int32_t)0x02010301, output.getValues()[4].mField.getField());
    EXPECT_EQ((int32_t)3333, output.getValues()[4].mValue.int_value);
    EXPECT_EQ((int32_t)0x02010302, output.getValues()[5].mField.getField());
    EXPECT_EQ("location3", output.getValues()[5].mValue.str_value);

    EXPECT_EQ((int32_t)0x00020000, output.getValues()[6].mField.getField());
    EXPECT_EQ("some value", output.getValues()[6].mValue.str_value);
}

TEST(AtomMatcherTest, TestSubDimension) {
    HashableDimensionKey dim;

    int pos1[] = {1, 1, 1};
    int pos2[] = {1, 1, 2};
    int pos3[] = {1, 1, 3};
    int pos4[] = {2, 0, 0};
    Field field1(10, pos1, 2);
    Field field2(10, pos2, 2);

    Field field3(10, pos3, 2);
    Field field4(10, pos4, 0);

    Value value1((int32_t)10025);
    Value value2("tag");

    Value value11((int32_t)10026);
    Value value22("tag2");

    dim.addValue(FieldValue(field1, value1));
    dim.addValue(FieldValue(field2, value2));

    HashableDimensionKey subDim1;
    subDim1.addValue(FieldValue(field1, value1));

    HashableDimensionKey subDim2;
    subDim1.addValue(FieldValue(field2, value2));

    EXPECT_TRUE(dim.contains(dim));
    EXPECT_TRUE(dim.contains(subDim1));
    EXPECT_TRUE(dim.contains(subDim2));

    HashableDimensionKey subDim3;
    subDim3.addValue(FieldValue(field1, value11));
    EXPECT_FALSE(dim.contains(subDim3));

    HashableDimensionKey subDim4;
    // Empty dimension is always a sub dimension of other dimensions
    EXPECT_TRUE(dim.contains(subDim4));
}

TEST(AtomMatcherTest, TestMetric2ConditionLink) {
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
    LogEvent event(10, 12345);
    event.write(attribution_nodes);
    event.write("some value");
    // Convert to a LogEvent
    event.init();

    FieldMatcher whatMatcher;
    whatMatcher.set_field(10);
    FieldMatcher* child11 = whatMatcher.add_child();
    child11->set_field(1);
    child11->set_position(Position::ANY);
    child11 = child11->add_child();
    child11->set_field(1);

    FieldMatcher conditionMatcher;
    conditionMatcher.set_field(27);
    FieldMatcher* child2 = conditionMatcher.add_child();
    child2->set_field(2);
    child2->set_position(Position::LAST);

    child2 = child2->add_child();
    child2->set_field(2);

    Metric2Condition link;

    translateFieldMatcher(whatMatcher, &link.metricFields);
    translateFieldMatcher(conditionMatcher, &link.conditionFields);

    EXPECT_EQ((size_t)1, link.metricFields.size());
    EXPECT_EQ((int32_t)0x02010001, link.metricFields[0].mMatcher.getField());
    EXPECT_EQ((int32_t)0xff7f007f, link.metricFields[0].mMask);
    EXPECT_EQ((int32_t)10, link.metricFields[0].mMatcher.getTag());

    EXPECT_EQ((size_t)1, link.conditionFields.size());
    EXPECT_EQ((int32_t)0x02028002, link.conditionFields[0].mMatcher.getField());
    EXPECT_EQ((int32_t)0xff7f807f, link.conditionFields[0].mMask);
    EXPECT_EQ((int32_t)27, link.conditionFields[0].mMatcher.getTag());
}

TEST(AtomMatcherTest, TestWriteDimensionPath) {
    for (auto position : {Position::ANY, Position::ALL, Position::FIRST, Position::LAST}) {
        FieldMatcher matcher1;
        matcher1.set_field(10);
        FieldMatcher* child = matcher1.add_child();
        child->set_field(2);
        child->set_position(position);
        child->add_child()->set_field(1);
        child->add_child()->set_field(3);

        child = matcher1.add_child();
        child->set_field(4);

        child = matcher1.add_child();
        child->set_field(6);
        child->add_child()->set_field(2);

        vector<Matcher> matchers;
        translateFieldMatcher(matcher1, &matchers);

        android::util::ProtoOutputStream protoOut;
        writeDimensionPathToProto(matchers, &protoOut);

        vector<uint8_t> outData;
        outData.resize(protoOut.size());
        size_t pos = 0;
        auto iter = protoOut.data();
        while (iter.readBuffer() != NULL) {
            size_t toRead = iter.currentToRead();
            std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
            pos += toRead;
            iter.rp()->move(toRead);
        }

        DimensionsValue result;
        EXPECT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));

        EXPECT_EQ(10, result.field());
        EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, result.value_case());
        EXPECT_EQ(3, result.value_tuple().dimensions_value_size());

        const auto& dim1 = result.value_tuple().dimensions_value(0);
        EXPECT_EQ(2, dim1.field());
        EXPECT_EQ(2, dim1.value_tuple().dimensions_value_size());

        const auto& dim11 = dim1.value_tuple().dimensions_value(0);
        EXPECT_EQ(1, dim11.field());

        const auto& dim12 = dim1.value_tuple().dimensions_value(1);
        EXPECT_EQ(3, dim12.field());

        const auto& dim2 = result.value_tuple().dimensions_value(1);
        EXPECT_EQ(4, dim2.field());

        const auto& dim3 = result.value_tuple().dimensions_value(2);
        EXPECT_EQ(6, dim3.field());
        EXPECT_EQ(1, dim3.value_tuple().dimensions_value_size());
        const auto& dim31 = dim3.value_tuple().dimensions_value(0);
        EXPECT_EQ(2, dim31.field());
    }
}

TEST(AtomMatcherTest, TestSubscriberDimensionWrite) {
    HashableDimensionKey dim;

    int pos1[] = {1, 1, 1};
    int pos2[] = {1, 1, 2};
    int pos3[] = {1, 1, 3};
    int pos4[] = {2, 0, 0};

    Field field1(10, pos1, 2);
    Field field2(10, pos2, 2);
    Field field3(10, pos3, 2);
    Field field4(10, pos4, 0);

    Value value1((int32_t)10025);
    Value value2("tag");
    Value value3((int32_t)987654);
    Value value4((int32_t)99999);

    dim.addValue(FieldValue(field1, value1));
    dim.addValue(FieldValue(field2, value2));
    dim.addValue(FieldValue(field3, value3));
    dim.addValue(FieldValue(field4, value4));

    SubscriberReporter::getStatsDimensionsValue(dim);
    // TODO: can't test anything here because SubscriberReport class doesn't have any read api.
}

TEST(AtomMatcherTest, TestWriteDimensionToProto) {
    HashableDimensionKey dim;
    int pos1[] = {1, 1, 1};
    int pos2[] = {1, 1, 2};
    int pos3[] = {1, 1, 3};
    int pos4[] = {2, 0, 0};
    Field field1(10, pos1, 2);
    Field field2(10, pos2, 2);
    Field field3(10, pos3, 2);
    Field field4(10, pos4, 0);

    Value value1((int32_t)10025);
    Value value2("tag");
    Value value3((int32_t)987654);
    Value value4((int32_t)99999);

    dim.addValue(FieldValue(field1, value1));
    dim.addValue(FieldValue(field2, value2));
    dim.addValue(FieldValue(field3, value3));
    dim.addValue(FieldValue(field4, value4));

    android::util::ProtoOutputStream protoOut;
    writeDimensionToProto(dim, nullptr /* include strings */, &protoOut);

    vector<uint8_t> outData;
    outData.resize(protoOut.size());
    size_t pos = 0;
    auto iter = protoOut.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    DimensionsValue result;
    EXPECT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    EXPECT_EQ(10, result.field());
    EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, result.value_case());
    EXPECT_EQ(2, result.value_tuple().dimensions_value_size());

    const auto& dim1 = result.value_tuple().dimensions_value(0);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, dim1.value_case());
    EXPECT_EQ(3, dim1.value_tuple().dimensions_value_size());

    const auto& dim11 = dim1.value_tuple().dimensions_value(0);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueInt, dim11.value_case());
    EXPECT_EQ(10025, dim11.value_int());

    const auto& dim12 = dim1.value_tuple().dimensions_value(1);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueStr, dim12.value_case());
    EXPECT_EQ("tag", dim12.value_str());

    const auto& dim13 = dim1.value_tuple().dimensions_value(2);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueInt, dim13.value_case());
    EXPECT_EQ(987654, dim13.value_int());

    const auto& dim2 = result.value_tuple().dimensions_value(1);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueInt, dim2.value_case());
    EXPECT_EQ(99999, dim2.value_int());
}

TEST(AtomMatcherTest, TestWriteDimensionLeafNodesToProto) {
    HashableDimensionKey dim;
    int pos1[] = {1, 1, 1};
    int pos2[] = {1, 1, 2};
    int pos3[] = {1, 1, 3};
    int pos4[] = {2, 0, 0};
    Field field1(10, pos1, 2);
    Field field2(10, pos2, 2);
    Field field3(10, pos3, 2);
    Field field4(10, pos4, 0);

    Value value1((int32_t)10025);
    Value value2("tag");
    Value value3((int32_t)987654);
    Value value4((int64_t)99999);

    dim.addValue(FieldValue(field1, value1));
    dim.addValue(FieldValue(field2, value2));
    dim.addValue(FieldValue(field3, value3));
    dim.addValue(FieldValue(field4, value4));

    android::util::ProtoOutputStream protoOut;
    writeDimensionLeafNodesToProto(dim, 1, nullptr /* include strings */, &protoOut);

    vector<uint8_t> outData;
    outData.resize(protoOut.size());
    size_t pos = 0;
    auto iter = protoOut.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    DimensionsValueTuple result;
    EXPECT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    EXPECT_EQ(4, result.dimensions_value_size());

    const auto& dim1 = result.dimensions_value(0);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueInt, dim1.value_case());
    EXPECT_EQ(10025, dim1.value_int());

    const auto& dim2 = result.dimensions_value(1);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueStr, dim2.value_case());
    EXPECT_EQ("tag", dim2.value_str());

    const auto& dim3 = result.dimensions_value(2);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueInt, dim3.value_case());
    EXPECT_EQ(987654, dim3.value_int());

    const auto& dim4 = result.dimensions_value(3);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueLong, dim4.value_case());
    EXPECT_EQ(99999, dim4.value_long());
}

TEST(AtomMatcherTest, TestWriteAtomToProto) {
    AttributionNodeInternal attribution_node1;
    attribution_node1.set_uid(1111);
    attribution_node1.set_tag("location1");

    AttributionNodeInternal attribution_node2;
    attribution_node2.set_uid(2222);
    attribution_node2.set_tag("location2");

    std::vector<AttributionNodeInternal> attribution_nodes = {attribution_node1, attribution_node2};

    // Set up the event
    LogEvent event(4, 12345);
    event.write(attribution_nodes);
    event.write((int32_t)999);
    // Convert to a LogEvent
    event.init();

    android::util::ProtoOutputStream protoOutput;
    writeFieldValueTreeToStream(event.GetTagId(), event.getValues(), &protoOutput);

    vector<uint8_t> outData;
    outData.resize(protoOutput.size());
    size_t pos = 0;
    auto iter = protoOutput.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&(outData[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    Atom result;
    EXPECT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    EXPECT_EQ(Atom::PushedCase::kBleScanResultReceived, result.pushed_case());
    const auto& atom = result.ble_scan_result_received();
    EXPECT_EQ(2, atom.attribution_node_size());
    EXPECT_EQ(1111, atom.attribution_node(0).uid());
    EXPECT_EQ("location1", atom.attribution_node(0).tag());
    EXPECT_EQ(2222, atom.attribution_node(1).uid());
    EXPECT_EQ("location2", atom.attribution_node(1).tag());
    EXPECT_EQ(999, atom.num_results());
}


}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif