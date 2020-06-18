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
#include "stats_event.h"
#include "stats_log_util.h"
#include "stats_util.h"
#include "subscriber/SubscriberReporter.h"
#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

using android::util::ProtoReader;

namespace android {
namespace os {
namespace statsd {

// These constants must be kept in sync with those in StatsDimensionsValue.java.
const static int STATS_DIMENSIONS_VALUE_STRING_TYPE = 2;
const static int STATS_DIMENSIONS_VALUE_INT_TYPE = 3;
const static int STATS_DIMENSIONS_VALUE_FLOAT_TYPE = 6;
const static int STATS_DIMENSIONS_VALUE_TUPLE_TYPE = 7;

namespace {
void makeLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                  const vector<int>& attributionUids, const vector<string>& attributionTags,
                  const string& name) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);

    writeAttribution(statsEvent, attributionUids, attributionTags);
    AStatsEvent_writeString(statsEvent, name.c_str());

    parseStatsEventToLogEvent(statsEvent, logEvent);
}

void makeLogEvent(LogEvent* logEvent, const int32_t atomId, const int64_t timestamp,
                  const vector<int>& attributionUids, const vector<string>& attributionTags,
                  const int32_t value) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, atomId);
    AStatsEvent_overwriteTimestamp(statsEvent, timestamp);

    writeAttribution(statsEvent, attributionUids, attributionTags);
    AStatsEvent_writeInt32(statsEvent, value);

    parseStatsEventToLogEvent(statsEvent, logEvent);
}
}  // anonymous namespace

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

    ASSERT_EQ((size_t)1, output.size());

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

    ASSERT_EQ((size_t)1, output.size());

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

    std::vector<int> attributionUids = {1111, 2222, 3333};
    std::vector<string> attributionTags = {"location1", "location2", "location3"};

    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event, 10 /*atomId*/, 1012345, attributionUids, attributionTags, "some value");
    HashableDimensionKey output;

    filterValues(matchers, event.getValues(), &output);

    ASSERT_EQ((size_t)7, output.getValues().size());
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
    std::vector<int> attributionUids = {1111, 2222, 3333};
    std::vector<string> attributionTags = {"location1", "location2", "location3"};

    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event, 10 /*atomId*/, 12345, attributionUids, attributionTags, "some value");

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

    ASSERT_EQ((size_t)1, link.metricFields.size());
    EXPECT_EQ((int32_t)0x02010001, link.metricFields[0].mMatcher.getField());
    EXPECT_EQ((int32_t)0xff7f007f, link.metricFields[0].mMask);
    EXPECT_EQ((int32_t)10, link.metricFields[0].mMatcher.getTag());

    ASSERT_EQ((size_t)1, link.conditionFields.size());
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
        sp<ProtoReader> reader = protoOut.data();
        while (reader->readBuffer() != NULL) {
            size_t toRead = reader->currentToRead();
            std::memcpy(&(outData[pos]), reader->readBuffer(), toRead);
            pos += toRead;
            reader->move(toRead);
        }

        DimensionsValue result;
        ASSERT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));

        EXPECT_EQ(10, result.field());
        EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, result.value_case());
        ASSERT_EQ(3, result.value_tuple().dimensions_value_size());

        const auto& dim1 = result.value_tuple().dimensions_value(0);
        EXPECT_EQ(2, dim1.field());
        ASSERT_EQ(2, dim1.value_tuple().dimensions_value_size());

        const auto& dim11 = dim1.value_tuple().dimensions_value(0);
        EXPECT_EQ(1, dim11.field());

        const auto& dim12 = dim1.value_tuple().dimensions_value(1);
        EXPECT_EQ(3, dim12.field());

        const auto& dim2 = result.value_tuple().dimensions_value(1);
        EXPECT_EQ(4, dim2.field());

        const auto& dim3 = result.value_tuple().dimensions_value(2);
        EXPECT_EQ(6, dim3.field());
        ASSERT_EQ(1, dim3.value_tuple().dimensions_value_size());
        const auto& dim31 = dim3.value_tuple().dimensions_value(0);
        EXPECT_EQ(2, dim31.field());
    }
}

void checkAttributionNodeInDimensionsValueParcel(StatsDimensionsValueParcel& attributionNodeParcel,
                                                 int32_t nodeDepthInAttributionChain,
                                                 int32_t uid, string tag) {
    EXPECT_EQ(attributionNodeParcel.field, nodeDepthInAttributionChain /*position at depth 1*/);
    ASSERT_EQ(attributionNodeParcel.valueType, STATS_DIMENSIONS_VALUE_TUPLE_TYPE);
    ASSERT_EQ(attributionNodeParcel.tupleValue.size(), 2);

    StatsDimensionsValueParcel uidParcel = attributionNodeParcel.tupleValue[0];
    EXPECT_EQ(uidParcel.field, 1 /*position at depth 2*/);
    EXPECT_EQ(uidParcel.valueType, STATS_DIMENSIONS_VALUE_INT_TYPE);
    EXPECT_EQ(uidParcel.intValue, uid);

    StatsDimensionsValueParcel tagParcel = attributionNodeParcel.tupleValue[1];
    EXPECT_EQ(tagParcel.field, 2 /*position at depth 2*/);
    EXPECT_EQ(tagParcel.valueType, STATS_DIMENSIONS_VALUE_STRING_TYPE);
    EXPECT_EQ(tagParcel.stringValue, tag);
}

// Test conversion of a HashableDimensionKey into a StatsDimensionValueParcel
TEST(AtomMatcherTest, TestSubscriberDimensionWrite) {
    int atomId = 10;
    // First four fields form an attribution chain
    int pos1[] = {1, 1, 1};
    int pos2[] = {1, 1, 2};
    int pos3[] = {1, 2, 1};
    int pos4[] = {1, 2, 2};
    int pos5[] = {2, 1, 1};

    Field field1(atomId, pos1, /*depth=*/2);
    Field field2(atomId, pos2, /*depth=*/2);
    Field field3(atomId, pos3, /*depth=*/2);
    Field field4(atomId, pos4, /*depth=*/2);
    Field field5(atomId, pos5, /*depth=*/0);

    Value value1((int32_t)1);
    Value value2("string2");
    Value value3((int32_t)3);
    Value value4("string4");
    Value value5((float)5.0);

    HashableDimensionKey dimensionKey;
    dimensionKey.addValue(FieldValue(field1, value1));
    dimensionKey.addValue(FieldValue(field2, value2));
    dimensionKey.addValue(FieldValue(field3, value3));
    dimensionKey.addValue(FieldValue(field4, value4));
    dimensionKey.addValue(FieldValue(field5, value5));

    StatsDimensionsValueParcel rootParcel = dimensionKey.toStatsDimensionsValueParcel();
    EXPECT_EQ(rootParcel.field, atomId);
    ASSERT_EQ(rootParcel.valueType, STATS_DIMENSIONS_VALUE_TUPLE_TYPE);
    ASSERT_EQ(rootParcel.tupleValue.size(), 2);

    // Check that attribution chain is populated correctly
    StatsDimensionsValueParcel attributionChainParcel = rootParcel.tupleValue[0];
    EXPECT_EQ(attributionChainParcel.field, 1 /*position at depth 0*/);
    ASSERT_EQ(attributionChainParcel.valueType, STATS_DIMENSIONS_VALUE_TUPLE_TYPE);
    ASSERT_EQ(attributionChainParcel.tupleValue.size(), 2);
    checkAttributionNodeInDimensionsValueParcel(attributionChainParcel.tupleValue[0],
                                                /*nodeDepthInAttributionChain=*/1,
                                                value1.int_value, value2.str_value);
    checkAttributionNodeInDimensionsValueParcel(attributionChainParcel.tupleValue[1],
                                                /*nodeDepthInAttributionChain=*/2,
                                                value3.int_value, value4.str_value);

    // Check that the float is populated correctly
    StatsDimensionsValueParcel floatParcel = rootParcel.tupleValue[1];
    EXPECT_EQ(floatParcel.field, 2 /*position at depth 0*/);
    EXPECT_EQ(floatParcel.valueType, STATS_DIMENSIONS_VALUE_FLOAT_TYPE);
    EXPECT_EQ(floatParcel.floatValue, value5.float_value);
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
    sp<ProtoReader> reader = protoOut.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&(outData[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }

    DimensionsValue result;
    ASSERT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    EXPECT_EQ(10, result.field());
    EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, result.value_case());
    ASSERT_EQ(2, result.value_tuple().dimensions_value_size());

    const auto& dim1 = result.value_tuple().dimensions_value(0);
    EXPECT_EQ(DimensionsValue::ValueCase::kValueTuple, dim1.value_case());
    ASSERT_EQ(3, dim1.value_tuple().dimensions_value_size());

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
    sp<ProtoReader> reader = protoOut.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&(outData[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }

    DimensionsValueTuple result;
    ASSERT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    ASSERT_EQ(4, result.dimensions_value_size());

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
    std::vector<int> attributionUids = {1111, 2222};
    std::vector<string> attributionTags = {"location1", "location2"};

    LogEvent event(/*uid=*/0, /*pid=*/0);
    makeLogEvent(&event, 4 /*atomId*/, 12345, attributionUids, attributionTags, 999);

    android::util::ProtoOutputStream protoOutput;
    writeFieldValueTreeToStream(event.GetTagId(), event.getValues(), &protoOutput);

    vector<uint8_t> outData;
    outData.resize(protoOutput.size());
    size_t pos = 0;
    sp<ProtoReader> reader = protoOutput.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&(outData[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }

    Atom result;
    ASSERT_EQ(true, result.ParseFromArray(&outData[0], outData.size()));
    EXPECT_EQ(Atom::PushedCase::kBleScanResultReceived, result.pushed_case());
    const auto& atom = result.ble_scan_result_received();
    ASSERT_EQ(2, atom.attribution_node_size());
    EXPECT_EQ(1111, atom.attribution_node(0).uid());
    EXPECT_EQ("location1", atom.attribution_node(0).tag());
    EXPECT_EQ(2222, atom.attribution_node(1).uid());
    EXPECT_EQ("location2", atom.attribution_node(1).tag());
    EXPECT_EQ(999, atom.num_results());
}

/*
 * Test two Matchers is not a subset of one Matcher.
 * Test one Matcher is subset of two Matchers.
 */
TEST(AtomMatcherTest, TestSubsetDimensions1) {
    // Initialize first set of matchers
    FieldMatcher matcher1;
    matcher1.set_field(10);

    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);
    child->set_position(Position::ALL);
    child->add_child()->set_field(1);
    child->add_child()->set_field(2);

    vector<Matcher> matchers1;
    translateFieldMatcher(matcher1, &matchers1);
    ASSERT_EQ(2, matchers1.size());

    // Initialize second set of matchers
    FieldMatcher matcher2;
    matcher2.set_field(10);

    child = matcher2.add_child();
    child->set_field(1);
    child->set_position(Position::ALL);
    child->add_child()->set_field(1);

    vector<Matcher> matchers2;
    translateFieldMatcher(matcher2, &matchers2);
    ASSERT_EQ(1, matchers2.size());

    EXPECT_FALSE(subsetDimensions(matchers1, matchers2));
    EXPECT_TRUE(subsetDimensions(matchers2, matchers1));
}
/*
 * Test not a subset with one matching Matcher, one non-matching Matcher.
 */
TEST(AtomMatcherTest, TestSubsetDimensions2) {
    // Initialize first set of matchers
    FieldMatcher matcher1;
    matcher1.set_field(10);

    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);

    child = matcher1.add_child();
    child->set_field(2);

    vector<Matcher> matchers1;
    translateFieldMatcher(matcher1, &matchers1);

    // Initialize second set of matchers
    FieldMatcher matcher2;
    matcher2.set_field(10);

    child = matcher2.add_child();
    child->set_field(1);

    child = matcher2.add_child();
    child->set_field(3);

    vector<Matcher> matchers2;
    translateFieldMatcher(matcher2, &matchers2);

    EXPECT_FALSE(subsetDimensions(matchers1, matchers2));
}

/*
 * Test not a subset if parent field is not equal.
 */
TEST(AtomMatcherTest, TestSubsetDimensions3) {
    // Initialize first set of matchers
    FieldMatcher matcher1;
    matcher1.set_field(10);

    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);

    vector<Matcher> matchers1;
    translateFieldMatcher(matcher1, &matchers1);

    // Initialize second set of matchers
    FieldMatcher matcher2;
    matcher2.set_field(5);

    child = matcher2.add_child();
    child->set_field(1);

    vector<Matcher> matchers2;
    translateFieldMatcher(matcher2, &matchers2);

    EXPECT_FALSE(subsetDimensions(matchers1, matchers2));
}

/*
 * Test is subset with two matching Matchers.
 */
TEST(AtomMatcherTest, TestSubsetDimensions4) {
    // Initialize first set of matchers
    FieldMatcher matcher1;
    matcher1.set_field(10);

    FieldMatcher* child = matcher1.add_child();
    child->set_field(1);

    child = matcher1.add_child();
    child->set_field(2);

    vector<Matcher> matchers1;
    translateFieldMatcher(matcher1, &matchers1);

    // Initialize second set of matchers
    FieldMatcher matcher2;
    matcher2.set_field(10);

    child = matcher2.add_child();
    child->set_field(1);

    child = matcher2.add_child();
    child->set_field(2);

    child = matcher2.add_child();
    child->set_field(3);

    vector<Matcher> matchers2;
    translateFieldMatcher(matcher2, &matchers2);

    EXPECT_TRUE(subsetDimensions(matchers1, matchers2));
    EXPECT_FALSE(subsetDimensions(matchers2, matchers1));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
