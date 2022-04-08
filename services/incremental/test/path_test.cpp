/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "../path.h"

#include <gtest/gtest.h>

using namespace std::literals;

namespace android::incremental::path {

TEST(Path, Normalize) {
    EXPECT_STREQ("", normalize("").c_str());
    EXPECT_STREQ("/data/app/com.snapchat.android-evzhnJDgPOq8VcxwEkSY5g==/base.apk",
                 normalize("/data/app/com.snapchat.android-evzhnJDgPOq8VcxwEkSY5g==/base.apk")
                         .c_str());
    EXPECT_STREQ("/a/b", normalize("/a/c/../b").c_str());
}

TEST(Path, Comparator) {
    EXPECT_TRUE(PathLess()("/a", "/aa"));
    EXPECT_TRUE(PathLess()("/a/b", "/aa/b"));
    EXPECT_TRUE(PathLess()("/a", "/a/b"));
    EXPECT_TRUE(PathLess()("/a/b"sv, "/a\0"sv));
    EXPECT_TRUE(!PathLess()("/aa/b", "/a/b"));
    EXPECT_TRUE(!PathLess()("/a/b", "/a/b"));
    EXPECT_TRUE(!PathLess()("/a/b", "/a"));
}

} // namespace android::incremental::path
