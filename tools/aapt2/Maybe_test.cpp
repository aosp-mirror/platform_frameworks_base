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
#include <string>

#include "Maybe.h"

namespace aapt {

struct Dummy {
    Dummy() {
        std::cerr << "Constructing Dummy " << (void *) this << std::endl;
    }

    Dummy(const Dummy& rhs) {
        std::cerr << "Copying Dummy " << (void *) this << " from " << (const void*) &rhs << std::endl;
    }

    Dummy(Dummy&& rhs) {
        std::cerr << "Moving Dummy " << (void *) this << " from " << (void*) &rhs << std::endl;
    }

    ~Dummy() {
        std::cerr << "Destroying Dummy " << (void *) this << std::endl;
    }
};

TEST(MaybeTest, MakeNothing) {
    Maybe<int> val = make_nothing<int>();
    EXPECT_FALSE(val);

    Maybe<std::string> val2 = make_nothing<std::string>();
    EXPECT_FALSE(val2);

    val2 = make_nothing<std::string>();
    EXPECT_FALSE(val2);
}

TEST(MaybeTest, MakeSomething) {
    Maybe<int> val = make_value(23);
    ASSERT_TRUE(val);
    EXPECT_EQ(23, val.value());

    Maybe<std::string> val2 = make_value(std::string("hey"));
    ASSERT_TRUE(val2);
    EXPECT_EQ(std::string("hey"), val2.value());
}

TEST(MaybeTest, Lifecycle) {
    Maybe<Dummy> val = make_nothing<Dummy>();

    Maybe<Dummy> val2 = make_value(Dummy());
}

} // namespace aapt
