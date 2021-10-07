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

#include <canvas/OpBuffer.h>

using namespace android;
using namespace android::uirenderer;

enum MockTypes {
    Lifecycle,
    NoOp,
    IntHolder,
    COUNT
};

using Op = MockTypes;

template<MockTypes T>
struct MockOp;

template<MockTypes T>
struct MockOpContainer {
    OpBufferItemHeader<MockTypes> header;
    MockOp<T> impl;

    MockOpContainer(MockOp<T>&& impl) : impl(std::move(impl)) {}
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

template<>
struct MockOp<MockTypes::NoOp> {};

template<>
struct MockOp<MockTypes::IntHolder> {
    int value = -1;
};

struct MockBuffer : public OpBuffer<MockTypes, MockOpContainer> {
    template <MockTypes T>
    void push(MockOp<T>&& op) {
        push_container(MockOpContainer<T>{std::move(op)});
    }
};

template<typename T>
static int countItems(const T& t) {
    int count = 0;
    t.for_each([&](auto i) {
        count++;
    });
    return count;
}

TEST(OpBuffer, lifecycleCheck) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<Op::Lifecycle> {
            MockOp<MockTypes::Lifecycle>{&tracker}
        });
        EXPECT_EQ(tracker.alive(), 1);
        buffer.clear();
        EXPECT_EQ(tracker.alive(), 0);
    }
    EXPECT_EQ(tracker.alive(), 0);
}

TEST(OpBuffer, lifecycleCheckMove) {
    LifecycleTracker tracker;
    {
        MockBuffer buffer;
        buffer.push_container(MockOpContainer<Op::Lifecycle> {
            MockOp<MockTypes::Lifecycle>{&tracker}
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
                MockOp<MockTypes::Lifecycle>{&tracker}
            });

            EXPECT_EQ(2, countItems(other));
            EXPECT_EQ(2, tracker.alive());

            buffer.push_container(MockOpContainer<MockTypes::Lifecycle> {
                MockOp<MockTypes::Lifecycle>{&tracker}
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

TEST(OpBuffer, verifyConst) {
    MockBuffer buffer;
    buffer.push<Op::IntHolder>({42});
    buffer.for_each([](auto op) {
        static_assert(std::is_const_v<std::remove_reference_t<decltype(*op)>>,
                "Expected container to be const");
    });
}

TEST(OpBuffer, filterView) {
    MockBuffer buffer;
    buffer.push<Op::NoOp>({});
    buffer.push<Op::IntHolder>({0});
    buffer.push<Op::IntHolder>({1});
    buffer.push<Op::NoOp>({});
    buffer.push<Op::NoOp>({});
    buffer.push<Op::IntHolder>({2});
    buffer.push<Op::NoOp>({});
    buffer.push<Op::NoOp>({});
    buffer.push<Op::NoOp>({});
    buffer.push<Op::NoOp>({});


    int index = 0;
    for (const auto& it : buffer.filter<Op::IntHolder>()) {
        ASSERT_EQ(Op::IntHolder, it.header.type);
        EXPECT_EQ(index, it.impl.value);
        index++;
    }
    EXPECT_EQ(index, 3);

    int count = 0;
    for (const auto& it : buffer.filter<Op::NoOp>()) {
        ASSERT_EQ(Op::NoOp, it.header.type);
        count++;
    }
    EXPECT_EQ(count, 7);
}

