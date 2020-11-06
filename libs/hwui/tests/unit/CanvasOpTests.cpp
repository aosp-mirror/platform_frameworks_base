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

#include <canvas/CanvasOpBuffer.h>
#include <canvas/CanvasOps.h>
#include <canvas/CanvasOpRasterizer.h>

#include <tests/common/CallCountingCanvas.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::test;

// We lazy
using Op = CanvasOpType;

enum MockTypes {
    Lifecycle,
    COUNT
};

template<MockTypes T>
struct MockOp;

template<MockTypes T>
struct MockOpContainer {
    OpBufferItemHeader<MockTypes> header;
    MockOp<T> impl;
};

struct LifecycleTracker {
    int ctor_count = 0;
    int dtor_count = 0;

    int alive() { return ctor_count - dtor_count; }
};

template<>
struct MockOp<MockTypes::Lifecycle> {
    MockOp() = delete;
    void operator=(const MockOp&) = delete;

    MockOp(LifecycleTracker* tracker) : tracker(tracker) {
        tracker->ctor_count += 1;
    }

    MockOp(const MockOp& other) {
        tracker = other.tracker;
        tracker->ctor_count += 1;
    }

    ~MockOp() {
        tracker->dtor_count += 1;
    }

    LifecycleTracker* tracker = nullptr;
};

using MockBuffer = OpBuffer<MockTypes, MockOpContainer>;

template<typename T>
static int countItems(const T& t) {
    int count = 0;
    t.for_each([&](auto i) {
        count++;
    });
    return count;
}

TEST(CanvasOp, lifecycleCheck) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
            .impl = MockOp<MockTypes::Lifecycle>{&tracker}
        });
        EXPECT_EQ(tracker.alive(), 1);
        buffer.clear();
        EXPECT_EQ(tracker.alive(), 0);
    }
    EXPECT_EQ(tracker.alive(), 0);
}

TEST(CanvasOp, lifecycleCheckMove) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
            .impl = MockOp<MockTypes::Lifecycle>{&tracker}
        });
        EXPECT_EQ(tracker.alive(), 1);
        {
            MockBuffer other(std::move(buffer));
            EXPECT_EQ(tracker.alive(), 1);
            EXPECT_EQ(buffer.size(), 0);
            EXPECT_GT(other.size(), 0);
            EXPECT_EQ(1, countItems(other));
            EXPECT_EQ(0, countItems(buffer));

            other.push_container(MockOpContainer<MockTypes::Lifecycle> {
                .impl = MockOp<MockTypes::Lifecycle>{&tracker}
            });

            EXPECT_EQ(2, countItems(other));
            EXPECT_EQ(2, tracker.alive());

            buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
                .impl = MockOp<MockTypes::Lifecycle>{&tracker}
            });
            EXPECT_EQ(1, countItems(buffer));
            EXPECT_EQ(3, tracker.alive());

            buffer = std::move(other);
            EXPECT_EQ(2, countItems(buffer));
            EXPECT_EQ(2, tracker.alive());
        }
        EXPECT_EQ(2, countItems(buffer));
        EXPECT_EQ(2, tracker.alive());
        buffer.clear();
        EXPECT_EQ(0, countItems(buffer));
        EXPECT_EQ(0, tracker.alive());
    }
    EXPECT_EQ(tracker.alive(), 0);
}

TEST(CanvasOp, simplePush) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push<Op::Save>({});
    buffer.push<Op::Save>({});
    buffer.push<Op::Restore>({});
    EXPECT_GT(buffer.size(), 0);

    int saveCount = 0;
    int restoreCount = 0;
    int otherCount = 0;

    buffer.for_each([&](auto op) {
        switch (op->type()) {
            case Op::Save:
                saveCount++;
                break;
            case Op::Restore:
                restoreCount++;
                break;
            default:
                otherCount++;
                break;
        }
    });

    EXPECT_EQ(saveCount, 2);
    EXPECT_EQ(restoreCount, 1);
    EXPECT_EQ(otherCount, 0);

    buffer.clear();
    int itemCount = 0;
    buffer.for_each([&](auto op) {
        itemCount++;
    });
    EXPECT_EQ(itemCount, 0);
    buffer.resize(0);
    EXPECT_EQ(buffer.size(), 0);
}

TEST(CanvasOp, simpleDrawRect) {
    CanvasOpBuffer buffer;
    EXPECT_EQ(buffer.size(), 0);
    buffer.push(CanvasOp<Op::DrawRect> {
        .paint = SkPaint{},
        .rect = SkRect::MakeEmpty()
    });

    CallCountingCanvas canvas;
    EXPECT_EQ(0, canvas.sumTotalDrawCalls());
    rasterizeCanvasBuffer(buffer, &canvas);
    EXPECT_EQ(1, canvas.drawRectCount);
    EXPECT_EQ(1, canvas.sumTotalDrawCalls());
}

TEST(CanvasOp, immediateRendering) {
    auto canvas = std::make_shared<CallCountingCanvas>();

    EXPECT_EQ(0, canvas->sumTotalDrawCalls());
    ImmediateModeRasterizer rasterizer{canvas};
    auto op = CanvasOp<Op::DrawRect> {
        .paint = SkPaint{},
        .rect = SkRect::MakeEmpty()
    };
    EXPECT_TRUE(CanvasOpTraits::can_draw<decltype(op)>);
    rasterizer.draw(op);
    EXPECT_EQ(1, canvas->drawRectCount);
    EXPECT_EQ(1, canvas->sumTotalDrawCalls());
}