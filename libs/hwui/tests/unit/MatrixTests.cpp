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

#include "Matrix.h"
#include "Rect.h"

using namespace android::uirenderer;

TEST(Matrix, mapRect) {
    // Skew, so we don't hit identity/translate/simple fast paths
    Matrix4 matrix;
    matrix.skew(0.1f, 0.1f);

    // non-zero empty rect, so sorting x/y would make rect non-empty
    Rect empty(100, 100, -100, -100);
    ASSERT_TRUE(empty.isEmpty());
    matrix.mapRect(empty);
    EXPECT_TRUE(empty.isEmpty())
        << "Empty rect should always remain empty, regardless of mapping.";
}
