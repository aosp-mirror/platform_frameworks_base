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

#include <canvas/CanvasFrontend.h>
#include <canvas/CanvasOpBuffer.h>
#include <canvas/CanvasOps.h>
#include <canvas/CanvasOpRasterizer.h>

#include <tests/common/CallCountingCanvas.h>

#include "SkPictureRecorder.h"
#include "SkColor.h"
#include "SkLatticeIter.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include <SkNoDrawCanvas.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::test;

class CanvasOpCountingReceiver {
public:
    template <CanvasOpType T>
    void push_container(CanvasOpContainer<T>&& op) {
        mOpCounts[static_cast<size_t>(T)] += 1;
    }

    int operator[](CanvasOpType op) const {
        return mOpCounts[static_cast<size_t>(op)];
    }

private:
    std::array<int, static_cast<size_t>(CanvasOpType::COUNT)> mOpCounts;
};

TEST(CanvasFrontend, saveCount) {
    SkNoDrawCanvas skiaCanvas(100, 100);
    CanvasFrontend<CanvasOpCountingReceiver> opCanvas(100, 100);
    const auto& receiver = opCanvas.receiver();

    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    skiaCanvas.save();
    opCanvas.save(SaveFlags::MatrixClip);
    EXPECT_EQ(2, skiaCanvas.getSaveCount());
    EXPECT_EQ(2, opCanvas.saveCount());

    skiaCanvas.restore();
    opCanvas.restore();
    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    skiaCanvas.restore();
    opCanvas.restore();
    EXPECT_EQ(1, skiaCanvas.getSaveCount());
    EXPECT_EQ(1, opCanvas.saveCount());

    EXPECT_EQ(1, receiver[CanvasOpType::Save]);
    EXPECT_EQ(1, receiver[CanvasOpType::Restore]);
}

TEST(CanvasFrontend, transform) {
    SkNoDrawCanvas skiaCanvas(100, 100);
    CanvasFrontend<CanvasOpCountingReceiver> opCanvas(100, 100);

    skiaCanvas.translate(10, 10);
    opCanvas.translate(10, 10);
    EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());

    {
        skiaCanvas.save();
        opCanvas.save(SaveFlags::Matrix);
        skiaCanvas.scale(2.0f, 1.125f);
        opCanvas.scale(2.0f, 1.125f);

        EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());
        skiaCanvas.restore();
        opCanvas.restore();
    }

    EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());

    {
        skiaCanvas.save();
        opCanvas.save(SaveFlags::Matrix);
        skiaCanvas.rotate(90.f);
        opCanvas.rotate(90.f);

        EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());

        {
            skiaCanvas.save();
            opCanvas.save(SaveFlags::Matrix);
            skiaCanvas.skew(5.0f, 2.25f);
            opCanvas.skew(5.0f, 2.25f);

            EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());
            skiaCanvas.restore();
            opCanvas.restore();
        }

        skiaCanvas.restore();
        opCanvas.restore();
    }

    EXPECT_EQ(skiaCanvas.getTotalMatrix(), opCanvas.transform());
}

TEST(CanvasFrontend, drawOpTransform) {
    CanvasFrontend<CanvasOpBuffer> opCanvas(100, 100);
    const auto &receiver = opCanvas.receiver();

    auto makeDrawRect = [] {
        return CanvasOp<CanvasOpType::DrawRect>{
                .rect = SkRect::MakeWH(50, 50),
                .paint = SkPaint(SkColors::kBlack),
        };
    };

    opCanvas.draw(makeDrawRect());

    opCanvas.translate(10, 10);
    opCanvas.draw(makeDrawRect());

    opCanvas.save();
    opCanvas.scale(2.0f, 4.0f);
    opCanvas.draw(makeDrawRect());
    opCanvas.restore();

    opCanvas.save();
    opCanvas.translate(20, 15);
    opCanvas.draw(makeDrawRect());
    opCanvas.save();
    opCanvas.rotate(90.f);
    opCanvas.draw(makeDrawRect());
    opCanvas.restore();
    opCanvas.restore();

    // Validate the results
    std::vector<SkMatrix> transforms;
    transforms.reserve(5);
    receiver.for_each([&](auto op) {
        // Filter for the DrawRect calls; ignore the save & restores
        // (TODO: Add a filtered for_each variant to OpBuffer?)
        if (op->type() == CanvasOpType::DrawRect) {
            transforms.push_back(op->transform());
        }
    });

    EXPECT_EQ(transforms.size(), 5);

    {
        // First result should be identity
        const auto &result = transforms[0];
        EXPECT_EQ(SkMatrix::kIdentity_Mask, result.getType());
        EXPECT_EQ(SkMatrix::I(), result);
    }

    {
        // Should be translate 10, 10
        const auto &result = transforms[1];
        EXPECT_EQ(SkMatrix::kTranslate_Mask, result.getType());
        SkMatrix m;
        m.setTranslate(10, 10);
        EXPECT_EQ(m, result);
    }

    {
        // Should be translate 10, 10 + scale 2, 4
        const auto &result = transforms[2];
        EXPECT_EQ(SkMatrix::kTranslate_Mask | SkMatrix::kScale_Mask, result.getType());
        SkMatrix m;
        m.setTranslate(10, 10);
        m.preScale(2.0f, 4.0f);
        EXPECT_EQ(m, result);
    }

    {
        // Should be translate 10, 10 + translate 20, 15
        const auto &result = transforms[3];
        EXPECT_EQ(SkMatrix::kTranslate_Mask, result.getType());
        SkMatrix m;
        m.setTranslate(30, 25);
        EXPECT_EQ(m, result);
    }

    {
        // Should be translate 10, 10 + translate 20, 15 + rotate 90
        const auto &result = transforms[4];
        EXPECT_EQ(SkMatrix::kTranslate_Mask | SkMatrix::kAffine_Mask | SkMatrix::kScale_Mask,
                  result.getType());
        SkMatrix m;
        m.setTranslate(30, 25);
        m.preRotate(90.f);
        EXPECT_EQ(m, result);
    }
}