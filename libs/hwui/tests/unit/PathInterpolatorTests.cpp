/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <Interpolator.h>

namespace android {
namespace uirenderer {

struct TestData {
    const std::vector<float> x;
    const std::vector<float> y;
    const std::vector<float> inFraction;
    const std::vector<float> outFraction;
};

const static TestData sTestDataSet[] = {
        {
                // Straight line as a path.
                {0.0f, 1.0f},
                {0.0f, 1.0f},
                {0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f},
                {0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f},
        },

        {{0.0f,       0.5f,       0.5178955f,  0.5341797f,  0.5489991f,  0.5625f,
          0.5748291f, 0.5861328f, 0.60625005f, 0.62402344f, 0.640625f,   0.675f,
          0.6951172f, 0.71875f,   0.7470703f,  0.78125f,    0.82246095f, 0.84606934f,
          0.871875f,  0.9000244f, 0.93066406f, 0.96394044f, 1.0f},
         {0.0f,        0.0f,         0.0028686523f, 0.011230469f, 0.024719238f, 0.04296875f,
          0.06561279f, 0.092285156f, 0.15625f,      0.2319336f,   0.31640625f,  0.5f,
          0.5932617f,  0.68359375f,  0.7680664f,    0.84375f,     0.90771484f,  0.9343872f,
          0.95703125f, 0.97528076f,  0.98876953f,   0.99713135f,  1.0f},
         {0.0f, 0.03375840187072754f, 0.13503384590148926f, 0.23630905151367188f,
          0.336834192276001f, 0.4508626461029053f, 0.564141035079956f, 0.6781694889068604f,
          0.7921979427337646f, 0.9054763317108154f, 1.0f},
         {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0459827296435833f, 0.5146934390068054f,
          0.8607426285743713f, 0.9776809215545654f, 1.0f

         }

        },
        {{0.0f,        0.017895509f, 0.034179688f, 0.048999026f, 0.0625f,     0.0748291f,
          0.08613282f, 0.10625f,     0.12402344f,  0.140625f,    0.17500001f, 0.19511719f,
          0.21875f,    0.24707031f,  0.28125f,     0.32246095f,  0.34606934f, 0.371875f,
          0.4000244f,  0.43066406f,  0.46394044f,  0.5f,         1.0f},
         {0.0f,         0.0028686523f, 0.011230469f, 0.024719238f, 0.04296875f, 0.06561279f,
          0.092285156f, 0.15625f,      0.2319336f,   0.31640625f,  0.5f,        0.5932617f,
          0.68359375f,  0.7680664f,    0.84375f,     0.90771484f,  0.9343872f,  0.95703125f,
          0.97528076f,  0.98876953f,   0.99713135f,  1.0f,         1.0f},
         {0.0f, 0.102020263671875f, 0.20330810546875f, 0.3165740966796875f, 0.43060302734375f,
          0.5318756103515625f, 0.6331634521484375f, 0.746429443359375f, 0.84771728515625f,
          0.9617462158203125f, 1.0f},
         {0.0f, 0.14280107617378235f, 0.6245699524879456f, 0.8985776901245117f, 0.9887426495552063f,
          1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f}},

};

static std::vector<float> getX(const TestData& data) {
    return data.x;
}

static std::vector<float> getY(const TestData& data) {
    return data.y;
}

TEST(Interpolator, pathInterpolation) {
    for (const TestData& data : sTestDataSet) {
        PathInterpolator interpolator(getX(data), getY(data));
        for (size_t i = 0; i < data.inFraction.size(); i++) {
            EXPECT_FLOAT_EQ(data.outFraction[i], interpolator.interpolate(data.inFraction[i]));
        }
    }
}
}
}
