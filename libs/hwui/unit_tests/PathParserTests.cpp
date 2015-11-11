/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "PathParser.h"
#include "VectorDrawablePath.h"

#include <functional>

namespace android {
namespace uirenderer {

struct TestData {
    const char* pathString;
    const PathData pathData;
    const std::function<void(SkPath*)> skPathLamda;
};

const static std::vector<TestData> testDataSet = {
    // TestData with scientific notation -2e3 etc.
    {
        // Path
        "M2.000000,22.000000l20.000000,0.000000 1e0-2e3z",
        {
            // Verbs
            {'M', 'l', 'z'},
            // Verb sizes
            {2, 4, 0},
            // Points
            {2, 22, 20, 0,  1, -2000},
        },
        [](SkPath* outPath) {
            outPath->moveTo(2, 22);
            outPath->rLineTo(20, 0);
            outPath->rLineTo(1, -2000);
            outPath->close();
            outPath->moveTo(2, 22);
        }
    },

    // Comprehensive data, containing all the verbs possible.
    {
        // Path
        "M 1 1 m 2 2, l 3 3 L 3 3 H 4 h4 V5 v5, Q6 6 6 6 q 6 6 6 6t 7 7 T 7 7 C 8 8 8 8 8 8 c 8 8 8 8 8 8 S 9 9 9 9 s 9 9 9 9 A 10 10 0 1 1 10 10 a 10 10 0 1 1 10 10",
        {
            // Verbs
            {'M', 'm', 'l', 'L', 'H', 'h', 'V', 'v', 'Q', 'q', 't', 'T', 'C', 'c', 'S', 's', 'A', 'a'},
            // VerbSizes
            {2, 2, 2, 2, 1, 1, 1, 1, 4, 4, 2, 2, 6, 6, 4, 4, 7, 7},
            // Points
            {1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 3.0, 4.0, 4.0, 5.0, 5.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 7.0, 7.0, 7.0, 7.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 10.0, 10.0, 0.0, 1.0, 1.0, 10.0, 10.0, 10.0, 10.0, 0.0, 1.0, 1.0, 10.0, 10.0, }
        },
        [](SkPath* outPath) {
            outPath->moveTo(1.0, 1.0);
            outPath->rMoveTo(2.0, 2.0);
            outPath->rLineTo(3.0, 3.0);
            outPath->lineTo(3.0, 3.0);
            outPath->lineTo(4.0, 3.0);
            outPath->rLineTo(4.0, 0);
            outPath->lineTo(8.0, 5.0);
            outPath->rLineTo(0, 5.0);
            outPath->quadTo(6.0, 6.0, 6.0, 6.0);
            outPath->rQuadTo(6.0, 6.0, 6.0, 6.0);
            outPath->rQuadTo(0.0, 0.0, 7.0, 7.0);
            outPath->quadTo(26.0, 26.0, 7.0, 7.0);
            outPath->cubicTo(8.0, 8.0, 8.0, 8.0, 8.0, 8.0);
            outPath->rCubicTo(8.0, 8.0, 8.0, 8.0, 8.0, 8.0);
            outPath->cubicTo(16.0, 16.0, 9.0, 9.0, 9.0, 9.0);
            outPath->rCubicTo(0.0, 0.0, 9.0, 9.0, 9.0, 9.0);
            outPath->cubicTo(18.447775037328352, 20.404243860300607, 17.998389141249767, 22.8911717921705, 16.737515350332117, 24.986664170401575);
            outPath->cubicTo(15.476641559414468, 27.08215654863265, 13.489843598291483, 28.644011882390082, 11.155893964798905, 29.37447073281729);
            outPath->cubicTo(8.821944331306327, 30.1049295832445, 6.299226382436471, 29.954422532383525, 4.0686829203897235, 28.951642951534332);
            outPath->cubicTo(1.838139458342976, 27.94886337068514, 0.05113662931485696, 26.161860541657013, -0.9516429515343354, 23.931317079610267);
            outPath->cubicTo(-1.9544225323835278, 21.70077361756352, -2.1049295832444987, 19.178055668693663, -1.37447073281729, 16.844106035201087);
            outPath->cubicTo(-0.6440118823900814, 14.51015640170851, 0.9178434513673546, 12.523358440585524, 3.0133358295984305, 11.262484649667876);
            outPath->cubicTo(5.108828207829506, 10.001610858750228, 7.5957561396993984, 9.552224962671648, 10.000000000000005, 10.0);
            outPath->cubicTo(10.0, 7.348852265086975, 11.054287646850167, 4.803576729418881, 12.928932188134523, 2.9289321881345254);
            outPath->cubicTo(14.803576729418879, 1.0542876468501696, 17.348852265086972, 4.870079381441987E-16, 19.999999999999996, 0.0);
            outPath->cubicTo(22.65114773491302, -4.870079381441987E-16, 25.19642327058112, 1.0542876468501678, 27.071067811865476, 2.9289321881345227);
            outPath->cubicTo(28.94571235314983, 4.803576729418878, 30.0, 7.348852265086974, 30.0, 9.999999999999998);
            outPath->cubicTo(30.0, 12.651147734913023, 28.94571235314983, 15.19642327058112, 27.071067811865476, 17.071067811865476);
            outPath->cubicTo(25.19642327058112, 18.94571235314983, 22.651147734913028, 20.0, 20.000000000000004, 20.0);
        }
    },

    // Random long data
    {
        // Path
        "M5.3,13.2c-0.1,0.0 -0.3,0.0 -0.4,-0.1c-0.3,-0.2 -0.4,-0.7 -0.2,-1.0c1.3,-1.9 2.9,-3.4 4.9,-4.5c4.1,-2.2 9.3,-2.2 13.4,0.0c1.9,1.1 3.6,2.5 4.9,4.4c0.2,0.3 0.1,0.8 -0.2,1.0c-0.3,0.2 -0.8,0.1 -1.0,-0.2c-1.2,-1.7 -2.6,-3.0 -4.3,-4.0c-3.7,-2.0 -8.3,-2.0 -12.0,0.0c-1.7,0.9 -3.2,2.3 -4.3,4.0C5.7,13.1 5.5,13.2 5.3,13.2z",
        {
            // Verbs
            {'M', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'C', 'z'},
            // Verb sizes
            {2, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 0},
            // Points
            {5.3, 13.2, -0.1, 0, -0.3, 0, -0.4, -0.1, -0.3, -0.2, -0.4, -0.7, -0.2, -1, 1.3, -1.9, 2.9, -3.4, 4.9, -4.5, 4.1, -2.2, 9.3, -2.2, 13.4, 0, 1.9, 1.1, 3.6, 2.5, 4.9, 4.4, 0.2, 0.3, 0.1, 0.8, -0.2, 1, -0.3, 0.2, -0.8, 0.1, -1, -0.2, -1.2, -1.7, -2.6, -3, -4.3, -4, -3.7, -2, -8.3, -2, -12, 0, -1.7, 0.9, -3.2, 2.3, -4.3, 4, 5.7, 13.1, 5.5, 13.2, 5.3, 13.2},
        },
        [](SkPath* outPath) {
            outPath->moveTo(5.3, 13.2);
            outPath->rCubicTo(-0.1, 0.0, -0.3, 0.0, -0.4, -0.1);
            outPath->rCubicTo(-0.3, -0.2, -0.4, -0.7, -0.2, -1.0);
            outPath->rCubicTo(1.3, -1.9, 2.9, -3.4, 4.9, -4.5);
            outPath->rCubicTo(4.1, -2.2, 9.3, -2.2, 13.4, 0.0);
            outPath->rCubicTo(1.9, 1.1, 3.6, 2.5, 4.9, 4.4);
            outPath->rCubicTo(0.2, 0.3, 0.1, 0.8, -0.2, 1.0);
            outPath->rCubicTo(-0.3, 0.2, -0.8, 0.1, -1.0, -0.2);
            outPath->rCubicTo(-1.2, -1.7, -2.6, -3.0, -4.3, -4.0);
            outPath->rCubicTo(-3.7, -2.0, -8.3, -2.0, -12.0, 0.0);
            outPath->rCubicTo(-1.7, 0.9, -3.2, 2.3, -4.3, 4.0);
            outPath->cubicTo(5.7, 13.1, 5.5, 13.2, 5.3, 13.2);
            outPath->close();
            outPath->moveTo(5.3, 13.2);
        }
    },

    // Extreme case with numbers and decimal points crunched together
    {
        // Path
        "l0.0.0.5.0.0.5-0.5.0.0-.5z",
        {
            // Verbs
            {'l', 'z'},
            // Verb sizes
            {10, 0},
            // Points
            {0, 0, 0.5, 0, 0, 0.5, -0.5, 0, 0, -0.5},
        },
        [](SkPath* outPath) {
            outPath->rLineTo(0.0, 0.0);
            outPath->rLineTo(0.5, 0.0);
            outPath->rLineTo(0.0, 0.5);
            outPath->rLineTo(-0.5, 0.0);
            outPath->rLineTo(0.0, -0.5);
            outPath->close();
            outPath->moveTo(0.0, 0.0);
        }
    },

    // Empty test data
    {
        "",
        {
                // Verbs
                {},
                {},
                {},
        },
        [](SkPath* outPath) {}
    }

};

TEST(PathParser, parseStringForData) {
    for (TestData testData: testDataSet) {
        // Test generated path data against the given data.
        PathData pathData;
        size_t length = strlen(testData.pathString);
        PathParser::getPathDataFromString(&pathData, testData.pathString, length);
        PathParser::dump(pathData);
        EXPECT_EQ(testData.pathData, pathData);
    }

}

TEST(PathParser, createSkPathFromPathData) {
    for (TestData testData: testDataSet) {
        SkPath expectedPath;
        testData.skPathLamda(&expectedPath);
        SkPath actualPath;
        VectorDrawablePath::verbsToPath(&actualPath, &testData.pathData);
        EXPECT_EQ(expectedPath, actualPath);
    }
}

TEST(PathParser, parseStringForSkPath) {
    for (TestData testData: testDataSet) {
        size_t length = strlen(testData.pathString);
        // Check the return value as well as the SkPath generated.
        SkPath actualPath;
        bool hasValidData = PathParser::parseStringForSkPath(&actualPath, testData.pathString,
                length);
        EXPECT_EQ(hasValidData, testData.pathData.verbs.size() > 0);
        SkPath expectedPath;
        testData.skPathLamda(&expectedPath);
        EXPECT_EQ(expectedPath, actualPath);
    }
    SkPath path;
    EXPECT_FALSE(PathParser::parseStringForSkPath(&path, "l", 1));
    EXPECT_FALSE(PathParser::parseStringForSkPath(&path, "1 1", 3));
    EXPECT_FALSE(PathParser::parseStringForSkPath(&path, "LMFAO", 5));
    EXPECT_TRUE(PathParser::parseStringForSkPath(&path, "m1 1", 4));
}

}; // namespace uirenderer
}; // namespace android
