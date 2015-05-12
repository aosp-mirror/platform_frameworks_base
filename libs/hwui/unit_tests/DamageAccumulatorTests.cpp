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

#include <DamageAccumulator.h>
#include <Matrix.h>
#include <utils/LinearAllocator.h>

#include <SkRect.h>

using namespace android;
using namespace android::uirenderer;

// Test that push & pop are propegating the dirty rect
// There is no transformation of the dirty rect, the input is the same
// as the output.
TEST(DamageAccumulator, identity) {
    DamageAccumulator da;
    Matrix4 identity;
    SkRect curDirty;
    identity.loadIdentity();
    da.pushTransform(&identity);
    da.dirty(50, 50, 100, 100);
    da.pushTransform(&identity);
    da.peekAtDirty(&curDirty);
    ASSERT_EQ(SkRect(), curDirty);
    da.popTransform();
    da.peekAtDirty(&curDirty);
    ASSERT_EQ(SkRect::MakeLTRB(50, 50, 100, 100), curDirty);
    da.popTransform();
    da.finish(&curDirty);
    ASSERT_EQ(SkRect::MakeLTRB(50, 50, 100, 100), curDirty);
}

// Test that transformation is happening at the correct levels via
// peekAtDirty & popTransform. Just uses a simple translate to test this
TEST(DamageAccumulator, translate) {
    DamageAccumulator da;
    Matrix4 translate;
    SkRect curDirty;
    translate.loadTranslate(25, 25, 0);
    da.pushTransform(&translate);
    da.dirty(50, 50, 100, 100);
    da.peekAtDirty(&curDirty);
    ASSERT_EQ(SkRect::MakeLTRB(50, 50, 100, 100), curDirty);
    da.popTransform();
    da.finish(&curDirty);
    ASSERT_EQ(SkRect::MakeLTRB(75, 75, 125, 125), curDirty);
}

// Test that dirty rectangles are being unioned across "siblings
TEST(DamageAccumulator, union) {
    DamageAccumulator da;
    Matrix4 identity;
    SkRect curDirty;
    identity.loadIdentity();
    da.pushTransform(&identity);
    da.pushTransform(&identity);
    da.dirty(50, 50, 100, 100);
    da.popTransform();
    da.pushTransform(&identity);
    da.dirty(150, 50, 200, 125);
    da.popTransform();
    da.popTransform();
    da.finish(&curDirty);
    ASSERT_EQ(SkRect::MakeLTRB(50, 50, 200, 125), curDirty);
}
