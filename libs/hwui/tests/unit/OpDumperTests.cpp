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

#include "tests/common/TestUtils.h"
#include "OpDumper.h"

using namespace android;
using namespace android::uirenderer;

TEST(OpDumper, dump) {
    SkPaint paint;
    RectOp op(uirenderer::Rect(100, 100), Matrix4::identity(), nullptr, &paint);

    std::stringstream stream;
    OpDumper::dump(op, stream);
    EXPECT_STREQ("RectOp [100 x 100]", stream.str().c_str());

    stream.str("");
    OpDumper::dump(op, stream, 2);
    EXPECT_STREQ("    RectOp [100 x 100]", stream.str().c_str());

    ClipRect clipRect(uirenderer::Rect(50, 50));
    op.localClip = &clipRect;

    stream.str("");
    OpDumper::dump(op, stream, 2);
    EXPECT_STREQ("    RectOp [100 x 100] clip=[50 x 50] mode=0", stream.str().c_str());
}
