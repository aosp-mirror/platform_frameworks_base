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
#include <gtest/gtest.h>

#include "metadata_util.h"
#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

TEST(MetadataUtilTest, TestWriteAndReadMetricDimensionKey) {
    HashableDimensionKey dim;
    HashableDimensionKey dim2;
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

    dim2.addValue(FieldValue(field1, value1));
    dim2.addValue(FieldValue(field2, value2));

    MetricDimensionKey dimKey(dim, dim2);

    metadata::MetricDimensionKey metadataDimKey;
    writeMetricDimensionKeyToMetadataDimensionKey(dimKey, &metadataDimKey);

    MetricDimensionKey loadedDimKey = loadMetricDimensionKeyFromProto(metadataDimKey);

    ASSERT_EQ(loadedDimKey, dimKey);
    ASSERT_EQ(std::hash<MetricDimensionKey>{}(loadedDimKey),
            std::hash<MetricDimensionKey>{}(dimKey));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
