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
#include "VectorDrawable.h"
#include "utils/MathUtils.h"
#include "utils/VectorDrawableUtils.h"

#include <functional>

namespace android {
namespace uirenderer {

struct TestData {
    const char* pathString;
    const PathData pathData;
    const std::function<void(SkPath*)> skPathLamda;
};

const static TestData sTestDataSet[] = {
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

    // Check box VectorDrawable path data
    {
        // Path
        "M 0.0,-1.0 l 0.0,0.0 c 0.5522847498,0.0 1.0,0.4477152502 1.0,1.0 l 0.0,0.0 c 0.0,0.5522847498 -0.4477152502,1.0 -1.0,1.0 l 0.0,0.0 c -0.5522847498,0.0 -1.0,-0.4477152502 -1.0,-1.0 l 0.0,0.0 c 0.0,-0.5522847498 0.4477152502,-1.0 1.0,-1.0 Z M 7.0,-9.0 c 0.0,0.0 -14.0,0.0 -14.0,0.0 c -1.1044921875,0.0 -2.0,0.8955078125 -2.0,2.0 c 0.0,0.0 0.0,14.0 0.0,14.0 c 0.0,1.1044921875 0.8955078125,2.0 2.0,2.0 c 0.0,0.0 14.0,0.0 14.0,0.0 c 1.1044921875,0.0 2.0,-0.8955078125 2.0,-2.0 c 0.0,0.0 0.0,-14.0 0.0,-14.0 c 0.0,-1.1044921875 -0.8955078125,-2.0 -2.0,-2.0 c 0.0,0.0 0.0,0.0 0.0,0.0 Z",
        {
            {'M', 'l', 'c', 'l', 'c', 'l', 'c', 'l', 'c', 'Z', 'M', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'c', 'Z'},
            {2, 2, 6, 2, 6, 2, 6, 2, 6, 0, 2, 6, 6, 6, 6, 6, 6, 6, 6, 6, 0},
            {0.0, -1.0, 0.0, 0.0, 0.5522848, 0.0, 1.0, 0.44771525, 1.0, 1.0, 0.0, 0.0, 0.0, 0.5522848, -0.44771525, 1.0, -1.0, 1.0, 0.0, 0.0, -0.5522848, 0.0, -1.0, -0.44771525, -1.0, -1.0, 0.0, 0.0, 0.0, -0.5522848, 0.44771525, -1.0, 1.0, -1.0, 7.0, -9.0, 0.0, 0.0, -14.0, 0.0, -14.0, 0.0, -1.1044922, 0.0, -2.0, 0.8955078, -2.0, 2.0, 0.0, 0.0, 0.0, 14.0, 0.0, 14.0, 0.0, 1.1044922, 0.8955078, 2.0, 2.0, 2.0, 0.0, 0.0, 14.0, 0.0, 14.0, 0.0, 1.1044922, 0.0, 2.0, -0.8955078, 2.0, -2.0, 0.0, 0.0, 0.0, -14.0, 0.0, -14.0, 0.0, -1.1044922, -0.8955078, -2.0, -2.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
        },
        [](SkPath* outPath) {
            outPath->moveTo(0.0, -1.0);
            outPath->rLineTo(0.0, 0.0);
            outPath->rCubicTo(0.5522848, 0.0, 1.0, 0.44771525, 1.0, 1.0);
            outPath->rLineTo(0.0, 0.0);
            outPath->rCubicTo(0.0, 0.5522848, -0.44771525, 1.0, -1.0, 1.0);
            outPath->rLineTo(0.0, 0.0);
            outPath->rCubicTo(-0.5522848, 0.0, -1.0, -0.44771525, -1.0, -1.0);
            outPath->rLineTo(0.0, 0.0);
            outPath->rCubicTo(0.0, -0.5522848, 0.44771525, -1.0, 1.0, -1.0);
            outPath->close();
            outPath->moveTo(0.0, -1.0);
            outPath->moveTo(7.0, -9.0);
            outPath->rCubicTo(0.0, 0.0, -14.0, 0.0, -14.0, 0.0);
            outPath->rCubicTo(-1.1044922, 0.0, -2.0, 0.8955078, -2.0, 2.0);
            outPath->rCubicTo(0.0, 0.0, 0.0, 14.0, 0.0, 14.0);
            outPath->rCubicTo(0.0, 1.1044922, 0.8955078, 2.0, 2.0, 2.0);
            outPath->rCubicTo(0.0, 0.0, 14.0, 0.0, 14.0, 0.0);
            outPath->rCubicTo(1.1044922, 0.0, 2.0, -0.8955078, 2.0, -2.0);
            outPath->rCubicTo(0.0, 0.0, 0.0, -14.0, 0.0, -14.0);
            outPath->rCubicTo(0.0, -1.1044922, -0.8955078, -2.0, -2.0, -2.0);
            outPath->rCubicTo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            outPath->close();
            outPath->moveTo(7.0, -9.0);
        }
    },

    // pie1 in progress bar
    {
        "M300,70 a230,230 0 1,0 1,0 z",
        {
             {'M', 'a', 'z', },
             {2, 7, 0, },
             {300.0, 70.0, 230.0, 230.0, 0.0, 1.0, 0.0, 1.0, 0.0, },
        },
        [](SkPath* outPath) {
            outPath->moveTo(300.0, 70.0);
            outPath->cubicTo(239.06697794203706, 70.13246340443499, 180.6164396449267, 94.47383115953485, 137.6004913602211, 137.6302781499585);
            outPath->cubicTo(94.58454307551551, 180.78672514038215, 70.43390412842275, 239.3163266242308, 70.50013586976587, 300.2494566687817);
            outPath->cubicTo(70.56636761110899, 361.1825867133326, 94.84418775550249, 419.65954850554147, 137.9538527586204, 462.72238058830936);
            outPath->cubicTo(181.06351776173827, 505.7852126710772, 239.5668339599056, 529.999456521097, 300.49999999999994, 529.999456521097);
            outPath->cubicTo(361.43316604009436, 529.999456521097, 419.93648223826176, 505.78521267107726, 463.0461472413797, 462.7223805883093);
            outPath->cubicTo(506.1558122444976, 419.65954850554135, 530.433632388891, 361.1825867133324, 530.4998641302341, 300.2494566687815);
            outPath->cubicTo(530.5660958715771, 239.31632662423056, 506.4154569244844, 180.7867251403819, 463.3995086397787, 137.6302781499583);
            outPath->cubicTo(420.383560355073, 94.47383115953468, 361.93302205796255, 70.13246340443492, 300.9999999999996, 70.00000000000003);
            outPath->close();
            outPath->moveTo(300.0, 70.0);
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

struct StringPath {
    const char* stringPath;
    bool isValid;
};

const StringPath sStringPaths[] = {
    {"3e...3", false}, // Not starting with a verb and ill-formatted float
    {"L.M.F.A.O", false}, // No floats following verbs
    {"m 1 1", true}, // Valid path data
    {"\n \t   z", true}, // Valid path data with leading spaces
    {"1-2e34567", false}, // Not starting with a verb and ill-formatted float
    {"f 4 5", false}, // Invalid verb
    {"\r      ", false} // Empty string
};


static bool hasSameVerbs(const PathData& from, const PathData& to) {
    return from.verbs == to.verbs && from.verbSizes == to.verbSizes;
}

TEST(PathParser, parseStringForData) {
    for (TestData testData: sTestDataSet) {
        PathParser::ParseResult result;
        // Test generated path data against the given data.
        PathData pathData;
        size_t length = strlen(testData.pathString);
        PathParser::getPathDataFromAsciiString(&pathData, &result, testData.pathString, length);
        EXPECT_EQ(testData.pathData, pathData);
    }

    for (StringPath stringPath : sStringPaths) {
        PathParser::ParseResult result;
        PathData pathData;
        SkPath skPath;
        PathParser::getPathDataFromAsciiString(&pathData, &result,
                stringPath.stringPath, strlen(stringPath.stringPath));
        EXPECT_EQ(stringPath.isValid, !result.failureOccurred);
    }
}

TEST(VectorDrawableUtils, createSkPathFromPathData) {
    for (TestData testData: sTestDataSet) {
        SkPath expectedPath;
        testData.skPathLamda(&expectedPath);
        SkPath actualPath;
        VectorDrawableUtils::verbsToPath(&actualPath, testData.pathData);
        EXPECT_EQ(expectedPath, actualPath);
    }
}

TEST(PathParser, parseAsciiStringForSkPath) {
    for (TestData testData: sTestDataSet) {
        PathParser::ParseResult result;
        size_t length = strlen(testData.pathString);
        // Check the return value as well as the SkPath generated.
        SkPath actualPath;
        PathParser::parseAsciiStringForSkPath(&actualPath, &result, testData.pathString, length);
        bool hasValidData = !result.failureOccurred;
        EXPECT_EQ(hasValidData, testData.pathData.verbs.size() > 0);
        SkPath expectedPath;
        testData.skPathLamda(&expectedPath);
        EXPECT_EQ(expectedPath, actualPath);
    }

    for (StringPath stringPath : sStringPaths) {
        PathParser::ParseResult result;
        SkPath skPath;
        PathParser::parseAsciiStringForSkPath(&skPath, &result, stringPath.stringPath,
                strlen(stringPath.stringPath));
        EXPECT_EQ(stringPath.isValid, !result.failureOccurred);
    }
}

TEST(VectorDrawableUtils, morphPathData) {
    for (TestData fromData: sTestDataSet) {
        for (TestData toData: sTestDataSet) {
            bool canMorph = VectorDrawableUtils::canMorph(fromData.pathData, toData.pathData);
            if (fromData.pathData == toData.pathData) {
                EXPECT_TRUE(canMorph);
            } else {
                bool expectedToMorph = hasSameVerbs(fromData.pathData, toData.pathData);
                EXPECT_EQ(expectedToMorph, canMorph);
            }
        }
    }
}

TEST(VectorDrawableUtils, interpolatePathData) {
    // Interpolate path data with itself and every other path data
    for (TestData fromData: sTestDataSet) {
        for (TestData toData: sTestDataSet) {
            PathData outData;
            bool success = VectorDrawableUtils::interpolatePathData(&outData, fromData.pathData,
                    toData.pathData, 0.5);
            bool expectedToMorph = hasSameVerbs(fromData.pathData, toData.pathData);
            EXPECT_EQ(expectedToMorph, success);
        }
    }

    float fractions[] = {0, 0.00001, 0.28, 0.5, 0.7777, 0.9999999, 1};
    // Now try to interpolate with a slightly modified version of self and expect success
    for (TestData fromData : sTestDataSet) {
        PathData toPathData = fromData.pathData;
        for (size_t i = 0; i < toPathData.points.size(); i++) {
            toPathData.points[i]++;
        }
        const PathData& fromPathData = fromData.pathData;
        PathData outData;
        // Interpolate the two path data with different fractions
        for (float fraction : fractions) {
            bool success = VectorDrawableUtils::interpolatePathData(
                    &outData, fromPathData, toPathData, fraction);
            EXPECT_TRUE(success);
            for (size_t i = 0; i < outData.points.size(); i++) {
                float expectedResult = fromPathData.points[i] * (1.0 - fraction) +
                        toPathData.points[i] * fraction;
                EXPECT_TRUE(MathUtils::areEqual(expectedResult, outData.points[i]));
            }
        }
    }
}

TEST(VectorDrawable, matrixScale) {
    struct MatrixAndScale {
        float buffer[9];
        float matrixScale;
    };

    const MatrixAndScale sMatrixAndScales[] {
        {
            {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            1.0
        },
        {
            {1.0f, 0.0f, 240.0f, 0.0f, 1.0f, 240.0f, 0.0f, 0.0f, 1.0f},
            1.0f,
        },
        {
            {1.5f, 0.0f, 24.0f, 0.0f, 1.5f, 24.0f, 0.0f, 0.0f, 1.0f},
            1.5f,
        },
        {
            {0.99999994f, 0.0f, 300.0f, 0.0f, 0.99999994f, 158.57864f, 0.0f, 0.0f, 1.0f},
            0.99999994f,
        },
        {
            {0.7071067f, 0.7071067f, 402.5305f, -0.7071067f, 0.7071067f, 169.18524f, 0.0f, 0.0f, 1.0f},
            0.99999994f,
        },
        {
            {0.0f, 0.9999999f, 482.5305f, -0.9999999f, 0.0f, 104.18525f, 0.0f, 0.0f, 1.0f},
            0.9999999f,
        },
        {
            {-0.35810637f, -0.93368083f, 76.55821f, 0.93368083f, -0.35810637f, 89.538506f, 0.0f, 0.0f, 1.0f},
            1.0000001f,
        },
    };

    for (MatrixAndScale matrixAndScale : sMatrixAndScales) {
        SkMatrix matrix;
        matrix.set9(matrixAndScale.buffer);
        float actualMatrixScale = VectorDrawable::Path::getMatrixScale(matrix);
        EXPECT_EQ(matrixAndScale.matrixScale, actualMatrixScale);
    }
}

TEST(VectorDrawable, groupProperties) {
    //TODO: Also need to test property sync and dirty flag when properties change.
    VectorDrawable::Group group;
    VectorDrawable::Group::GroupProperties* properties = group.mutateProperties();
    // Test default values, change values through setters and verify the change through getters.
    EXPECT_EQ(0.0f, properties->getTranslateX());
    properties->setTranslateX(1.0f);
    EXPECT_EQ(1.0f, properties->getTranslateX());

    EXPECT_EQ(0.0f, properties->getTranslateY());
    properties->setTranslateY(1.0f);
    EXPECT_EQ(1.0f, properties->getTranslateY());

    EXPECT_EQ(0.0f, properties->getRotation());
    properties->setRotation(1.0f);
    EXPECT_EQ(1.0f, properties->getRotation());

    EXPECT_EQ(1.0f, properties->getScaleX());
    properties->setScaleX(0.0f);
    EXPECT_EQ(0.0f, properties->getScaleX());

    EXPECT_EQ(1.0f, properties->getScaleY());
    properties->setScaleY(0.0f);
    EXPECT_EQ(0.0f, properties->getScaleY());

    EXPECT_EQ(0.0f, properties->getPivotX());
    properties->setPivotX(1.0f);
    EXPECT_EQ(1.0f, properties->getPivotX());

    EXPECT_EQ(0.0f, properties->getPivotY());
    properties->setPivotY(1.0f);
    EXPECT_EQ(1.0f, properties->getPivotY());

}

static SkShader* createShader(bool* isDestroyed) {
    class TestShader : public SkShader {
    public:
        TestShader(bool* isDestroyed) : SkShader(), mDestroyed(isDestroyed) {
        }
        ~TestShader() {
            *mDestroyed = true;
        }

        Factory getFactory() const override { return nullptr; }
    private:
        bool* mDestroyed;
    };
    return new TestShader(isDestroyed);
}

TEST(VectorDrawable, drawPathWithoutIncrementingShaderRefCount) {
    VectorDrawable::FullPath path("m1 1", 4);
    SkBitmap bitmap;
    SkImageInfo info = SkImageInfo::Make(5, 5, kN32_SkColorType, kPremul_SkAlphaType);
    bitmap.setInfo(info);
    bitmap.allocPixels(info);
    SkCanvas canvas(bitmap);

    bool shaderIsDestroyed = false;

    // Initial ref count is 1
    SkShader* shader = createShader(&shaderIsDestroyed);

    // Setting the fill gradient increments the ref count of the shader by 1
    path.mutateStagingProperties()->setFillGradient(shader);
    path.draw(&canvas, SkMatrix::I(), 1.0f, 1.0f, true);
    // Resetting the fill gradient decrements the ref count of the shader by 1
    path.mutateStagingProperties()->setFillGradient(nullptr);

    // Expect ref count to be 1 again, i.e. nothing else to have a ref to the shader now. Unref()
    // again should bring the ref count to zero and consequently trigger detor.
    shader->unref();

    // Verify that detor is called.
    EXPECT_TRUE(shaderIsDestroyed);
}

}; // namespace uirenderer
}; // namespace android
